package io.skjaere.newznab

import java.net.URI

/**
 * What an indexer's results are: NZBs from a Newznab indexer, torrents from a Torznab one (Prowlarr's or Jackett's).
 * A result that isn't of its indexer's kind is left out of the page ([LeftOut]).
 */
public enum class IndexerKind { Newznab, Torznab }

/**
 * An indexer to search. [apiKey] is a secret: it never appears in [toString], in logs or in exception messages.
 */
public data class Indexer(
    /** Stable id: the results' [NewznabItem.indexerId], and what the caches are keyed by (with the address and key). */
    val id: String,
    /** The API endpoint, e.g. `https://indexer.example.org/api` (`/api` is added when missing). */
    val url: String,
    val apiKey: String,
    val name: String = id,
    val kind: IndexerKind = IndexerKind.Newznab,
    /** A disabled indexer is left out of [Newznab.searchAll]. */
    val enabled: Boolean = true,
    /**
     * The User-Agent sent to this indexer in place of [Newznab.userAgent]; blank for that one. Some indexers only
     * answer known clients. It must pass [UserAgentRule].
     */
    val userAgent: String = "",
) {
    // Only the host: an address's path or login can hold a token.
    override fun toString(): String =
        "Indexer(id=$id, name=$name, host=${runCatching { URI(url.trim()).host }.getOrNull()}, kind=$kind, enabled=$enabled)"
}

/**
 * Builds an [Indexer]:
 * ```
 * val geek = indexer("geek") {
 *     url = "https://api.nzbgeek.info"
 *     apiKey = System.getenv("NZBGEEK_KEY")
 * }
 * ```
 */
public fun indexer(id: String, block: IndexerBuilder.() -> Unit): Indexer = IndexerBuilder(id).apply(block).build()

@NewznabDsl
public class IndexerBuilder internal constructor(private val id: String) {
    public var url: String = ""
    public var apiKey: String = ""
    public var name: String = id
    public var kind: IndexerKind = IndexerKind.Newznab
    public var enabled: Boolean = true
    public var userAgent: String = ""

    internal fun build(): Indexer {
        require(id.isNotBlank()) { "An indexer needs an id" }
        require(url.isNotBlank()) { "Indexer $id needs a url" }
        return Indexer(id, url, apiKey, name, kind, enabled, userAgent)
    }
}
