package dev.mcrecon.decompiler

import dev.mcrecon.decompiler.DecompilationModel.ArtifactIdentity
import dev.mcrecon.decompiler.DecompilationModel.ExternalLibrary
import dev.mcrecon.decompiler.DecompilationModel.Inputs
import dev.mcrecon.decompiler.DecompilationModel.JavaRuntimeIdentity
import dev.mcrecon.decompiler.DecompilationModel.Output
import dev.mcrecon.decompiler.DecompilationModel.Provenance
import dev.mcrecon.decompiler.DecompilationModel.ToolIdentity
import dev.mcrecon.decompiler.DecompilationModel.VineflowerIdentity
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DecompilationCacheTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `provenance serialization is deterministic`() {
        val raw = temporaryDirectory.resolve("raw")
        writeSource(raw)
        val provenance = provenance(inputs(), raw)
        val cache = DecompilationCache()
        val first = temporaryDirectory.resolve("first.json")
        val second = temporaryDirectory.resolve("second.json")

        cache.write(first, provenance)
        cache.write(second, provenance)

        assertContentEquals(Files.readAllBytes(first), Files.readAllBytes(second))
    }

    @Test
    fun `reuse requires exact relevant inputs schema and intact output`() {
        val raw = temporaryDirectory.resolve("raw")
        writeSource(raw)
        val expected = inputs()
        val cache = DecompilationCache()
        val path = temporaryDirectory.resolve("decompile.json")
        cache.write(path, provenance(expected, raw))

        assertNotNull(cache.loadReusable(path, raw, expected, 2))
        listOf(
            inputs(targetSha = "b".repeat(64)),
            inputs(vineflowerVersion = "other"),
            inputs(options = sortedMapOf("thread-count" to "1")),
            inputs(librarySha = "c".repeat(64)),
            inputs(format = "2"),
        ).forEach { changed -> assertNull(cache.loadReusable(path, raw, changed, 2)) }

        cache.write(path, Provenance(emptyList(), expected, provenance(expected, raw).output(), 999, ToolIdentity("test")))
        assertNull(cache.loadReusable(path, raw, expected, 2))

        cache.write(path, provenance(expected, raw))
        Files.writeString(raw.resolve("pkg/Test.java"), "changed")
        assertNull(cache.loadReusable(path, raw, expected, 2))

        Files.writeString(path, "{}")
        assertNull(cache.loadReusable(path, raw, expected, 2))
    }

    private fun provenance(inputs: Inputs, raw: Path): Provenance {
        val fingerprint = SourceTreeHasher.hash(raw)
        return Provenance(
            emptyList(),
            inputs,
            Output(emptyList(), fingerprint.fileCount, fingerprint.sha256, 2),
            DecompilationModel.SCHEMA_VERSION,
            ToolIdentity("test"),
        )
    }

    private fun inputs(
        targetSha: String = "a".repeat(64),
        vineflowerVersion: String = "1.12.0",
        options: Map<String, String> = sortedMapOf("thread-count" to "4"),
        librarySha: String = "d".repeat(64),
        format: String = "1",
    ): Inputs = Inputs(
        format,
        listOf(
            ExternalLibrary(
                "example:library:1",
                null,
                "standard",
                ArtifactIdentity("2".repeat(40), librarySha, 20),
            ),
        ),
        JavaRuntimeIdentity(25, "java-runtime-test", "test JDK 25"),
        "test-version",
        "client",
        ArtifactIdentity("1".repeat(40), targetSha, 10),
        VineflowerIdentity(vineflowerVersion, options),
    )

    private fun writeSource(raw: Path) {
        Files.createDirectories(raw.resolve("pkg"))
        Files.writeString(raw.resolve("pkg/Test.java"), "class Test {}\n")
    }
}
