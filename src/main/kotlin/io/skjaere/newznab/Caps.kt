package io.skjaere.newznab

/** A search function, by its name in requests ([function]) and in caps ([capsElement]). */
public enum class SearchType(public val function: String, public val capsElement: String) {
    Search("search", "search"), Tv("tvsearch", "tv-search"), Movie("movie", "movie-search"),
}

public data class Category(val id: Int, val name: String, val subcategories: List<Category> = emptyList())

/** What an indexer says it can do (`t=caps`). */
public data class IndexerCaps(
    val serverTitle: String? = null,
    val serverVersion: String? = null,
    val maxLimit: Int? = null,
    val defaultLimit: Int? = null,
    /** Supported parameters per available search type, lower-case (`q`, `tvdbid`, `season`, `ep`, ...). */
    val searching: Map<SearchType, Set<String>> = emptyMap(),
    val categories: List<Category> = emptyList(),
    val limits: ApiLimits? = null,
) {
    /** Whether [type] is available, and takes [param] when one is given. */
    public fun supports(type: SearchType, param: String? = null): Boolean =
        searching[type]?.let { param == null || param.lowercase() in it } == true
}

/** Usage the indexer reported; any field may be missing. */
public data class ApiLimits(
    val apiCurrent: Int? = null,
    val apiMax: Int? = null,
    val grabCurrent: Int? = null,
    val grabMax: Int? = null,
)
