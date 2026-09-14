package dev.mcrecon.workspace

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mcrecon.classfile.JarIndexStore
import dev.mcrecon.classfile.Sha256
import dev.mcrecon.decompiler.DecompilationModel
import dev.mcrecon.decompiler.DecompilationSide
import dev.mcrecon.decompiler.SourceTreeHasher
import dev.mcrecon.manifest.AssetIndex
import dev.mcrecon.manifest.Download
import dev.mcrecon.manifest.JavaVersion
import dev.mcrecon.manifest.Library
import dev.mcrecon.manifest.LibraryDownloads
import dev.mcrecon.manifest.ManifestArgument
import dev.mcrecon.manifest.VersionManifest
import dev.mcrecon.manifest.VersionType
import dev.mcrecon.manifest.rules.Architecture
import dev.mcrecon.manifest.rules.HostPlatform
import dev.mcrecon.manifest.rules.OperatingSystem
import dev.mcrecon.manifest.rules.OsCondition
import dev.mcrecon.manifest.rules.Rule
import dev.mcrecon.manifest.rules.RuleAction
import dev.mcrecon.reconstruction.ReconstructionModel
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.sha1ForTest
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WorkspacePlannerTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `selects exact compile and host runtime dependencies and portable launch metadata`() {
        val cache = CacheLayout(temporaryDirectory.resolve("cache"))
        val platform = HostPlatform(OperatingSystem.WINDOWS, Architecture.X86_64, "10.0")
        val client = "client".toByteArray()
        val server = "server".toByteArray()
        val assetIndex = "{\"objects\":{}}".toByteArray()
        val runtimeLibrary = "runtime-library".toByteArray()
        val compileOnlyLibrary = "compile-only-library".toByteArray()
        val runtimeDownload = download("libraries/example/runtime/1/runtime-1.jar", runtimeLibrary)
        val compileOnlyDownload = download("libraries/example/other/1/other-1.jar", compileOnlyLibrary)
        val manifest = VersionManifest(
            "workspace-plan-test", VersionType.SNAPSHOT, time(), time(),
            mapOf("client" to download(null, client), "server" to download(null, server)),
            AssetIndex("test-assets", sha1ForTest(assetIndex), assetIndex.size.toLong(), 0, URI("https://example.invalid/assets")),
            "test-assets", JavaVersion("java-runtime-test", 25),
            listOf(
                Library("example:runtime:1", LibraryDownloads(runtimeDownload, emptyMap()), null, emptyMap(), null, null),
                Library(
                    "example:other:1", LibraryDownloads(compileOnlyDownload, emptyMap()),
                    listOf(Rule(RuleAction.ALLOW, OsCondition(name = OperatingSystem.OSX))), emptyMap(), null, null,
                ),
            ),
            mapOf(
                "jvm" to listOf(
                    ManifestArgument.Literal("-Djava.library.path=\${natives_directory}"),
                    ManifestArgument.Literal("-cp"), ManifestArgument.Literal("\${classpath}"),
                ),
                "game" to listOf(
                    ManifestArgument.Literal("--gameDir"), ManifestArgument.Literal("\${game_directory}"),
                    ManifestArgument.Conditional(
                        listOf("--demo"), listOf(Rule(RuleAction.ALLOW, features = mapOf("is_demo_user" to true))),
                    ),
                ),
            ),
            emptyMap(), "example.Main", null, null, null, null, ObjectMapper().createObjectNode(),
        )
        write(cache.versionArtifact(manifest.id, "client.jar"), client)
        write(cache.versionArtifact(manifest.id, "server.jar"), server)
        write(cache.assetIndex(manifest.assetIndex.id), assetIndex)
        write(cache.libraryArtifact(checkNotNull(runtimeDownload.path)), runtimeLibrary)
        write(cache.libraryArtifact(checkNotNull(compileOnlyDownload.path)), compileOnlyLibrary)
        val manifestPath = temporaryDirectory.resolve("manifest.json")
        Files.writeString(manifestPath, "manifest bytes\n")
        writeReconstruction(cache, manifest)

        val plan = WorkspacePlanner(cache).plan(manifest, manifestPath, DecompilationSide.CLIENT, platform)

        assertEquals(manifest.id, plan.manifest.id)
        assertEquals(
            listOf(
                "-source", "25", "-target", "25", "-encoding", "UTF-8", "-proc:none", "-g", "-parameters",
                "-Xmaxerrs", "100000", "-Xmaxwarns", "100000", "-classpath", "<resolved-manifest-artifacts>",
                "-d", "<class-output>",
            ),
            plan.compilerOptions,
        )
        assertEquals(listOf("example:runtime:1", "example:other:1"), plan.dependencies.sortedBy { it.compileOrder }.map { it.artifactName })
        assertEquals(0, plan.dependencies.single { it.artifactName == "example:runtime:1" }.runtimeOrder)
        assertNull(plan.dependencies.single { it.artifactName == "example:other:1" }.runtimeOrder)
        assertFalse(plan.launch.jvmArguments.any { it == "-cp" || it.contains(cache.root.toString()) })
        assertContains(plan.launch.jvmArguments.single(), WorkspacePlaceholders.NATIVES_DIRECTORY)
        assertContains(plan.launch.gameArguments, "--demo")
        assertContains(plan.launch.gameArguments, WorkspacePlaceholders.GAME_DIRECTORY)
    }

    private fun writeReconstruction(cache: CacheLayout, manifest: VersionManifest) {
        val raw = cache.rawSourceDirectory(manifest.id, "client")
        val reconstructed = cache.reconstructedSourceDirectory(manifest.id, "client")
        Files.createDirectories(raw.resolve("example"))
        Files.createDirectories(reconstructed.resolve("example"))
        Files.writeString(raw.resolve("example/Main.java"), "package example; class Main {}\n")
        Files.writeString(reconstructed.resolve("example/Main.java"), "package example; class Main {}\n")
        val rawFingerprint = SourceTreeHasher.hash(raw)
        val reconstructedFingerprint = SourceTreeHasher.hash(reconstructed)
        val mapper = JarIndexStore.deterministicObjectMapper()
        val artifact = DecompilationModel.ArtifactIdentity("0".repeat(40), "0".repeat(64), 0)
        val decompilation = DecompilationModel.Provenance(
            emptyList(),
            DecompilationModel.Inputs(
                "1", emptyList(), DecompilationModel.JavaRuntimeIdentity(25, "java-runtime-test", "test"),
                manifest.id, "client", artifact, DecompilationModel.VineflowerIdentity("1.12.0", emptyMap()),
            ),
            DecompilationModel.Output(emptyList(), 1, rawFingerprint.sha256, 1), 1,
            DecompilationModel.ToolIdentity("test"),
        )
        val decompilationPath = cache.decompilationProvenance(manifest.id, "client")
        Files.createDirectories(decompilationPath.parent)
        Files.writeString(decompilationPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(decompilation) + "\n")
        val reconstruction = ReconstructionModel.Provenance(
            emptyList(),
            ReconstructionModel.Inputs(
                manifest.id, "2", ReconstructionModel.RawSource(Sha256.file(decompilationPath), rawFingerprint.sha256),
                "1", "2", "2", "client",
            ),
            ReconstructionModel.Output(1, reconstructedFingerprint.sha256), emptyList(), emptyList(), 1,
        )
        val path = cache.reconstructionProvenance(manifest.id, "client")
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(reconstruction) + "\n")
    }

    private fun download(path: String?, bytes: ByteArray): Download = Download(
        sha1ForTest(bytes), bytes.size.toLong(), URI("https://example.invalid/${path ?: "artifact"}"), path,
    )

    private fun write(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
    }

    private fun time() = OffsetDateTime.parse("2026-01-01T00:00:00Z")
}
