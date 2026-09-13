package dev.mcrecon.resolver

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifiedArtifactStoreTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `downloads verifies and publishes a missing artifact`() {
        val bytes = "verified artifact".toByteArray()
        TestHttpServer().use { server ->
            server.serve("artifact.bin", bytes)
            val request = request(server, bytes)

            val resolved = store().resolve(request)

            assertEquals(ArtifactOrigin.DOWNLOAD, resolved.origin)
            assertContentEquals(bytes, Files.readAllBytes(request.destination))
            assertEquals(1, server.hits("artifact.bin"))
        }
    }

    @Test
    fun `a valid cache entry is reused without network access`() {
        val bytes = "already cached".toByteArray()
        TestHttpServer().use { server ->
            server.serve("artifact.bin", bytes)
            val request = request(server, bytes)
            store().resolve(request)

            val cached = store().resolve(request)

            assertEquals(ArtifactOrigin.CACHE, cached.origin)
            assertEquals(1, server.hits("artifact.bin"))
        }
    }

    @Test
    fun `a corrupt cache entry is reacquired and replaced`() {
        val bytes = "replacement".toByteArray()
        TestHttpServer().use { server ->
            server.serve("artifact.bin", bytes)
            val request = request(server, bytes)
            Files.createDirectories(request.destination.parent)
            Files.writeString(request.destination, "xxxxxxxxxxx") // Same size, wrong SHA-1.

            val resolved = store().resolve(request)

            assertEquals(ArtifactOrigin.DOWNLOAD, resolved.origin)
            assertTrue(resolved.replacedCorruptEntry)
            assertContentEquals(bytes, Files.readAllBytes(request.destination))
            assertEquals(1, server.hits("artifact.bin"))
        }
    }

    @Test
    fun `incorrect downloaded SHA-1 is rejected without publication`() {
        val bytes = "server data".toByteArray()
        TestHttpServer().use { server ->
            server.serve("artifact.bin", bytes)
            val request = request(server, bytes).copy(expectedSha1 = sha1("different data".toByteArray()))

            val failure = assertFailsWith<IntegrityException> { store().resolve(request) }

            assertTrue(failure.message.orEmpty().contains("expected SHA-1"))
            assertFalse(Files.exists(request.destination))
            assertNoPartialFiles()
        }
    }

    @Test
    fun `incorrect downloaded size is rejected without publication`() {
        val bytes = "server data".toByteArray()
        TestHttpServer().use { server ->
            server.serve("artifact.bin", bytes)
            val request = request(server, bytes).copy(expectedSize = bytes.size + 1L)

            val failure = assertFailsWith<IntegrityException> { store().resolve(request) }

            assertTrue(failure.message.orEmpty().contains("size ${bytes.size + 1L}"))
            assertFalse(Files.exists(request.destination))
            assertNoPartialFiles()
        }
    }

    @Test
    fun `a truncated response leaves no final or partial artifact`() {
        val bytes = "short".toByteArray()
        TestHttpServer().use { server ->
            server.serve("artifact.bin", bytes, declaredLength = 100, bytesToWrite = bytes.size)
            val request = request(server, bytes)

            assertFailsWith<ResolutionException> { store(noDelayFetcher()).resolve(request) }

            assertFalse(Files.exists(request.destination))
            assertNoPartialFiles()
            // The JDK client may transparently retry a prematurely closed idempotent request once.
            assertTrue(server.hits("artifact.bin") in 2..4)
        }
    }

    @Test
    fun `permanent HTTP errors are not retried`() {
        val bytes = "not returned".toByteArray()
        TestHttpServer().use { server ->
            server.serve("artifact.bin", ByteArray(0), status = 404)
            val request = request(server, bytes)

            val failure = assertFailsWith<ResolutionException> { store(noDelayFetcher()).resolve(request) }

            assertTrue(failure.message.orEmpty().contains("HTTP 404"))
            assertEquals(1, server.hits("artifact.bin"))
            assertFalse(Files.exists(request.destination))
        }
    }

    @Test
    fun `transient HTTP errors use bounded retries`() {
        val bytes = "not returned".toByteArray()
        TestHttpServer().use { server ->
            server.serve("artifact.bin", ByteArray(0), status = 503)
            val request = request(server, bytes)

            val failure = assertFailsWith<ResolutionException> { store(noDelayFetcher()).resolve(request) }

            assertTrue(failure.message.orEmpty().contains("HTTP 503"))
            assertEquals(2, server.hits("artifact.bin"))
            assertFalse(Files.exists(request.destination))
        }
    }

    @Test
    fun `failed reacquisition removes a known corrupt final entry`() {
        val bytes = "expected bytes".toByteArray()
        TestHttpServer().use { server ->
            server.serve("artifact.bin", ByteArray(0), status = 503)
            val request = request(server, bytes)
            Files.createDirectories(request.destination.parent)
            Files.writeString(request.destination, "xxxxxxxxxxxxxx") // Same size, wrong SHA-1.

            assertFailsWith<ResolutionException> { store(noDelayFetcher()).resolve(request) }

            assertFalse(Files.exists(request.destination))
            assertNoPartialFiles()
        }
    }

    private fun request(server: TestHttpServer, bytes: ByteArray): ArtifactRequest = ArtifactRequest(
        name = "test artifact",
        uri = server.baseUri.resolve("artifact.bin"),
        expectedSha1 = sha1(bytes),
        expectedSize = bytes.size.toLong(),
        destination = temporaryDirectory.resolve("cache/libraries/example/artifact.bin").toAbsolutePath(),
    )

    private fun store(fetcher: JdkArtifactFetcher = JdkArtifactFetcher()): VerifiedArtifactStore =
        VerifiedArtifactStore(temporaryDirectory.resolve("cache"), fetcher)

    private fun noDelayFetcher(): JdkArtifactFetcher = JdkArtifactFetcher(
        retryPolicy = RetryPolicy(maxAttempts = 2, initialDelay = Duration.ZERO, maximumDelay = Duration.ZERO),
        sleeper = Sleeper {},
    )

    private fun assertNoPartialFiles() {
        val partialDirectory = temporaryDirectory.resolve("cache/downloads/partial")
        if (Files.exists(partialDirectory)) {
            Files.list(partialDirectory).use { files -> assertFalse(files.findAny().isPresent) }
        }
    }
}
