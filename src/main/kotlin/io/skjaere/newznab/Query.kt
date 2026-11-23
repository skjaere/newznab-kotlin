package io.skjaere.newznab

import java.time.LocalDate

public data class TvIds(val tvdb: Int? = null, val tmdb: Int? = null, val imdb: String? = null, val tvmaze: Int? = null)

/** One search, as Newznab's `t=search`, `t=tvsearch` or `t=movie`. Built with the search DSL, or directly. */
public sealed interface SearchQuery {
    public val categories: List<Int>

    /** The search function this query uses, as caps list them. */
    public val type: SearchType

    public data class Text(val query: String, override val categories: List<Int> = emptyList()) : SearchQuery {
        override val type: SearchType get() = SearchType.Search
    }

    public data class Tv(
        val query: String? = null,
        val ids: TvIds = TvIds(),
        val season: Int? = null,
        val episode: Int? = null,
        /** A daily show's episode, asked the Newznab way: `season=YYYY&ep=MM/DD`, in place of [season] and [episode]. */
        val airDate: LocalDate? = null,
        override val categories: List<Int> = emptyList(),
    ) : SearchQuery {
        override val type: SearchType get() = SearchType.Tv
    }

    public data class Movie(
        val query: String? = null,
        val imdb: String? = null,
        val tmdb: Int? = null,
        override val categories: List<Int> = emptyList(),
    ) : SearchQuery {
        override val type: SearchType get() = SearchType.Movie
    }
}

/** [this] query asking for [categories] in place of its own. */
public fun SearchQuery.withCategories(categories: List<Int>): SearchQuery = when (this) {
    is SearchQuery.Text -> copy(categories = categories)
    is SearchQuery.Tv -> copy(categories = categories)
    is SearchQuery.Movie -> copy(categories = categories)
}

/** Newznab's standard categories, as indexers, Prowlarr and Jackett number them. */
public object Categories {
    public const val MOVIES: Int = 2000
    public const val MOVIES_FOREIGN: Int = 2010
    public const val MOVIES_SD: Int = 2030
    public const val MOVIES_HD: Int = 2040
    public const val MOVIES_UHD: Int = 2045
    public const val TV: Int = 5000
    public const val TV_FOREIGN: Int = 5020
    public const val TV_SD: Int = 5030
    public const val TV_HD: Int = 5040
    public const val TV_UHD: Int = 5045
    public const val TV_ANIME: Int = 5070
}

/**
 * The categories a Torznab indexer's [query] should ask for: of the Movies and TV parents its [caps] list, TV's for a TV
 * search, Movies' for a film's, both for words alone. Jackett runs a search without categories over every category a site
 * has, software and music among them. None listed: none.
 */
public fun torznabCategories(caps: IndexerCaps, query: SearchQuery): List<Int> {
    val wanted = when (query) {
        is SearchQuery.Tv -> listOf(Categories.TV)
        is SearchQuery.Movie -> listOf(Categories.MOVIES)
        is SearchQuery.Text -> listOf(Categories.MOVIES, Categories.TV)
    }
    val listed = caps.categories.map { it.id }.toSet()
    return wanted.filter { it in listed }
}

/**
 * Picks one search: [text], [tv] or [movie], and optionally its [categories].
 * ```
 * tv {
 *     tvdb = 81189
 *     season = 1
 *     episode = 2
 * }
 * categories(Categories.TV_HD, Categories.TV_UHD)
 * ```
 */
@NewznabDsl
public open class QueryBuilder internal constructor() {
    private var query: SearchQuery? = null
    private var categories: List<Int> = emptyList()

    /** A free-text search (`t=search`). */
    public fun text(query: String) {
        set(SearchQuery.Text(query))
    }

    /** A TV search (`t=tvsearch`). */
    public fun tv(block: TvQueryBuilder.() -> Unit) {
        set(TvQueryBuilder().apply(block).build())
    }

    /** A movie search (`t=movie`). */
    public fun movie(block: MovieQueryBuilder.() -> Unit) {
        set(MovieQueryBuilder().apply(block).build())
    }

    /** This search's [query], built elsewhere. */
    public fun query(query: SearchQuery) {
        set(query)
    }

    /** The categories to search in (`cat=`); none searches the indexer's defaults. */
    public fun categories(vararg ids: Int) {
        categories = ids.toList()
    }

    public fun categories(ids: Iterable<Int>) {
        categories = ids.toList()
    }

    internal fun built(): SearchQuery? = query?.let { if (categories.isEmpty()) it else it.withCategories(categories) }

    private fun set(query: SearchQuery) {
        check(this.query == null) { "A search is one of text, tv, movie or query" }
        this.query = query
    }
}

@NewznabDsl
public class TvQueryBuilder internal constructor() {
    /** Words to search for, alone or beside the ids. */
    public var query: String? = null
    public var tvdb: Int? = null
    public var tmdb: Int? = null

    /** An IMDb id, with or without its `tt`. */
    public var imdb: String? = null
    public var tvmaze: Int? = null
    public var season: Int? = null
    public var episode: Int? = null

    /** A daily show's episode by its air date, in place of [season] and [episode]. */
    public var airDate: LocalDate? = null

    internal fun build(): SearchQuery.Tv {
        require(airDate == null || season == null && episode == null) {
            "A daily episode is searched by its air date alone, without a season or episode"
        }
        return SearchQuery.Tv(query, TvIds(tvdb, tmdb, imdb, tvmaze), season, episode, airDate)
    }
}

@NewznabDsl
public class MovieQueryBuilder internal constructor() {
    /** Words to search for, alone or beside the ids. */
    public var query: String? = null

    /** An IMDb id, with or without its `tt`. */
    public var imdb: String? = null
    public var tmdb: Int? = null

    internal fun build(): SearchQuery.Movie = SearchQuery.Movie(query, imdb, tmdb)
}

/** Builds a [SearchQuery] on its own, e.g. to keep or to pass to [Newznab.searchAll]. */
public fun searchQuery(block: QueryBuilder.() -> Unit): SearchQuery =
    checkNotNull(QueryBuilder().apply(block).built()) { "No search given: call text, tv, movie or query" }

/** A search of one indexer: its query, and which page of results. */
@NewznabDsl
public class SearchBuilder internal constructor() : QueryBuilder() {
    /** How many results to skip (`offset=`). */
    public var offset: Int = 0

    /** How many results to ask for (`limit=`); null for the indexer's default. Indexers cap it at their caps' maximum. */
    public var limit: Int? = null
}

/**
 * The search one indexer runs in [Newznab.searchAll], picked from its [caps]: a query as [QueryBuilder] builds one,
 * or [skip]. Picking nothing skips the indexer too.
 * ```
 * newznab.searchAll(indexers) {
 *     if (caps.supports(SearchType.Tv, "tvdbid")) tv { tvdb = 81189; season = 1; episode = 2 }
 *     else text("Breaking Bad S01E02")
 * }
 * ```
 */
@NewznabDsl
public class SearchPlan internal constructor(public val indexer: Indexer, public val caps: IndexerCaps) : QueryBuilder() {
    internal var skipped: String? = null
        private set

    /** Leaves this indexer out of the search, giving [IndexerOutcome.Skipped] with [reason]. */
    public fun skip(reason: String = SKIPPED) {
        skipped = reason
    }

    internal companion object {
        const val SKIPPED = "Doesn't support this search"
    }
}
