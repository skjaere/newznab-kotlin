package io.skjaere.newznab

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The public API: building indexers, queries and the client, and what they send. */
class NewznabDslTest {

    /** Indexers answering from fixtures by their host: caps by host, searches with [search]. */
    private class Indexers(
        private val caps: Map<String, String> = emptyMap(),
        private val search: (HttpRequestData) -> String = { dslFixture("newznab-search.xml") },
    ) {
        val requests = mutableListOf<HttpRequestData>()
        val http = HttpClient(
            MockEngine { request ->
                synchronized(requests) { requests += request }
                val body = when (request.url.parameters["t"]) {
                    "caps" -> dslFixture(caps[request.url.host] ?: "newznab-caps.xml")
                    "ping" -> "pong"
                    else -> search(request)
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/xml"))
            },
        )
        val newznab = Newznab { httpClient = http }

        fun searches(host: String? = null) =
            synchronized(requests) { requests.filter { it.url.parameters["t"] != "caps" && (host == null || it.url.host == host) } }
    }

    private val geek = indexer("geek") {
        url = "https://geek.example.org"
        apiKey = "SECRETKEY"
    }

    @Test
    fun `an indexer is built with its defaults, and printed without its key or path`() {
        val built = indexer("home") {
            url = "https://user:pass@home.example.org/token/api"
            apiKey = "SECRETKEY"
        }
        assertEquals(Indexer("home", "https://user:pass@home.example.org/token/api", "SECRETKEY"), built)
        assertEquals("home", built.name)
        assertEquals(IndexerKind.Newznab, built.kind)
        assertTrue(built.enabled)
        assertFalse("SECRETKEY" in built.toString(), built.toString())
        assertFalse("token" in built.toString() || "pass" in built.toString(), built.toString())
        assertTrue("home.example.org" in built.toString(), built.toString())
        assertFailsWith<IllegalArgumentException> { indexer("nourl") { apiKey = "x" } }
    }

    @Test
    fun `a TV search sends its ids, episode, categories and page`() = runBlocking<Unit> {
        val indexers = Indexers()
        indexers.newznab.search(geek) {
            tv {
                tvdb = 81189
                imdb = "tt0903747"
                season = 1
                episode = 2
            }
            categories(Categories.TV_HD, Categories.TV_UHD)
            offset = 100
            limit = 50
        }
        val sent = indexers.searches().single().url
        assertEquals("/api", sent.encodedPath)
        assertEquals(
            mapOf(
                "cat" to "5040,5045", "t" to "tvsearch", "tvdbid" to "81189", "imdbid" to "0903747", "season" to "1",
                "ep" to "2", "extended" to "1", "offset" to "100", "limit" to "50", "apikey" to "SECRETKEY",
            ),
            sent.parameters.entries().associate { (k, v) -> k to v.single() },
        )
    }

    @Test
    fun `a daily episode is searched by its air date`() = runBlocking<Unit> {
        val indexers = Indexers()
        indexers.newznab.search(geek) {
            tv {
                tvdb = 71256
                airDate = LocalDate.of(2026, 3, 9)
            }
        }
        val sent = indexers.searches().single().url.parameters
        assertEquals("2026", sent["season"])
        assertEquals("03/09", sent["ep"])
    }

    @Test
    fun `movie and text searches use their functions`() = runBlocking<Unit> {
        val indexers = Indexers()
        indexers.newznab.search(geek) { movie { imdb = "tt0133093" } }
        indexers.newznab.search(geek) { text("harbor lights") }
        val (movie, text) = indexers.searches().map { it.url.parameters }
        assertEquals("movie", movie["t"])
        assertEquals("0133093", movie["imdbid"])
        assertEquals("search", text["t"])
        assertEquals("harbor lights", text["q"])
    }

    @Test
    fun `a search is exactly one query`() {
        assertFailsWith<IllegalStateException> {
            searchQuery {
                text("one")
                movie { imdb = "tt0133093" }
            }
        }
        assertFailsWith<IllegalStateException> { searchQuery { categories(Categories.TV) } }
        assertFailsWith<IllegalArgumentException> {
            searchQuery {
                tv {
                    season = 1
                    airDate = LocalDate.of(2026, 3, 9)
                }
            }
        }
        assertEquals(
            SearchQuery.Movie(imdb = "tt0133093", categories = listOf(Categories.MOVIES)),
            searchQuery {
                movie { imdb = "tt0133093" }
                categories(Categories.MOVIES)
            },
        )
    }

    @Test
    fun `search results come back parsed`() = runBlocking<Unit> {
        val page = Indexers().newznab.search(geek) { text("harbor lights") }
        assertEquals(2, page.items.size)
        assertEquals("Harbor.Lights.S01E02.1080p.WEB-DL.DDP5.1.H.264-NTGRP", page.items.first().title)
        assertEquals("geek", page.items.first().indexerId)
    }

    @Test
    fun `searchAll runs each indexer's plan against its caps`() = runBlocking<Unit> {
        val prowlarr = indexer("prowlarr") {
            url = "https://prowlarr.example.org"
            apiKey = "K2"
        }
        val skipped = indexer("skipped") {
            url = "https://skipped.example.org"
            apiKey = "K3"
        }
        val idle = indexer("idle") {
            url = "https://idle.example.org"
            apiKey = "K4"
        }
        val off = indexer("off") {
            url = "https://off.example.org"
            apiKey = "K5"
            enabled = false
        }
        val indexers = Indexers(caps = mapOf("prowlarr.example.org" to "prowlarr-caps.xml"))

        val outcomes = indexers.newznab.searchAll(listOf(geek, prowlarr, skipped, idle, off)) {
            when {
                indexer.id == "skipped" -> skip("Not this one")
                indexer.id == "idle" -> Unit
                caps.supports(SearchType.Tv, "tmdbid") -> tv { tmdb = 1396 }
                else -> text("Breaking Bad")
            }
        }.toList().associateBy { it.indexer.id }

        assertEquals(setOf("geek", "prowlarr", "skipped", "idle"), outcomes.keys, "a disabled indexer isn't searched")
        assertEquals(SearchQuery.Text("Breaking Bad"), assertIs<IndexerOutcome.Results>(outcomes["geek"]).query)
        assertEquals(SearchQuery.Tv(ids = TvIds(tmdb = 1396)), assertIs<IndexerOutcome.Results>(outcomes["prowlarr"]).query)
        assertEquals("Not this one", assertIs<IndexerOutcome.Skipped>(outcomes["skipped"]).reason)
        assertEquals("Doesn't support this search", assertIs<IndexerOutcome.Skipped>(outcomes["idle"]).reason)
        assertEquals("1396", indexers.searches("prowlarr.example.org").single().url.parameters["tmdbid"])
    }

    @Test
    fun `searchAll with one query skips the indexers whose caps don't list its search`() = runBlocking<Unit> {
        val prowlarr = indexer("prowlarr") {
            url = "https://prowlarr.example.org"
            apiKey = "K2"
        }
        val indexers = Indexers(caps = mapOf("prowlarr.example.org" to "prowlarr-caps.xml"))
        val outcomes = indexers.newznab.searchAll(listOf(geek, prowlarr), searchQuery { movie { imdb = "tt0133093" } })
            .toList().associateBy { it.indexer.id }
        assertIs<IndexerOutcome.Results>(outcomes["geek"])
        assertIs<IndexerOutcome.Skipped>(outcomes["prowlarr"])
        assertEquals(emptyList(), indexers.searches("prowlarr.example.org"))
    }

    @Test
    fun `a Torznab indexer's search asks for the categories its caps list`() = runBlocking<Unit> {
        val jackett = indexer("jackett") {
            url = "https://jackett.example.org"
            apiKey = "K"
            kind = IndexerKind.Torznab
        }
        val indexers = Indexers(search = { dslFixture("torznab-prowlarr.xml") })
        indexers.newznab.searchAll(listOf(jackett)) { tv { tvdb = 81189 } }.toList()
        assertEquals("5000", indexers.searches().single().url.parameters["cat"])
    }

    @Test
    fun `caps are kept until asked for fresh`() = runBlocking<Unit> {
        val indexers = Indexers()
        val caps = indexers.newznab.caps(geek)
        indexers.newznab.caps(geek)
        assertEquals(1, indexers.requests.size)
        assertEquals(caps, indexers.newznab.caps(geek, fresh = true))
        assertEquals(2, indexers.requests.size)
    }

    @Test
    fun `requests send the configured user agent, or the indexer's own`() = runBlocking<Unit> {
        val indexers = Indexers()
        val newznab = Newznab {
            httpClient = indexers.http
            userAgent = "debridav/1.0"
        }
        newznab.search(geek) { text("a") }
        newznab.search(geek.copy(userAgent = "Mozilla/5.0")) { text("b") }
        Newznab { httpClient = indexers.http }.search(geek) { text("c") }
        assertEquals(
            listOf("debridav/1.0", "Mozilla/5.0", Newznab.DEFAULT_USER_AGENT),
            indexers.searches().map { it.headers[HttpHeaders.UserAgent] },
        )
    }

    @Test
    fun `a client is refused a user agent or timeouts that can't work`() {
        assertFailsWith<IllegalArgumentException> { Newznab { userAgent = "bad\u0007agent" } }
        assertFailsWith<IllegalArgumentException> { Newznab { userAgent = " " } }
        assertFailsWith<IllegalArgumentException> {
            Newznab { timeouts { download = kotlin.time.Duration.ZERO } }
        }
    }

    @Test
    fun `closing the client leaves the app's own HttpClient working`() = runBlocking<Unit> {
        val indexers = Indexers()
        indexers.newznab.search(geek) { text("a") }
        indexers.newznab.close()
        assertEquals("pong", indexers.http.get("https://geek.example.org/api?t=ping").bodyAsText())
    }

    private companion object {
        fun dslFixture(name: String): String =
            requireNotNull(NewznabDslTest::class.java.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()
    }
}
