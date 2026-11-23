package io.skjaere.newznab

import java.time.Duration

/** What went wrong talking to one indexer. The messages never hold the API key. */
public sealed class NewznabError(public val message: String) {
    public class Auth(message: String) : NewznabError(message)

    /** The indexer's API limit; [retryAfter] is how long it asked to be left alone, when it said. */
    public class ApiLimitReached(message: String, public val retryAfter: Duration? = null) : NewznabError(message)
    public class DownloadLimitReached(message: String) : NewznabError(message)
    public class Disabled(message: String) : NewznabError(message)
    public class Unsupported(message: String) : NewznabError(message)
    public class NotFound(message: String) : NewznabError(message)
    public class Server(message: String) : NewznabError(message)
    public class BadResponse(message: String) : NewznabError(message)
    public class Timeout(message: String) : NewznabError(message)
    public class Network(message: String) : NewznabError(message)

    override fun toString(): String = "${this::class.simpleName}($message)"

    public companion object {
        /** An error code from the Newznab API spec, plus 429, which several implementations use. */
        public fun fromCode(code: Int, description: String): NewznabError = when (code) {
            100, 101, 102 -> Auth(description)
            429, 500 -> ApiLimitReached(description)
            501 -> DownloadLimitReached(description)
            910 -> Disabled(description)
            202, 203 -> Unsupported(description)
            300 -> NotFound(description)
            200, 201 -> BadResponse(description)
            else -> Server(description)
        }
    }
}

/** Every failure the library throws. */
public class NewznabException(public val error: NewznabError, cause: Throwable? = null) : Exception(error.toString(), cause)

/** What an indexer that answered its limit says, whatever its own words were. */
internal const val API_LIMIT_REACHED = "API limit reached"

internal fun noAnswerWithin(timeoutMs: Long) = "No answer within ${timeoutMs / 1000} s"
