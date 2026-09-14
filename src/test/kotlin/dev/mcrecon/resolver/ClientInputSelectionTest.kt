package dev.mcrecon.resolver

import dev.mcrecon.manifest.Download
import dev.mcrecon.manifest.Library
import dev.mcrecon.manifest.LibraryDownloads
import dev.mcrecon.manifest.ManifestParser
import dev.mcrecon.manifest.rules.Architecture
import dev.mcrecon.manifest.rules.HostPlatform
import dev.mcrecon.manifest.rules.OperatingSystem
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ClientInputSelectionTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `client inputs preserve exact manifest classpath order and exclude legacy natives`() {
        val layout = CacheLayout(temporaryDirectory.resolve("cache"))
        val manifest = ManifestParser().read(Path.of("fixtures/26.3-rc-2.json"))
        val platform = HostPlatform(OperatingSystem.WINDOWS, Architecture.X86_64, "10.0")
        val clientBytes = byteArrayOf(1)
        Files.createDirectories(layout.versionArtifact(manifest.id, "client.jar").parent)
        Files.write(layout.versionArtifact(manifest.id, "client.jar"), clientBytes)

        val standard = bytes("standard")
        val nativeMain = bytes("native-main")
        val nativeClassifier = bytes("native-classifier")
        val primaryClassifier = bytes("primary-classifier")
        val libraries = listOf(
            library("example:standard:1", "example/standard/1/standard.jar", standard),
            Library(
                "example:native:1",
                LibraryDownloads(
                    download("example/native/1/native.jar", nativeMain),
                    mapOf("natives-windows-64" to download("example/native/1/native-natives-windows-64.jar", nativeClassifier)),
                ),
                null,
                mapOf("windows" to "natives-windows-\${arch}"),
                null,
                null,
            ),
            library("example:classified:1:context", "example/classified/1/classified-context.jar", primaryClassifier),
        )
        val testManifest = manifest.copy(
            id = "input-selection",
            downloads = manifest.downloads + (
                "client" to Download(sha1(clientBytes), clientBytes.size.toLong(), URI("https://example.invalid/client.jar"))
            ),
            libraries = libraries,
        )
        Files.createDirectories(layout.versionArtifact(testManifest.id, "client.jar").parent)
        Files.write(layout.versionArtifact(testManifest.id, "client.jar"), clientBytes)
        listOf(
            "example/standard/1/standard.jar" to standard,
            "example/native/1/native.jar" to nativeMain,
            "example/native/1/native-natives-windows-64.jar" to nativeClassifier,
            "example/classified/1/classified-context.jar" to primaryClassifier,
        ).forEach { (path, content) ->
            val destination = layout.libraryArtifact(path)
            Files.createDirectories(destination.parent)
            Files.write(destination, content)
        }

        val inputs = VersionResolver(layout).resolveClientInputsCached(testManifest, platform)

        assertEquals(
            listOf(
                "example/standard/1/standard.jar",
                "example/native/1/native.jar",
                "example/classified/1/classified-context.jar",
            ).map(layout::libraryArtifact),
            inputs.classpathLibraries.map { it.file.path },
        )
        assertEquals(listOf(LibraryArtifactRole.STANDARD, LibraryArtifactRole.STANDARD, LibraryArtifactRole.CLASSIFIER), inputs.classpathLibraries.map { it.role })
    }

    private fun library(name: String, path: String, bytes: ByteArray) = Library(
        name,
        LibraryDownloads(download(path, bytes), emptyMap()),
        null,
        emptyMap(),
        null,
        null,
    )

    private fun download(path: String, bytes: ByteArray) = Download(
        sha1(bytes),
        bytes.size.toLong(),
        URI("https://example.invalid/$path"),
        path,
    )

    private fun bytes(value: String) = value.toByteArray()
}
