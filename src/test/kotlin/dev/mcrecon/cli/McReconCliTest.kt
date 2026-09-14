package dev.mcrecon.cli

import dev.mcrecon.classfile.ClassfileTestFixtures
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McReconCliTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `decompile is cache only and rejects server bundlers clearly`() {
        fun invoke(vararg arguments: String): Pair<Int, String> {
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val exit = McReconCli().run(arrayOf(*arguments), PrintWriter(stdout), PrintWriter(stderr))
            return exit to stderr.toString()
        }

        val missing = invoke(
            "decompile", "fixtures/26.3-rc-2.json", "--side", "client",
            "--cache-dir", temporaryDirectory.resolve("empty-cache").toString(),
        )
        val server = invoke("decompile", "fixtures/26.3-rc-2.json", "--side", "server")

        assertEquals(2, missing.first)
        assertContains(missing.second, "Run 'mc-recon resolve <manifest>' first")
        assertEquals(2, server.first)
        assertContains(server.second, "published server artifact is a bundler")
    }

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

    @Test
    fun `client launch command works without session flags and username can be supplied alone`() {
        val cachePath = temporaryDirectory.resolve("launch-cache")
        val manifestPath = writeCachedLaunchFixture(cachePath)

        fun launch(vararg options: String): Pair<Int, Pair<String, String>> {
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val exit = McReconCli().run(
                arrayOf("launch-command", manifestPath.toString(), "client", "--cache-dir", cachePath.toString(), *options),
                PrintWriter(stdout),
                PrintWriter(stderr),
            )
            return exit to (stdout.toString() to stderr.toString())
        }

        val defaultLaunch = launch()
        val namedLaunch = launch("--username", "Dinnerbone")

        assertEquals(0, defaultLaunch.first)
        assertEquals("", defaultLaunch.second.second)
        assertContains(defaultLaunch.second.first, "mc-recon-player")
        assertContains(defaultLaunch.second.first, "<redacted>")
        assertFalse(defaultLaunch.second.first.contains("] --demo"))
        assertEquals(0, namedLaunch.first)
        assertEquals("", namedLaunch.second.second)
        assertContains(namedLaunch.second.first, "Dinnerbone")
        assertFalse(namedLaunch.second.first.contains("client launch requires"))
    }

    @Test
    fun `index and binary diff commands are cache only and support structured json`() {
        val cachePath = temporaryDirectory.resolve("binary-cache")
        val classBytes = ClassfileTestFixtures.compile(
            temporaryDirectory, "CliIndexSample", "public class CliIndexSample { int value() { return 1; } }",
        )
        val clientJar = ClassfileTestFixtures.writeJar(
            cachePath.resolve("versions/binary-cli/client.jar"),
            "CliIndexSample.class" to classBytes,
            "data.json" to "{}".toByteArray(),
        )
        val serverBytes = "server".toByteArray()
        val assetIndex = "{\"objects\":{}}".toByteArray()
        val manifestPath = temporaryDirectory.resolve("binary-cli.json")
        Files.writeString(
            manifestPath,
            """
                {
                  "id":"binary-cli",
                  "type":"snapshot",
                  "time":"2026-01-01T00:00:00Z",
                  "releaseTime":"2026-01-01T00:00:00Z",
                  "downloads":{
                    "client":${download("https://example.invalid/client.jar", Files.readAllBytes(clientJar))},
                    "server":${download("https://example.invalid/server.jar", serverBytes)}
                  },
                  "assetIndex":{
                    "id":"binary-assets","sha1":"${sha1(assetIndex)}","size":${assetIndex.size},"totalSize":0,
                    "url":"https://example.invalid/assets.json"
                  },
                  "javaVersion":{"component":"java-runtime-test","majorVersion":25},
                  "libraries":[],
                  "arguments":{"jvm":[],"game":[]},
                  "mainClass":"example.Main"
                }
            """.trimIndent(),
        )

        fun invoke(vararg arguments: String): Triple<Int, String, String> {
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val exit = McReconCli().run(arrayOf(*arguments), PrintWriter(stdout), PrintWriter(stderr))
            return Triple(exit, stdout.toString(), stderr.toString())
        }

        val indexed = invoke("index", manifestPath.toString(), "--side", "client", "--cache-dir", cachePath.toString())
        assertEquals(0, indexed.first)
        assertEquals("", indexed.third)
        assertContains(indexed.second, "classes:       1")
        assertContains(indexed.second, "resources:     1")
        assertTrue(Files.exists(cachePath.resolve("indexes/binary-cli/client.json.gz")))

        val selfDiff = invoke(
            "diff-bin", manifestPath.toString(), manifestPath.toString(), "--side", "client",
            "--cache-dir", cachePath.toString(),
        )
        assertEquals(0, selfDiff.first)
        assertContains(selfDiff.second, "changed:     0")

        val detailedSelfDiff = invoke(
            "diff-bin", manifestPath.toString(), manifestPath.toString(), "--side", "client",
            "--cache-dir", cachePath.toString(), "--details",
        )
        assertEquals(0, detailedSelfDiff.first)
        assertContains(detailedSelfDiff.second, "Method implementation details")
        assertContains(detailedSelfDiff.second, "  none")

        val inspectedMethod = invoke(
            "inspect-method", manifestPath.toString(), "--side", "client",
            "--class", "CliIndexSample", "--method", "value", "--descriptor", "()I",
            "--cache-dir", cachePath.toString(),
        )
        assertEquals(0, inspectedMethod.first)
        assertEquals("", inspectedMethod.third)
        assertContains(inspectedMethod.second, "CliIndexSample.value()I")
        assertContains(inspectedMethod.second, "constant INT 1")

        val jsonDiff = invoke(
            "diff-bin", manifestPath.toString(), manifestPath.toString(), "--side", "client",
            "--cache-dir", cachePath.toString(), "--format", "json",
        )
        assertEquals(0, jsonDiff.first)
        assertContains(jsonDiff.second, "\"archiveEntries\"")
        assertContains(jsonDiff.second, "\"resources\"")
    }

    private fun writeCachedLaunchFixture(cachePath: Path): Path {
        val client = "client".toByteArray()
        val server = "server".toByteArray()
        val assetIndex = "{\"objects\":{}}".toByteArray()
        Files.createDirectories(cachePath.resolve("versions/launch-test"))
        Files.createDirectories(cachePath.resolve("assets/indexes"))
        Files.write(cachePath.resolve("versions/launch-test/client.jar"), client)
        Files.write(cachePath.resolve("versions/launch-test/server.jar"), server)
        Files.write(cachePath.resolve("assets/indexes/launch-assets.json"), assetIndex)
        val manifestPath = temporaryDirectory.resolve("launch-version.json")
        Files.writeString(
            manifestPath,
            """
                {
                  "id":"launch-test",
                  "type":"snapshot",
                  "time":"2026-01-01T00:00:00Z",
                  "releaseTime":"2026-01-01T00:00:00Z",
                  "downloads":{
                    "client":${download("https://example.invalid/client.jar", client)},
                    "server":${download("https://example.invalid/server.jar", server)}
                  },
                  "assetIndex":{
                    "id":"launch-assets","sha1":"${sha1(assetIndex)}","size":${assetIndex.size},"totalSize":0,
                    "url":"https://example.invalid/assets.json"
                  },
                  "assets":"launch-assets",
                  "javaVersion":{"component":"java-runtime-test","majorVersion":25},
                  "libraries":[],
                  "arguments":{
                    "jvm":[],
                    "game":[
                      "--username","${'$'}{auth_player_name}",
                      "--uuid","${'$'}{auth_uuid}",
                      "--accessToken","${'$'}{auth_access_token}"
                    ]
                  },
                  "mainClass":"example.Main"
                }
            """.trimIndent(),
        )
        return manifestPath
    }

    private fun download(url: String, bytes: ByteArray): String =
        "{\"sha1\":\"${sha1(bytes)}\",\"size\":${bytes.size},\"url\":\"$url\"}"
}
