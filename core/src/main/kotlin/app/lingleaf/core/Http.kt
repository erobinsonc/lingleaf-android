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
