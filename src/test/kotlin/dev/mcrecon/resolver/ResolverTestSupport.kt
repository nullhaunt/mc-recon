package dev.mcrecon.resolver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal class TestHttpServer : AutoCloseable {
    private val responses = ConcurrentHashMap<String, TestResponse>()
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val executor = Executors.newFixedThreadPool(2)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/", ::handle)
        this.executor = this@TestHttpServer.executor
        start()
    }

    val baseUri: URI = URI("http://127.0.0.1:${server.address.port}/")

    fun serve(
        path: String,
        body: ByteArray,
        status: Int = 200,
        declaredLength: Long = body.size.toLong(),
        bytesToWrite: Int = body.size,
    ) {
        responses[normalizedPath(path)] = TestResponse(status, body, declaredLength, bytesToWrite)
    }

    fun hits(path: String): Int = hits[normalizedPath(path)]?.get() ?: 0

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
        val response = responses[path] ?: TestResponse(404, ByteArray(0), 0, 0)
        try {
            exchange.sendResponseHeaders(response.status, response.declaredLength)
            if (response.bytesToWrite > 0) {
                exchange.responseBody.write(response.body, 0, response.bytesToWrite.coerceAtMost(response.body.size))
            }
        } catch (_: Exception) {
            // Deliberately truncated responses cause the server stream to reject close.
        } finally {
            exchange.close()
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun normalizedPath(path: String): String = if (path.startsWith('/')) path else "/$path"

    private data class TestResponse(
        val status: Int,
        val body: ByteArray,
        val declaredLength: Long,
        val bytesToWrite: Int,
    )
}

internal fun sha1(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes))
