package app.lingleaf.core

class HttpResponse(val status: Int, val body: ByteArray) {
    val text: String get() = body.toString(Charsets.UTF_8)
}

/** Minimal HTTP abstraction so network code stays testable without a server. */
fun interface HttpFetcher {
    suspend fun get(url: String): HttpResponse
}

class NetworkException(message: String) : Exception(message)
