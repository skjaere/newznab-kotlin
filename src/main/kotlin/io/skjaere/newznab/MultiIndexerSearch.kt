package io.skjaere.newznab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext

/** Caps per indexer, kept for [ttl]. A failed fetch is not cached. */
internal class CapsCache(
    private val api: IndexerApi,
    private val clock: Clock,
    private val ttl: Duration,
) {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Pair<Instant, IndexerCaps>>()

    suspend fun get(indexer: Indexer): IndexerCaps {
        kept(indexer)?.let { return it }
        val caps = api.caps(indexer)
        mutex.withLock { entries[indexer.cacheKey()] = clock.instant() to caps }
        return caps
    }

    suspend fun invalidate(indexer: Indexer) {
        mutex.withLock { entries.remove(indexer.cacheKey()) }
    }

    /** [indexer]'s caps while they are kept, without asking for them; null once they would have to be asked for. */
    suspend fun kept(indexer: Indexer): IndexerCaps? =
        mutex.withLock { entries[indexer.cacheKey()] }?.takeIf { (at, _) -> at.plus(ttl).isAfter(clock.instant()) }?.second

    // A changed address, key or User-Agent must not reuse the old answer.
    private fun Indexer.cacheKey() = "$id|$url|${apiKey.hashCode()}|${userAgent.trim()}"
}

/** What a plan picked for one indexer: a query, or to skip it. */
internal sealed interface Planned {
    data class Search(val query: SearchQuery) : Planned
    data class Skip(val reason: String) : Planned
}

/**
 * Runs one search on every enabled indexer in parallel and emits each indexer's outcome as it arrives, so fast indexers'
 * results can be shown while slow ones are still working. One indexer failing or timing out never affects the others.
 * A search dropped while an indexer is still answering stops at once, but leaves that request to finish into the cache:
 * the indexer counted it against its API limit already. A Torznab indexer's query names the categories its caps list
 * ([torznabCategories]). An indexer that answered its limit with a Retry-After is kept out until it ends.
 */
