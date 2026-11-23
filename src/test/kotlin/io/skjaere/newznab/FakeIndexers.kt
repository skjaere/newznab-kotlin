package io.skjaere.newznab

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

const val KEY = "TESTKEY123"

fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

fun indexer(id: String = "nz", host: String = "indexer.example.org", key: String = KEY) =
    Indexer(id = id, url = "https://$host", apiKey = key)

fun MockRequestHandleScope.xml(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/xml"))

/**
 * An [IndexerApi] over [http], with [NewznabConfig]'s defaults for whatever isn't given: 15 s for a Newznab indexer,
 * 25 s for a Torznab one, 30 s for a download, 64 MiB NZBs, 32 MiB answers, parsed on [Dispatchers.Default].
 */
internal fun indexerApi(
    http: HttpClient,
    userAgent: String = Newznab.DEFAULT_USER_AGENT,
    newznabTimeoutMs: Long = 15_000,
    torznabTimeoutMs: Long = 25_000,
    downloadTimeoutMs: Long = 30_000,
    maxNzbBytes: Long = 64L * 1024 * 1024,
    maxAnswerBytes: Long = 32L * 1024 * 1024,
    parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) = IndexerApi(http, userAgent, newznabTimeoutMs, torznabTimeoutMs, downloadTimeoutMs, maxNzbBytes, maxAnswerBytes, parseDispatcher)

/** An [IndexerApi] whose requests are answered by [handler]; every request is recorded. */
internal class FakeIndexers(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    val requests = mutableListOf<HttpRequestData>()

    // Configured as Newznab configures the client it is given.
    val http = HttpClient(MockEngine { request -> requests += request; handler(request) }) {
        install(HttpTimeout)
        expectSuccess = false
    }
    val api = indexerApi(http)
}
