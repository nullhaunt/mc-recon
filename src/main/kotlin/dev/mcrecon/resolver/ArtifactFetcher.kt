package dev.mcrecon.resolver

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelay: Duration = Duration.ofMillis(250),
    val maximumDelay: Duration = Duration.ofSeconds(2),
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(!initialDelay.isNegative) { "initialDelay must not be negative" }
        require(!maximumDelay.isNegative) { "maximumDelay must not be negative" }
    }
}

fun interface Sleeper {
    fun sleep(duration: Duration)
}

class JdkArtifactFetcher(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val requestTimeout: Duration = Duration.ofMinutes(2),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val sleeper: Sleeper = Sleeper { duration -> Thread.sleep(duration.toMillis()) },
) {
    fun fetch(name: String, uri: URI, destination: Path) {
        var lastFailure: IOException? = null
        for (attempt in 1..retryPolicy.maxAttempts) {
            try {
                val request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(requestTimeout)
                    .header("User-Agent", "mc-recon/0.1")
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                response.body().use { body ->
                    if (response.statusCode() in 200..299) {
                        Files.newOutputStream(
                            destination,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                        ).use(body::transferTo)
                        return
                    }
                }

                if (!isTransient(response.statusCode()) || attempt == retryPolicy.maxAttempts) {
                    throw ResolutionException("HTTP ${response.statusCode()} while downloading '$name' from $uri")
                }
                sleep(backoff(attempt, response.headers().firstValue("Retry-After").orElse(null)))
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ResolutionException("Download interrupted for '$name' from $uri", exception)
            } catch (exception: IOException) {
                lastFailure = exception
                if (attempt == retryPolicy.maxAttempts) break
                sleep(backoff(attempt, null))
            }
        }
        throw ResolutionException("Network failure while downloading '$name' from $uri: ${lastFailure?.message}", lastFailure)
    }

    private fun sleep(duration: Duration) {
        try {
            sleeper.sleep(duration)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ResolutionException("Download retry interrupted", exception)
        }
    }

    private fun backoff(attempt: Int, retryAfter: String?): Duration {
        val serverDelay = retryAfter?.toLongOrNull()?.let(Duration::ofSeconds)
        val exponential = initialDelayMultiplied(attempt)
        val requested = serverDelay ?: exponential
        return if (requested > retryPolicy.maximumDelay) retryPolicy.maximumDelay else requested
    }

    private fun initialDelayMultiplied(attempt: Int): Duration {
        var delay = retryPolicy.initialDelay
        repeat((attempt - 1).coerceAtLeast(0)) {
            delay = delay.multipliedBy(2)
        }
        return delay
    }

    private fun isTransient(status: Int): Boolean = status == 429 || status in TRANSIENT_SERVER_ERRORS

    private companion object {
        val TRANSIENT_SERVER_ERRORS = setOf(500, 502, 503, 504)
    }
}
