package io.skjaere.newznab

/**
 * What an indexer's own User-Agent ([Indexer.userAgent]) may be. Trimmed with Kotlin's trim(), it is blank (the client's
 * own is sent), or printable ASCII (' '..'~') of at most [MAX_LENGTH] chars. HTTP engines refuse other chars and some
 * name the whole value in their error; a tab, which some would send, is refused too. A settings form can check a value
 * with the same rule before it is saved.
 */
public object UserAgentRule {
    /** Room for any browser's string, which is about 150 chars. */
    public const val MAX_LENGTH: Int = 256

    public enum class Problem { Characters, TooLong }

    /** What keeps [value] from being sent, a char it can't hold first; null when it may be, blank included. */
    public fun problem(value: String): Problem? {
        val trimmed = value.trim()
        return when {
            trimmed.any { it !in ' '..'~' } -> Problem.Characters
            trimmed.length > MAX_LENGTH -> Problem.TooLong
            else -> null
        }
    }
}
