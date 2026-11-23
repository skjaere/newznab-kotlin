package io.skjaere.newznab

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * A client for Newznab and Torznab indexers:
 * ```
 * val newznab = Newznab {
 *     userAgent = "my-app/1.0"
 * }
 * val geek = indexer("geek") {
 *     url = "https://api.nzbgeek.info"
 *     apiKey = key
 * }
 * val page = newznab.search(geek) {
 *     tv {
 *         tvdb = 81189
 *         season = 1
 *         episode = 2
 *     }
 * }
 * val nzb = newznab.download(geek, page.items.first())
 * ```
 */
public fun Newznab(block: NewznabConfig.() -> Unit = {}): Newznab = Newznab(NewznabConfig().apply(block))

/**
 * Searches indexers and downloads their NZBs; see [Newznab()][io.skjaere.newznab.Newznab] for an example. Every failure is a
 * [NewznabException]. It keeps caps and [searchAll]'s answers for a while, and the time an indexer asked to be left
 * alone after hitting its API limit; [close] it when done.
 */
public class Newznab internal constructor(config: NewznabConfig) : AutoCloseable {
    init {
        require(config.userAgent.isNotBlank() && UserAgentRule.problem(config.userAgent) == null) {
            "userAgent must be printable ASCII of at most ${UserAgentRule.MAX_LENGTH} chars"
        }
        config.timeouts.check()
    }

    /** The client's own: the app's client (sharing its engine), or one on the engine on the classpath. */
    private val http: HttpClient = (config.httpClient?.config { configure() } ?: HttpClient { configure() })

    private val requests = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal val api: IndexerApi = IndexerApi(
        http = http,
        userAgent = config.userAgent,
        newznabTimeoutMs = config.timeouts.newznab.inWholeMilliseconds,
        torznabTimeoutMs = config.timeouts.torznab.inWholeMilliseconds,
        downloadTimeoutMs = config.timeouts.download.inWholeMilliseconds,
        maxNzbBytes = config.maxNzbBytes,
        maxAnswerBytes = config.maxAnswerBytes,
        parseDispatcher = config.parseDispatcher,
    )

    private val capsCache = CapsCache(api, config.clock, config.capsTtl.toJavaDuration())

    private val multi = MultiIndexerSearch(api, capsCache, config.clock, config.resultTtl.toJavaDuration(), requests)

    /** What requests send as their User-Agent, unless their indexer has its own ([Indexer.userAgent]). */
    public val userAgent: String = config.userAgent

    /** [indexer]'s caps: kept for [NewznabConfig.capsTtl] unless [fresh], e.g. for a settings page's "Test" button. */
    public suspend fun caps(indexer: Indexer, fresh: Boolean = false): IndexerCaps {
        if (fresh) capsCache.invalidate(indexer)
        return capsCache.get(indexer)
    }

    /** Searches [indexer] for [query]: one request, exactly as asked, never cached. */
    public suspend fun search(indexer: Indexer, query: SearchQuery, offset: Int = 0, limit: Int? = null): SearchPage =
        api.search(indexer, query, offset, limit)

    /**
     * Searches [indexer]:
     * ```
     * newznab.search(indexer) {
     *     movie { imdb = "tt0133093" }
     *     categories(Categories.MOVIES_HD, Categories.MOVIES_UHD)
     *     limit = 100
     * }
     * ```
     */
    public suspend fun search(indexer: Indexer, block: SearchBuilder.() -> Unit): SearchPage {
        val builder = SearchBuilder().apply(block)
        val query = checkNotNull(builder.built()) { "No search given: call text, tv, movie or query" }
        return api.search(indexer, query, builder.offset, builder.limit)
    }

    /**
     * Searches every enabled indexer of [indexers] at once, each with the query [plan] picks from its caps, and emits
     * each indexer's outcome as it arrives. One indexer failing or timing out never fails the others. Answers are reused
     * for [NewznabConfig.resultTtl] unless [fresh]. A Torznab indexer's query without categories asks for the Movies or
     * TV ones its caps list ([torznabCategories]).
     */
    public fun searchAll(indexers: List<Indexer>, fresh: Boolean = false, plan: SearchPlan.() -> Unit): Flow<IndexerOutcome> =
        multi.search(indexers, fresh) { indexer, caps ->
            val planned = SearchPlan(indexer, caps).apply(plan)
            planned.skipped?.let { Planned.Skip(it) }
                ?: planned.built()?.let { Planned.Search(it) }
                ?: Planned.Skip(SearchPlan.SKIPPED)
        }

    /** Searches every enabled indexer of [indexers] for [query], skipping those whose caps don't list its search. */
    public fun searchAll(indexers: List<Indexer>, query: SearchQuery, fresh: Boolean = false): Flow<IndexerOutcome> =
        searchAll(indexers, fresh) { if (caps.supports(query.type)) query(query) else skip() }

    /** Downloads [item]'s NZB from [indexer], with the archive password the indexer sent beside it. Counts as a grab. */
    public suspend fun download(indexer: Indexer, item: NewznabItem): NzbDownload =
        api.download(indexer, item.guid, item.downloadTemplate)

    /**
     * Downloads an NZB by its [guid] and stored [downloadTemplate] ([NewznabItem.downloadTemplate]; null asks `t=get`).
     */
    public suspend fun download(indexer: Indexer, guid: String, downloadTemplate: String?): NzbDownload =
        api.download(indexer, guid, downloadTemplate)

    /** Stops requests still running for dropped searches and closes this client's HttpClient (not an app's engine). */
    override fun close() {
        requests.cancel()
        http.close()
    }

    public companion object {
        public const val DEFAULT_USER_AGENT: String = "newznab-kotlin"
    }
}

private fun io.ktor.client.HttpClientConfig<*>.configure() {
    install(HttpTimeout)
    // Statuses are read here: an indexer's error document comes with them.
    expectSuccess = false
}

@NewznabDsl
public class NewznabConfig internal constructor() {
    /** Sent with every request to an indexer without a User-Agent of its own. Self-hosted indexers list clients by it. */
    public var userAgent: String = Newznab.DEFAULT_USER_AGENT

    /**
     * The client requests go through, sharing its engine and configuration. Null builds one on the Ktor engine on the
     * classpath.
     */
    public var httpClient: HttpClient? = null

    public val timeouts: Timeouts = Timeouts()

    public fun timeouts(block: Timeouts.() -> Unit) {
        timeouts.apply(block)
    }

    /** How long an indexer's caps are kept. */
    public var capsTtl: Duration = 24.hours

    /** How long [Newznab.searchAll]'s answers are reused, so asking again soon after doesn't spend API calls. */
    public var resultTtl: Duration = 15.minutes

    /** The largest NZB downloaded; a link that answers with something bigger fails. */
    public var maxNzbBytes: Long = 64L * 1024 * 1024

    /** The largest caps or search answer read. */
    public var maxAnswerBytes: Long = 32L * 1024 * 1024

    /** Where answers are decoded and parsed. */
    public var parseDispatcher: CoroutineDispatcher = Dispatchers.Default

    public var clock: Clock = Clock.systemUTC()
}

@NewznabDsl
public class Timeouts internal constructor() {
    /** A Newznab indexer's caps and searches. */
    public var newznab: Duration = 15.seconds

    /** A Torznab indexer's caps and searches: they ask trackers' sites, which are slow. */
    public var torznab: Duration = 25.seconds

    /** An NZB download. */
    public var download: Duration = 30.seconds

    internal fun check() {
        require(newznab.isPositive() && torznab.isPositive() && download.isPositive()) { "Timeouts must be positive" }
    }
}
