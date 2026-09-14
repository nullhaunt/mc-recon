package dev.mcrecon.decompiler

import dev.mcrecon.resolvedVersion
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.runtime.JavaRuntime
import dev.mcrecon.runtime.JavaRuntimeSelector
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DecompilationServiceTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `forced runs publish complete clean output deterministically and normal run reuses`() {
        val fixture = fixture()
        var calls = 0
        val engine = RawDecompilerEngine { invocation ->
            calls++
            val file = invocation.outputRoot.resolve("pkg/Test.java")
            Files.createDirectories(file.parent)
            Files.writeString(file, "package pkg;\nclass Test {}\n")
            DecompilerExecution(
                listOf(DecompilationModel.Diagnostic(null, null, "synthetic warning", "WARN")),
                emptyList(),
            )
        }
        val service = fixture.service(engine)

        val first = service.decompile(fixture.manifest, fixture.platform, DecompilationSide.CLIENT, force = true, javaExecutable = fixture.java)
        val firstProvenance = Files.readAllBytes(fixture.layout.decompilationProvenance(fixture.manifest.id, "client"))
        Files.writeString(first.path.resolve("stale.java"), "stale")
        val second = service.decompile(fixture.manifest, fixture.platform, DecompilationSide.CLIENT, force = true, javaExecutable = fixture.java)
        val secondProvenance = Files.readAllBytes(fixture.layout.decompilationProvenance(fixture.manifest.id, "client"))
        val reused = service.decompile(fixture.manifest, fixture.platform, DecompilationSide.CLIENT, javaExecutable = fixture.java)

        assertEquals(first.provenance.output().sourceTreeSha256(), second.provenance.output().sourceTreeSha256())
        assertContentEquals(firstProvenance, secondProvenance)
        assertEquals("synthetic warning", second.provenance.diagnostics().single().message())
        assertFalse(Files.exists(second.path.resolve("stale.java")))
        assertTrue(reused.reused)
        assertEquals(2, calls)
    }

    @Test
    fun `engine failure cannot replace a previous valid tree`() {
        val fixture = fixture()
        var calls = 0
        val engine = RawDecompilerEngine { invocation ->
            calls++
            val file = invocation.outputRoot.resolve("pkg/Test.java")
            Files.createDirectories(file.parent)
            Files.writeString(file, if (calls == 1) "known-good\n" else "partial\n")
            if (calls == 2) throw DecompilationException("synthetic failure")
            DecompilerExecution(emptyList(), emptyList())
        }
        val service = fixture.service(engine)
        val first = service.decompile(fixture.manifest, fixture.platform, DecompilationSide.CLIENT, force = true, javaExecutable = fixture.java)
        val provenanceBefore = Files.readAllBytes(fixture.layout.decompilationProvenance(fixture.manifest.id, "client"))

        assertFailsWith<DecompilationException> {
            service.decompile(fixture.manifest, fixture.platform, DecompilationSide.CLIENT, force = true, javaExecutable = fixture.java)
        }

        assertEquals("known-good\n", Files.readString(first.path.resolve("pkg/Test.java")))
        assertContentEquals(provenanceBefore, Files.readAllBytes(fixture.layout.decompilationProvenance(fixture.manifest.id, "client")))
    }

    @Test
    fun `reported errors prevent publication`() {
        val fixture = fixture()
        val engine = RawDecompilerEngine { invocation ->
            Files.writeString(invocation.outputRoot.resolve("Test.java"), "partial")
            DecompilerExecution(
                listOf(DecompilationModel.Diagnostic(null, null, "Class Test couldn't be fully decompiled.", "ERROR")),
                listOf("Test"),
            )
        }

        assertFailsWith<DecompilationException> {
            fixture.service(engine).decompile(
                fixture.manifest,
                fixture.platform,
                DecompilationSide.CLIENT,
                force = true,
                javaExecutable = fixture.java,
            )
        }
        assertFalse(Files.exists(fixture.layout.rawSourceDirectory(fixture.manifest.id, "client")))
    }

    private fun fixture(): ServiceFixture {
        val cacheRoot = temporaryDirectory.resolve("cache")
        val layout = CacheLayout(cacheRoot)
        val id = "decompile-test"
        val client = layout.versionArtifact(id, "client.jar")
        Files.createDirectories(client.parent)
        JarOutputStream(Files.newOutputStream(client)).use { output ->
            output.putNextEntry(JarEntry("pkg/Test.class").apply { time = 0 })
            output.write(byteArrayOf(1, 2, 3))
            output.closeEntry()
        }
        val base = resolvedVersion(temporaryDirectory.resolve("manifest-seed")).manifest
        val clientBytes = Files.readAllBytes(client)
        val manifest = base.copy(
            id = id,
            downloads = base.downloads + (
                "client" to dev.mcrecon.manifest.Download(
                    sha1(clientBytes),
                    clientBytes.size.toLong(),
                    URI("https://example.invalid/client.jar"),
                )
            ),
            libraries = emptyList(),
        )
        val javaHome = temporaryDirectory.resolve("test-jdk")
        val java = javaHome.resolve("bin/java")
        Files.createDirectories(java.parent)
        Files.createDirectories(javaHome.resolve("lib"))
        Files.write(java, byteArrayOf(0))
        Files.write(javaHome.resolve("lib/jrt-fs.jar"), byteArrayOf(0))
        return ServiceFixture(layout, manifest, basePlatform(), java)
    }

    private fun basePlatform() = resolvedVersion(temporaryDirectory.resolve("platform-seed")).platform

    private fun sha1(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes))

    private data class ServiceFixture(
        val layout: CacheLayout,
        val manifest: dev.mcrecon.manifest.VersionManifest,
        val platform: dev.mcrecon.manifest.rules.HostPlatform,
        val java: Path,
    ) {
        fun service(engine: RawDecompilerEngine): DecompilationService = DecompilationService(
            layout,
            javaSelector = JavaRuntimeSelector { JavaRuntime(it, 25, "test JDK 25") },
            engine = engine,
        )
    }
}
