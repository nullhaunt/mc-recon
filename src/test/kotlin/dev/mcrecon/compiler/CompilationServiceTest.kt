package dev.mcrecon.compiler

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mcrecon.decompiler.DecompilationSide
import dev.mcrecon.manifest.AssetIndex
import dev.mcrecon.manifest.Download
import dev.mcrecon.manifest.JavaVersion
import dev.mcrecon.manifest.VersionManifest
import dev.mcrecon.manifest.VersionType
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.runtime.JavaRuntime
import dev.mcrecon.runtime.JavaRuntimeProbe
import dev.mcrecon.runtime.JavaRuntimeSelector
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompilationServiceTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `compiled publication is deterministic transactional and keyed by compiler identity`() {
        val layout = CacheLayout(temporaryDirectory.resolve("cache"))
        val manifest = manifest(layout)
        val source = layout.reconstructedSourceDirectory(manifest.id, "client")
        Files.createDirectories(source.resolve("sample"))
        Files.writeString(source.resolve("sample/Example.java"), "package sample; public record Example(int value) {}\n")
        val serviceA = service(layout, "compiler-a")

        val first = serviceA.compile(manifest, DecompilationSide.CLIENT, force = true)
        assertEquals("SUCCESS", first.report.output().result())
        val firstHash = first.report.output().classTreeSha256()
        val firstReport = Files.readAllBytes(first.reportPath)
        assertTrue(serviceA.compile(manifest, DecompilationSide.CLIENT).reused)

        val stale = first.classesPath!!.resolve("stale.class")
        Files.write(stale, byteArrayOf(1, 2, 3))
        val second = serviceA.compile(manifest, DecompilationSide.CLIENT, force = true)
        assertFalse(Files.exists(stale))
        assertEquals(firstHash, second.report.output().classTreeSha256())
        assertTrue(firstReport.contentEquals(Files.readAllBytes(second.reportPath)))

        val changedCompiler = service(layout, "compiler-b").compile(manifest, DecompilationSide.CLIENT)
        assertFalse(changedCompiler.reused)
        assertEquals(firstHash, changedCompiler.report.output().classTreeSha256())

        val publishedBeforeFailure = DeterministicTreeHasher.hash(first.classesPath, CompilationService.CLASS_TREE_DOMAIN)
        Files.writeString(source.resolve("sample/Example.java"), "package sample; public class Example { Missing value; }\n")
        val failed = serviceA.compile(manifest, DecompilationSide.CLIENT, force = true)
        assertEquals("FAILED", failed.report.output().result())
        assertTrue(failed.report.output().errors() > 0)
        assertEquals(publishedBeforeFailure, DeterministicTreeHasher.hash(first.classesPath, CompilationService.CLASS_TREE_DOMAIN))
    }

    private fun service(layout: CacheLayout, identity: String): CompilationService {
        val java = currentJava()
        return CompilationService(
            layout,
            javaSelector = JavaRuntimeSelector(JavaRuntimeProbe { JavaRuntime(java, 25, identity) }),
        )
    }

    private fun manifest(layout: CacheLayout): VersionManifest {
        val client = "client".toByteArray()
        val clientPath = layout.versionArtifact("test-version", "client.jar")
        Files.createDirectories(clientPath.parent)
        Files.write(clientPath, client)
        val download = Download(sha1(client), client.size.toLong(), URI("https://example.invalid/client.jar"))
        return VersionManifest(
            id = "test-version",
            type = VersionType.SNAPSHOT,
            time = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            releaseTime = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            downloads = mapOf("client" to download),
            assetIndex = AssetIndex("test", "0".repeat(40), 0, 0, temporaryDirectory.resolve("unused-assets").toUri()),
            assets = "test",
            javaVersion = JavaVersion("java-runtime-test", 25),
            libraries = emptyList(),
            arguments = mapOf("jvm" to emptyList(), "game" to emptyList()),
            logging = emptyMap(),
            mainClass = "sample.Example",
            minimumLauncherVersion = null,
            complianceLevel = null,
            inheritsFrom = null,
            jar = null,
            raw = ObjectMapper().createObjectNode(),
        )
    }

    private fun currentJava(): Path {
        val name = if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", name)
    }

    private fun sha1(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes))
}
