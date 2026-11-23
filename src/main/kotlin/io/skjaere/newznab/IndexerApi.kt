package io.skjaere.newznab

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.charset
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Talks to one Newznab-compatible API (Newznab, NNTmux, nZEDb, and the Prowlarr, NZBHydra and Jackett proxies), one
 * request per call. All failures surface as [NewznabException]; the API key never appears in logs or exception messages.
 *
 * Answers (an extended search page can be hundreds of KB) are decoded and parsed on [parseDispatcher]. Bodies are read
 * only up to [maxNzbBytes] or [maxAnswerBytes], so a link that answers with a video or an archive can't exhaust memory.
 */
internal class IndexerApi(
    private val http: HttpClient,
    val userAgent: String,
    private val newznabTimeoutMs: Long,
    private val torznabTimeoutMs: Long,
    private val downloadTimeoutMs: Long,
    private val maxNzbBytes: Long,
    private val maxAnswerBytes: Long,
    private val parseDispatcher: CoroutineDispatcher,
) {
    /** How long [indexer]'s caps and searches may take: Torznab indexers ask trackers' sites, which are slow. */
    fun timeoutOf(indexer: Indexer): Long = if (indexer.kind == IndexerKind.Torznab) torznabTimeoutMs else newznabTimeoutMs

    suspend fun caps(indexer: Indexer): IndexerCaps =
        getParsed(indexer, mapOf("t" to "caps"), NewznabXml::parseCaps)

    suspend fun search(indexer: Indexer, query: SearchQuery, offset: Int = 0, limit: Int? = null): SearchPage {
        val params = searchParams(query) + listOfNotNull(
            "extended" to "1",
            "offset" to offset.toString(),
            limit?.let { "limit" to it.toString() },
        )
        val page = getParsed(indexer, params.toMap()) { NewznabXml.parseSearch(it, indexer) }
        leftOutLine(indexer, page)?.let { log.info(it) }
        return page
    }

    /** Downloads an NZB: its stored link when there is one, else `t=get`. Counts as a grab. */
    suspend fun download(indexer: Indexer, guid: String, downloadTemplate: String?): NzbDownload {
        val url = downloadTemplate?.let { keyedLink(indexer, it) } ?: apiUrl(indexer, mapOf("t" to "get", "id" to guid))
        val download = request(indexer, url, downloadTimeoutMs) { response ->
            NzbDownload(
                response.bodyAtMost(maxNzbBytes, "The download is too large to be an NZB"),
                NzbDownload.passwordOf(response.headers),
            )
        }
        val bytes = download.bytes
        val head = bytes.copyOf(minOf(bytes.size, NZB_SNIFF_BYTES)).decodeToString()
        if ("<nzb" !in head) {
            withContext(parseDispatcher) { NewznabXml.checkError(bytes.decodeToString()) }
            throw NewznabException(NewznabError.BadResponse("Download is not an NZB"))
        }
        return download
    }

    fun searchParams(query: SearchQuery): List<Pair<String, String>> {
        val categories = query.categories.takeIf { it.isNotEmpty() }?.let { "cat" to it.joinToString(",") }
        return listOfNotNull(categories) + when (query) {
            is SearchQuery.Text -> listOf("t" to "search", "q" to query.query)
            is SearchQuery.Tv -> listOfNotNull(
                "t" to "tvsearch",
                query.query?.let { "q" to it },
                query.ids.tvdb?.let { "tvdbid" to it.toString() },
                query.ids.tmdb?.let { "tmdbid" to it.toString() },
                query.ids.imdb?.let { "imdbid" to it.removePrefix("tt") },
                query.ids.tvmaze?.let { "tvmazeid" to it.toString() },
                (query.airDate?.year ?: query.season)?.let { "season" to it.toString() },
                (
                    query.airDate?.let { "%02d/%02d".format(Locale.ROOT, it.monthValue, it.dayOfMonth) }
                        ?: query.episode?.toString()
                    )?.let { "ep" to it },
            )
            is SearchQuery.Movie -> listOfNotNull(
                "t" to "movie",
                query.query?.let { "q" to it },
                query.imdb?.let { "imdbid" to it.removePrefix("tt") },
                query.tmdb?.let { "tmdbid" to it.toString() },
            )
        }
    }

    private suspend fun <T> getParsed(indexer: Indexer, params: Map<String, String>, parse: (String) -> T): T {
        val timeoutMs = timeoutOf(indexer)
        val text = request(indexer, apiUrl(indexer, params), timeoutMs, silentMs = timeoutMs) { it.text() }
        return withContext(parseDispatcher) { parse(text) }
    }

    /**
     * GETs [url] and hands a successful answer to [read] on [parseDispatcher] while its body is still streaming, so
     * [read] decides how much of it is ever held in memory. [silentMs]: how long it may go without a byte (null keeps
     * the engine's own read timeout).
     */
    private suspend fun <T> request(
        indexer: Indexer,
        url: String,
        timeoutMs: Long,
        silentMs: Long? = null,
        read: suspend (HttpResponse) -> T,
    ): T {
        // A User-Agent the rule refuses never reaches the engine, whose error could repeat it.
        val own = indexer.userAgent.trim()
        if (UserAgentRule.problem(own) != null) throw NewznabException(NewznabError.BadResponse("Invalid User-Agent"))
        if (log.isDebugEnabled) log.debug(requestLine(indexer, url))
        return try {
            http.prepareGet(url) {
                // Never an empty header.
                header(HttpHeaders.UserAgent, own.ifEmpty { userAgent })
                timeout {
                    requestTimeoutMillis = timeoutMs
                    // Jackett and Prowlarr send nothing until their trackers have answered: the engine's own read
                    // timeout would end a caps call or search long before its kind's time.
                    silentMs?.let { socketTimeoutMillis = it }
                }
            }.execute { response ->
                if (!response.status.isSuccess()) throw statusError(response)
                withContext(parseDispatcher) { read(response) }
            }
        } catch (e: NewznabException) {
            throw e
        } catch (e: CancellationException) {
            // A caller's timeout too: its cancellation, not the indexer's failure.
            throw e
        } catch (_: HttpRequestTimeoutException) {
            throw NewznabException(NewznabError.Timeout(noAnswerWithin(timeoutMs)))
        } catch (e: SocketTimeoutException) {
            // Silent as long as it was given: its time ran out, whichever of the two ended it first. A grab, which keeps
            // the engine's read timeout, is a network failure.
            throw NewznabException(
                if (silentMs != null) NewznabError.Timeout(noAnswerWithin(timeoutMs)) else NewznabError.Network(safeMessage(e, indexer)),
            )
        } catch (e: Exception) {
            // IO errors, and whatever else Ktor or the engine throw (a redirect loop, a link they can't parse).
            throw NewznabException(NewznabError.Network(safeMessage(e, indexer)))
        }
    }

    /**
     * The error document an indexer sent with a failure status, else the status itself; but a 429 is the API limit
     * whatever document comes with it (Jackett's is its code 900, which alone reads as a server error), with its
     * Retry-After. A used-up grab allowance stays one, as NNTmux answers its code 501 with a 429 too.
     */
    private suspend fun statusError(response: HttpResponse): Throwable {
        val document = withContext(parseDispatcher) {
            val body = runCatching { response.text() }.getOrDefault("")
            runCatching { NewznabXml.checkError(body) }.exceptionOrNull()
        }
        if (response.status == HttpStatusCode.TooManyRequests) {
            if ((document as? NewznabException)?.error is NewznabError.DownloadLimitReached) return document
            return NewznabException(
                NewznabError.ApiLimitReached(API_LIMIT_REACHED, retryAfter(response.headers[HttpHeaders.RetryAfter], Instant.now())),
            )
        }
        return document ?: NewznabException(
            when (response.status) {
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> NewznabError.Auth("HTTP ${response.status.value}")
                HttpStatusCode.NotFound -> NewznabError.NotFound("HTTP 404")
                else -> NewznabError.Server("HTTP ${response.status.value}")
            },
        )
    }

    private suspend fun HttpResponse.text(): String =
        String(bodyAtMost(maxAnswerBytes, "The answer is too large"), charset() ?: Charsets.UTF_8)

    companion object {
        private val log = LoggerFactory.getLogger(Newznab::class.java)

        /** How much of a download is looked at for `<nzb`. */
        private const val NZB_SNIFF_BYTES = 512

        /** The body, read no further than one byte past [limit]; longer is a bad response saying [tooLarge]. */
        private suspend fun HttpResponse.bodyAtMost(limit: Long, tooLarge: String): ByteArray {
            if ((contentLength() ?: 0) > limit) throw NewznabException(NewznabError.BadResponse(tooLarge))
            val body = bodyAsChannel().readRemaining(limit + 1).readByteArray()
            if (body.size > limit) throw NewznabException(NewznabError.BadResponse(tooLarge))
            return body
        }

        private val origin = Regex("^[A-Za-z][A-Za-z0-9+.-]*://[^/?#]*")

        /** How a request is logged: by the indexer's name, its address left out, its key redacted. */
        fun requestLine(indexer: Indexer, url: String): String =
            "GET ${NewznabXml.redactApiKey(url, indexer.apiKey).replace(origin, "").ifEmpty { "/" }} from ${indexer.name}"

        /**
         * "Left out 3 of Home Prowlarr's 40 results: 3 without an info hash": what [page] left out, by why, of its
         * results (those kept and those left out); null for nothing.
         */
        fun leftOutLine(indexer: Indexer, page: SearchPage): String? {
            val counts = LeftOut.entries.mapNotNull { why -> page.leftOut[why]?.takeIf { it > 0 }?.let { why to it } }
            if (counts.isEmpty()) return null
            val reasons = counts.joinToString { (why, n) ->
                when (why) {
                    LeftOut.NotNzb -> if (n == 1) "1 not an NZB" else "$n not NZBs"
                    LeftOut.Nzb -> if (n == 1) "1 NZB" else "$n NZBs"
                    LeftOut.NoHash -> "$n without an info hash"
                }
            }
            val left = counts.sumOf { it.second }
            val total = page.items.size + left
            return "Left out $left of ${indexer.name}'s $total result${if (total == 1) "" else "s"}: $reasons"
        }

        /**
         * A Retry-After's wait from [now]: seconds, or an HTTP date (RFC 9110), a day at most ([MAX_RETRY_AFTER]). Null
         * for none, one that isn't either, or one already over.
         */
        fun retryAfter(header: String?, now: Instant): Duration? {
            val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val wait = value.toLongOrNull()?.let { runCatching { Duration.ofSeconds(it) }.getOrNull() }
                ?: runCatching {
                    Duration.between(now, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
                }.getOrNull()
            return wait?.takeIf { !it.isNegative && !it.isZero }?.coerceAtMost(MAX_RETRY_AFTER)
        }

        /**
         * The longest wait a Retry-After is taken for: a daily limit's. The indexer is kept out until the wait's end, a
         * time that must exist: a broken or hostile indexer's wait of Long.MAX_VALUE seconds would fail the whole search.
         */
        val MAX_RETRY_AFTER: Duration = Duration.ofDays(1)

        fun safeMessage(e: Throwable, indexer: Indexer): String =
            NewznabXml.redactApiKey(e.message ?: e::class.simpleName.orEmpty(), indexer.apiKey)

        /** [address] as requests use it, or null when Ktor can't parse it (java.net.URI is laxer, e.g. about the port). */
        fun parseBaseUrl(address: String): Url? = runCatching { Url(address.trim().trimEnd('/')) }.getOrNull()

        /**
         * [template] with the API key filled in, never less secure than [indexer]'s address: an http link from an https
         * indexer moves to https on the indexer's host, and one on another host gives null, so the caller asks `t=get`
         * instead (the key would leave in cleartext before any redirect to https could help).
         */
        fun keyedLink(indexer: Indexer, template: String): String? {
            val link = template.replace(NewznabItem.API_KEY_PLACEHOLDER, indexer.apiKey)
            val base = parseBaseUrl(indexer.url) ?: return null
            // A link Ktor can't parse is never sent: the request fails with a redacted error.
            val url = runCatching { Url(link) }.getOrNull()
            return when {
                url == null || base.protocol != URLProtocol.HTTPS || url.protocol == URLProtocol.HTTPS -> link
                url.host.equals(base.host, ignoreCase = true) ->
                    URLBuilder(url).apply {
                        protocol = URLProtocol.HTTPS
                        port = base.specifiedPort
                    }.buildString()
                else -> null
            }
        }

        fun apiUrl(indexer: Indexer, params: Map<String, String>): String {
            val base = parseBaseUrl(indexer.url) ?: throw NewznabException(NewznabError.BadResponse("Invalid indexer address"))
            return URLBuilder(base).apply {
                if (base.segments.lastOrNull() != "api") appendPathSegments("api")
                params.forEach { (k, v) -> parameters.append(k, v) }
                parameters.append("apikey", indexer.apiKey)
            }.buildString()
        }
    }
}
