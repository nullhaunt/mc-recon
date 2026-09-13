package dev.mcrecon.cli

import dev.mcrecon.resolver.TestHttpServer
import dev.mcrecon.resolver.sha1
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McReconCliTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `nonexistent manifests fail without a stack trace`() {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exitCode = McReconCli().run(
            arrayOf("inspect", "does-not-exist.json"),
            PrintWriter(stdout),
            PrintWriter(stderr),
        )

        assertEquals(2, exitCode)
        assertContains(stderr.toString(), "Manifest does not exist")
        assertEquals("", stdout.toString())
        assert(!stderr.toString().contains("\tat "))
    }

    @Test
    fun `resolve command uses a custom cache with deterministic local downloads`() {
        val client = "client".toByteArray()
        val serverJar = "server".toByteArray()
        val assetIndex = "{\"objects\":{}}".toByteArray()
        TestHttpServer().use { server ->
            server.serve("client.jar", client)
            server.serve("server.jar", serverJar)
            server.serve("assets.json", assetIndex)
            val manifestPath = temporaryDirectory.resolve("version.json")
            val cachePath = temporaryDirectory.resolve("custom-cache")
            Files.writeString(
                manifestPath,
                """
                    {
                      "id":"cli-test",
                      "type":"snapshot",
                      "time":"2026-01-01T00:00:00Z",
                      "releaseTime":"2026-01-01T00:00:00Z",
                      "downloads":{
                        "client":${download(server.baseUri.resolve("client.jar").toString(), client)},
                        "server":${download(server.baseUri.resolve("server.jar").toString(), serverJar)}
                      },
                      "assetIndex":{
                        "id":"cli-assets","sha1":"${sha1(assetIndex)}","size":${assetIndex.size},"totalSize":0,
                        "url":"${server.baseUri.resolve("assets.json")}"
                      },
                      "javaVersion":{"component":"java-runtime-test","majorVersion":25},
                      "libraries":[],
                      "arguments":{"jvm":[],"game":[]},
                      "mainClass":"example.Main"
                    }
                """.trimIndent(),
            )
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()

            val exitCode = McReconCli().run(
                arrayOf("resolve", manifestPath.toString(), "--cache-dir", cachePath.toString()),
                PrintWriter(stdout),
                PrintWriter(stderr),
            )

            assertEquals(0, exitCode)
            assertEquals("", stderr.toString())
            assertContains(stdout.toString(), "Resolved Minecraft cli-test")
            assertContains(stdout.toString(), "Verified artifacts:     3")
            assertTrue(Files.exists(cachePath.resolve("versions/cli-test/client.jar")))
            assertTrue(Files.exists(cachePath.resolve("assets/indexes/cli-assets.json")))
        }
    }

    private fun download(url: String, bytes: ByteArray): String =
        "{\"sha1\":\"${sha1(bytes)}\",\"size\":${bytes.size},\"url\":\"$url\"}"
}
