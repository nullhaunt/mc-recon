package dev.mcrecon.reconstruction

import dev.mcrecon.classfile.JarIndexStore
import dev.mcrecon.decompiler.DecompilationModel
import dev.mcrecon.decompiler.DecompilationSide
import dev.mcrecon.decompiler.SourceTreeHasher
import dev.mcrecon.manifest.AssetIndex
import dev.mcrecon.manifest.Download
import dev.mcrecon.manifest.JavaVersion
import dev.mcrecon.manifest.VersionManifest
import dev.mcrecon.manifest.VersionType
import dev.mcrecon.resolver.CacheLayout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconstructionServiceTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `reconstruction is deterministic removes stale output preserves raw source and reuses exact inputs`() {
        val fixture = fixture("class Test { int value = 1; }\n")
        val rawBefore = SourceTreeHasher.hash(fixture.raw)
        val service = service(fixture.layout, rules = listOf(LiteralRule))

        val first = service.reconstruct(fixture.manifest, DecompilationSide.CLIENT, force = true)
        val firstJson = Files.readAllBytes(first.provenancePath)
        Files.writeString(first.path.resolve("stale.java"), "class Stale {}\n")
        val second = service.reconstruct(fixture.manifest, DecompilationSide.CLIENT, force = true)

        assertFalse(Files.exists(second.path.resolve("stale.java")))
        assertEquals(first.provenance.output(), second.provenance.output())
        assertTrue(firstJson.contentEquals(Files.readAllBytes(second.provenancePath)))
        assertEquals(rawBefore, SourceTreeHasher.hash(fixture.raw))
        assertTrue(service.reconstruct(fixture.manifest, DecompilationSide.CLIENT).reused)
    }

    @Test
    fun `raw and rule-set identities invalidate reuse`() {
        val fixture = fixture("class Test { int value = 1; }\n")
        service(fixture.layout, rules = listOf(LiteralRule), ruleSetVersion = "rules-a")
            .reconstruct(fixture.manifest, DecompilationSide.CLIENT)

        val changedRules = service(fixture.layout, rules = listOf(LiteralRule), ruleSetVersion = "rules-b")
            .reconstruct(fixture.manifest, DecompilationSide.CLIENT)
        assertFalse(changedRules.reused)

        Files.writeString(fixture.raw.resolve("pkg/Test.java"), "class Test { int value = 1; int next = 3; }\n")
        writeDecompilationProvenance(fixture.layout, fixture.manifest, fixture.raw)
        val changedRaw = service(fixture.layout, rules = listOf(LiteralRule), ruleSetVersion = "rules-b")
            .reconstruct(fixture.manifest, DecompilationSide.CLIENT)
        assertFalse(changedRaw.reused)
        assertTrue("next = 3" in Files.readString(changedRaw.path.resolve("pkg/Test.java")))
    }

    @Test
    fun `failed exact patch leaves the last valid publication intact`() {
        val fixture = fixture("class Test { int value = 1; }\n")
        val valid = service(fixture.layout, rules = listOf(LiteralRule))
            .reconstruct(fixture.manifest, DecompilationSide.CLIENT, force = true)
        val before = SourceTreeHasher.hash(valid.path)
        val brokenPatch = ExactSourcePatch("PATCH-BAD", "pkg/Test.java", "must match pristine input", "missing context", "replacement")

        assertFailsWith<ReconstructionException> {
            service(fixture.layout, patches = listOf(brokenPatch)).reconstruct(
                fixture.manifest,
                DecompilationSide.CLIENT,
                force = true,
            )
        }
        assertEquals(before, SourceTreeHasher.hash(valid.path))
        assertEquals("class Test { int value = 2; }\n", Files.readString(valid.path.resolve("pkg/Test.java")))
    }

    private fun fixture(source: String): Fixture {
        val layout = CacheLayout(temporaryDirectory.resolve("cache"))
        val manifest = manifest()
        val raw = layout.rawSourceDirectory(manifest.id, "client")
        Files.createDirectories(raw.resolve("pkg"))
        Files.writeString(raw.resolve("pkg/Test.java"), source)
        writeDecompilationProvenance(layout, manifest, raw)
        return Fixture(layout, manifest, raw)
    }

    private fun service(
        layout: CacheLayout,
        rules: List<ReconstructionRule> = emptyList(),
        patches: List<ExactSourcePatch> = emptyList(),
        ruleSetVersion: String = "rules-test",
    ) = ReconstructionService(
        layout,
        rules = rules,
        patches = patches,
        implementationVersion = "implementation-test",
        ruleSetVersion = ruleSetVersion,
        patchSetVersion = "patches-test",
    )

    private fun writeDecompilationProvenance(layout: CacheLayout, manifest: VersionManifest, raw: Path) {
        val fingerprint = SourceTreeHasher.hash(raw)
        val identity = DecompilationModel.ArtifactIdentity("0".repeat(40), "0".repeat(64), 0)
        val provenance = DecompilationModel.Provenance(
            emptyList(),
            DecompilationModel.Inputs(
                DecompilationModel.FORMAT_VERSION,
                emptyList(),
                DecompilationModel.JavaRuntimeIdentity(25, "java-runtime-test", "test JDK"),
                manifest.id,
                "client",
                identity,
                DecompilationModel.VineflowerIdentity("test", sortedMapOf()),
            ),
            DecompilationModel.Output(emptyList(), fingerprint.fileCount, fingerprint.sha256, 1),
            DecompilationModel.SCHEMA_VERSION,
            DecompilationModel.ToolIdentity("test"),
        )
        val path = layout.decompilationProvenance(manifest.id, "client")
        Files.createDirectories(path.parent)
        Files.writeString(path, JarIndexStore.deterministicObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(provenance) + "\n")
    }

    private fun manifest(): VersionManifest {
        val emptyDownload = Download("0".repeat(40), 0, temporaryDirectory.resolve("unused").toUri())
        return VersionManifest(
            "test-version",
            VersionType.SNAPSHOT,
            OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            mapOf("client" to emptyDownload),
            AssetIndex("test", "0".repeat(40), 0, 0, temporaryDirectory.resolve("unused-assets").toUri()),
            "test",
            JavaVersion("java-runtime-test", 25),
            emptyList(),
            mapOf("jvm" to emptyList(), "game" to emptyList()),
            emptyMap(),
            "example.Main",
            null,
            null,
            null,
            null,
            JarIndexStore.deterministicObjectMapper().createObjectNode(),
        )
    }

    private object LiteralRule : ReconstructionRule {
        override val id = "TEST-RULE-001"
        override val description = "replace the test literal"
        override fun edits(relativePath: String, source: String): List<TextEdit> {
            val start = source.indexOf("value = 1")
            return if (start < 0) emptyList() else listOf(TextEdit(start, start + "value = 1".length, "value = 2", "test field"))
        }
    }

    private data class Fixture(val layout: CacheLayout, val manifest: VersionManifest, val raw: Path)
}
