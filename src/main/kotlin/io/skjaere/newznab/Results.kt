package io.skjaere.newznab

import java.time.Instant

/** One result of a search. */
public data class NewznabItem(
    val indexerId: String,
    /**
     * Stable id for `t=get&id=`. A torrent's is [TORRENT_GUID_PREFIX] and its info hash: a Torznab feed's own guid can be
     * a tracker link holding a passkey.
     */
    val guid: String,
    val title: String,
    val sizeBytes: Long,
    val publishedAt: Instant?,
    /** When the release was posted to Usenet; falls back to [publishedAt]. */
    val postedAt: Instant?,
    val categories: List<Int>,
    val grabs: Int?,
    val passworded: Boolean,
    val files: Int?,
    val tvdbId: Int?,
    val tmdbId: Int?,
    /** `tt` and at least seven digits. */
    val imdbId: String?,
    val tvmazeId: Int?,
    val season: Int?,
    val episode: Int?,
    val coverUrl: String?,
    /**
     * The item's download link with the API key replaced by [API_KEY_PLACEHOLDER], so it can be stored. Null when the
     * indexer gave none (then `t=get&id=guid` is used) and for a torrent.
     */
    val downloadTemplate: String?,
    /** Every `newznab:attr`/`torznab:attr`, by lower-case name; a name can repeat (`audio`, `language`, `category`). */
    val attributes: Map<String, List<String>>,
    /** A torrent's v1 info hash, 40 lower-case hex digits; null for an NZB. */
    val infoHash: String? = null,
    /** The enclosure's media type as the feed gave it (`application/x-nzb`, `application/x-bittorrent`); null when none. */
    val enclosureType: String? = null,
) {
    /** The first non-blank value of the attribute [name]. */
    public fun attribute(name: String): String? = attributes[name.lowercase()]?.firstOrNull { it.isNotBlank() }

    /** Every non-blank value of the attribute [name]. */
    public fun attributeValues(name: String): List<String> = attributes[name.lowercase()].orEmpty().filter { it.isNotBlank() }

    /** The video codec the indexer reports (`video`), e.g. `x264`. */
    public val video: String? get() = attribute("video")

    /** The audio tracks the indexer reports (`audio`), e.g. `AC3 5.1`; a list in one value is split. */
    public val audio: List<String> get() = listValues("audio")

    /** The resolution the indexer reports (`resolution`), e.g. `1920x1080`. */
    public val resolution: String? get() = attribute("resolution")

    /** The audio languages the indexer reports (`language`); a list in one value (`German, English`) is split. */
    public val languages: List<String> get() = listValues("language")

    /** The subtitle languages the indexer reports (`subs`); a list in one value is split. */
    public val subtitles: List<String> get() = listValues("subs")

    // Indexers write a list as repeated attributes or in one value, joined by any of these.
    private fun listValues(name: String): List<String> =
        attributeValues(name).flatMap { it.split(',', '/', '|', ';') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    public companion object {
        public const val API_KEY_PLACEHOLDER: String = "{apikey}"

        /** What a torrent's [guid] starts with, its info hash after it. */
        public const val TORRENT_GUID_PREFIX: String = "btih:"
    }
}

/** One page of a search's results. */
public data class SearchPage(
    val items: List<NewznabItem>,
    val offset: Int,
    val total: Int?,
    val limits: ApiLimits?,
    /** How many of the feed's items were left out of [items], by why: what this indexer's kind can't use. */
    val leftOut: Map<LeftOut, Int> = emptyMap(),
    /** The feed's items as read, in its order: null for one kept in [items], else why it was left out. */
    val feed: List<LeftOut?> = emptyList(),
)

/** Why a feed's item was left out of its [SearchPage]: what its indexer's [IndexerKind] can't use. */
public enum class LeftOut {
    /** On a Newznab indexer, an item whose enclosure isn't an NZB: a torrent feed added as a Newznab indexer. */
    NotNzb,

    /** On a Torznab indexer, an NZB: a Usenet feed added as a Torznab indexer. */
    Nzb,

    /**
     * On a Torznab indexer, a torrent with no v1 info hash: neither an `infohash` attribute nor a magnet holding one
     * (a v2-only magnet among them).
     */
    NoHash,
}

/** One indexer's part of [Newznab.searchAll]. */
public sealed interface IndexerOutcome {
    public val indexer: Indexer

    public data class Results(override val indexer: Indexer, val page: SearchPage, val query: SearchQuery) : IndexerOutcome

    public data class Failed(override val indexer: Indexer, val error: NewznabError) : IndexerOutcome

    /** The indexer's plan skipped it, e.g. as its caps don't list the search. */
    public data class Skipped(override val indexer: Indexer, val reason: String) : IndexerOutcome
}