internal class MultiIndexerSearch(
    private val api: IndexerApi,
    private val caps: CapsCache,
    private val clock: Clock,
    /** Answers are reused this long, so asking again soon after doesn't spend API calls. */
    private val resultTtl: Duration,
    /** The requests' parent, not the searches waiting for them: a request outlives a search dropped midway. */
    private val requests: CoroutineScope,
) {
    /** Guards [results], [sent] and [limited]. */
    private val lock = Any()
    private val results = HashMap<String, Pair<Instant, SearchPage>>()

    /** The requests on their way, by the same key as [results]. */
    private val sent = HashMap<String, Deferred<SearchPage>>()

    /** Until when each indexer is kept out, by [limitKey]: it answered its limit with a Retry-After. */
    private val limited = HashMap<String, Instant>()

    /** [plan] picks the query for each indexer from its caps. [fresh] asks every indexer again rather than reusing an answer. */
    fun search(indexers: List<Indexer>, fresh: Boolean, plan: (Indexer, IndexerCaps) -> Planned): Flow<IndexerOutcome> =
        channelFlow {
            indexers.filter { it.enabled }.forEach { indexer ->
                launch {
                    val timeoutMs = api.timeoutOf(indexer)
                    val outcome = withTimeoutOrNull(timeoutMs) { searchOne(indexer, fresh, plan) }
                        ?: IndexerOutcome.Failed(indexer, NewznabError.Timeout(noAnswerWithin(timeoutMs)))
                    send(outcome)
                }
            }
        }

    private suspend fun searchOne(indexer: Indexer, fresh: Boolean, plan: (Indexer, IndexerCaps) -> Planned): IndexerOutcome = try {
        val left = stillLimited(indexer)
        // Kept out, it isn't asked: every request meanwhile would be refused, and count against it. What it answered
        // before and is still kept is given as ever, as no request is made for it; anything else, a fresh search too,
        // is its limit.
        val limited = left?.let { IndexerOutcome.Failed(indexer, NewznabError.ApiLimitReached(API_LIMIT_REACHED, it)) }
        val found = if (left == null) caps.get(indexer) else caps.kept(indexer)
        when (val planned = found?.let { plan(indexer, it) }) {
            null -> limited!!
            is Planned.Skip -> IndexerOutcome.Skipped(indexer, planned.reason)
            is Planned.Search -> {
                val query = planned.query.let { query ->
                    if (indexer.kind == IndexerKind.Torznab && query.categories.isEmpty()) {
                        query.withCategories(torznabCategories(found, query))
                    } else {
                        query
                    }
                }
                // Failures are never cached; a changed address, key or User-Agent is a different entry.
                val key = "${limitKey(indexer)}|${indexer.userAgent.trim()}|$query"
                if (limited == null) {
                    IndexerOutcome.Results(indexer, page(indexer, key, fresh) { api.search(indexer, query) }, query)
                } else {
                    kept(key).takeIf { !fresh }?.let { IndexerOutcome.Results(indexer, it, query) } ?: limited
                }
            }
        }
    } catch (e: NewznabException) {
        holdOff(indexer, e.error)
        IndexerOutcome.Failed(indexer, e.error)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Anything else is still this indexer's failure alone; it must not fail the whole search.
        IndexerOutcome.Failed(indexer, NewznabError.BadResponse(IndexerApi.safeMessage(e, indexer)))
    }

    /** How long [indexer] is still kept out; null once it may be asked. */
    private fun stillLimited(indexer: Indexer): Duration? = synchronized(lock) {
        val key = limitKey(indexer)
        val left = limited[key]?.let { until -> Duration.between(clock.instant(), until) }
        if (left != null && (left.isZero || left.isNegative)) limited.remove(key)
        left?.takeIf { !it.isZero && !it.isNegative }
    }

    /** Keeps [indexer] out for its limit's Retry-After, if [error] is one that came with it. */
    private fun holdOff(indexer: Indexer, error: NewznabError) {
        val wait = (error as? NewznabError.ApiLimitReached)?.retryAfter ?: return
        synchronized(lock) { limited[limitKey(indexer)] = clock.instant().plus(wait) }
    }

    /** [key]'s answer while it is kept, without asking. */
    private fun kept(key: String): SearchPage? = synchronized(lock) {
        results[key]?.takeIf { (at, _) -> at.plus(resultTtl).isAfter(clock.instant()) }?.second
    }

    /**
     * [key]'s answer: cached, else from the request already on its way for it, else from a new one ([ask]); [fresh]
     * always asks anew. A request whose search was dropped goes on into the cache, and the same search a moment later
     * waits for it instead of asking again; and its limit, if that is what it answered, keeps [indexer] out as a
     * waited-for one's does.
     */
    private suspend fun page(indexer: Indexer, key: String, fresh: Boolean, ask: suspend () -> SearchPage): SearchPage {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor]
        val timeoutMs = api.timeoutOf(indexer)
        val request = synchronized(lock) {
            if (!fresh) {
                kept(key)?.let { return it }
                sent[key]?.let { return@synchronized it }
            }
            // On the searcher's dispatcher.
            requests.async(dispatcher ?: EmptyCoroutineContext, start = CoroutineStart.LAZY) {
                // Its own timeout: nobody may be waiting for it any more.
                val page = try {
                    withTimeoutOrNull(timeoutMs) { ask() } ?: throw NewznabException(NewznabError.Timeout(noAnswerWithin(timeoutMs)))
                } catch (e: NewznabException) {
                    holdOff(indexer, e.error)
                    throw e
                }
                synchronized(lock) { results[key] = clock.instant() to page }
                page
            }.also { started ->
                sent[key] = started
                started.invokeOnCompletion { synchronized(lock) { sent.remove(key, started) } }
            }
        }
        request.start()
        return request.await()
    }

    companion object {
        /**
         * How an indexer's limit is kept: by its address and key too, so another account's limit isn't this one's, but
         * never by its User-Agent, as the limit is the account's whatever asks.
         */
        fun limitKey(indexer: Indexer): String = "${indexer.id}|${indexer.url}|${indexer.apiKey.hashCode()}"
    }
}
