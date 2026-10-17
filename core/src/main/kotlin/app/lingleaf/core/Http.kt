package app.lingleaf.core

class HttpResponse(val status: Int, val body: ByteArray) {
    val text: String get() = body.toString(Charsets.UTF_8)
}

/** Minimal HTTP abstraction so network code stays testable without a server. */
fun interface HttpFetcher {
    suspend fun get(url: String): HttpResponse

    /** Like [get], reporting progress (0…1) while the body arrives. */
    suspend fun get(
        url: String,
        progress: ((Double) -> Unit)?,
    ): HttpResponse = get(url).also { progress?.invoke(1.0) }
}

class NetworkException(
    message: String,
    val kind: Kind = Kind.OTHER,
) : Exception(message) {
    enum class Kind {
        /** No internet connection. */
        OFFLINE,
        TIMED_OUT,
        OTHER,
    }
}

/**
 * The only hosts the app talks to (see README). A request or redirect anywhere else is refused,
 * so even a compromised host can't send the app to a server of the attacker's choosing.
 */
object Hosts {
    val ALLOWED = setOf("gutendex.com", "www.gutenberg.org", "api.dictionaryapi.dev")

    /** Redirects followed at most, each one HTTPS and to an allowed host. */
    const val MAX_REDIRECTS = 5

    /** Whether [url] is HTTPS to one of [allowed] (exact host, default port). */
    fun isAllowed(
        url: String?,
        allowed: Set<String> = ALLOWED,
    ): Boolean {
        val uri = url?.let { runCatching { java.net.URI(it) }.getOrNull() } ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && (uri.port == -1 || uri.port == 443) && uri.userInfo == null && host in allowed
    }
}

/**
 * Checks JSON nesting without parsing it: a deeply nested answer (`[[[[…`) can't
 * exhaust the parser's stack. Real answers nest fewer than 10 levels.
 */
object JsonGuard {
    const val MAX_DEPTH = 64

    fun isShallow(
        text: String,
        maxDepth: Int = MAX_DEPTH,
    ): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[', '{' -> if (++depth > maxDepth) return false
                ']', '}' -> depth--
            }
        }
        return true
    }
}
