package dev.mcrecon.resolver

import dev.mcrecon.manifest.ManifestParser
import dev.mcrecon.manifest.rules.Architecture
import dev.mcrecon.manifest.rules.HostPlatform
import dev.mcrecon.manifest.rules.OperatingSystem
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionResolverTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `resolves only applicable libraries and keeps native and classifier roles distinct`() {
        TestHttpServer().use { server ->
            val content = TestContent(server)
            content.serveAll()
            val manifest = ManifestParser().parse(content.manifestJson(), "local-version.json")
            val layout = CacheLayout(temporaryDirectory.resolve("cache"))
            val platform = HostPlatform(OperatingSystem.WINDOWS, Architecture.X86_64, "10.0.26100")
            val resolver = VersionResolver(layout)

            val resolved = resolver.resolve(manifest, platform)

            assertEquals(3, resolved.libraries.size)
            assertEquals(
                setOf("example:common:1", "example:native:1", "example:classified:1:natives-windows"),
                resolved.libraries.map { it.metadata.name }.toSet(),
            )
            assertEquals(2, resolved.classifierAndNativeArtifacts.size)
            assertTrue(resolved.classifierAndNativeArtifacts.any { it.role == LibraryArtifactRole.NATIVE && it.classifier == "natives-windows-64" })
            assertTrue(resolved.classifierAndNativeArtifacts.any { it.role == LibraryArtifactRole.CLASSIFIER && it.classifier == "natives-windows" })
            assertEquals(0, server.hits("linux.jar"))
            assertEquals(0, server.hits("native-32.jar"))
            assertEquals(1, server.hits("native-64.jar"))
            assertEquals(8, resolved.summary.verifiedArtifacts)
            assertEquals(8, resolved.summary.downloadedArtifacts)
            assertEquals("-Dlog4j.configurationFile=\${path}", resolved.loggingConfigurations.getValue("client").metadata.argument)

            val cached = resolver.resolve(manifest, platform)

            assertEquals(8, cached.summary.cachedArtifacts)
            assertEquals(0, cached.summary.downloadedArtifacts)
            assertEquals(1, server.hits("client.jar"))
            assertEquals(1, server.hits("logging.xml"))
        }
    }

    private class TestContent(private val server: TestHttpServer) {
        private val client = "client".toByteArray()
        private val dedicatedServer = "server".toByteArray()
        private val common = "common library".toByteArray()
        private val nativeMain = "native main library".toByteArray()
        private val native32 = "32-bit native".toByteArray()
        private val native64 = "64-bit native".toByteArray()
        private val linux = "linux-only library".toByteArray()
        private val classified = "classified library".toByteArray()
        private val assetIndex = "{\"objects\":{}}".toByteArray()
        private val logging = "logging configuration".toByteArray()

        fun serveAll() {
            mapOf(
                "client.jar" to client,
                "server.jar" to dedicatedServer,
                "common.jar" to common,
                "native-main.jar" to nativeMain,
                "native-32.jar" to native32,
                "native-64.jar" to native64,
                "linux.jar" to linux,
                "classified.jar" to classified,
                "asset-index.json" to assetIndex,
                "logging.xml" to logging,
            ).forEach(server::serve)
        }

        fun manifestJson(): String = """
            {
              "id":"local-test",
              "type":"snapshot",
              "time":"2026-01-01T00:00:00Z",
              "releaseTime":"2026-01-01T00:00:00Z",
              "downloads":{
                "client":${artifact("client.jar", client)},
                "server":${artifact("server.jar", dedicatedServer)}
              },
              "assetIndex":{
                "id":"test-assets",
                "sha1":"${sha1(assetIndex)}",
                "size":${assetIndex.size},
                "totalSize":0,
                "url":"${server.baseUri.resolve("asset-index.json")}"
              },
              "assets":"test-assets",
              "javaVersion":{"component":"java-runtime-test","majorVersion":25},
              "libraries":[
                {
                  "name":"example:common:1",
                  "downloads":{"artifact":${artifact("common.jar", common, "example/common/1/common-1.jar")}}
                },
                {
                  "name":"example:native:1",
                  "downloads":{
                    "artifact":${artifact("native-main.jar", nativeMain, "example/native/1/native-1.jar")},
                    "classifiers":{
                      "natives-windows-32":${artifact("native-32.jar", native32, "example/native/1/native-1-natives-windows-32.jar")},
                      "natives-windows-64":${artifact("native-64.jar", native64, "example/native/1/native-1-natives-windows-64.jar")}
                    }
                  },
                  "natives":{"windows":"natives-windows-${'$'}{arch}"},
                  "rules":[{"action":"allow","os":{"name":"windows"}}]
                },
                {
                  "name":"example:linux:1",
                  "downloads":{"artifact":${artifact("linux.jar", linux, "example/linux/1/linux-1.jar")}},
                  "rules":[{"action":"allow","os":{"name":"linux"}}]
                },
                {
                  "name":"example:classified:1:natives-windows",
                  "downloads":{"artifact":${artifact("classified.jar", classified, "example/classified/1/classified-1-natives-windows.jar")}},
                  "rules":[{"action":"allow","os":{"name":"windows"}}]
                }
              ],
              "arguments":{"jvm":[],"game":[]},
              "logging":{
                "client":{
                  "argument":"-Dlog4j.configurationFile=${'$'}{path}",
                  "file":${artifact("logging.xml", logging, id = "test-log.xml")},
                  "type":"log4j2-xml"
                }
              },
              "mainClass":"example.Main"
            }
        """.trimIndent()

        private fun artifact(endpoint: String, bytes: ByteArray, path: String? = null, id: String? = null): String =
            buildString {
                append("{\"sha1\":\"").append(sha1(bytes)).append("\",")
                append("\"size\":").append(bytes.size).append(',')
                append("\"url\":\"").append(server.baseUri.resolve(endpoint)).append('"')
                if (path != null) append(",\"path\":\"").append(path).append('"')
                if (id != null) append(",\"id\":\"").append(id).append('"')
                append('}')
            }
    }
}
