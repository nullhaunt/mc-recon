package dev.mcrecon.compiler

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mcrecon.classfile.ClassfileTestFixtures
import dev.mcrecon.decompiler.DecompilationSide
import dev.mcrecon.manifest.AssetIndex
import dev.mcrecon.manifest.Download
import dev.mcrecon.manifest.JavaVersion
import dev.mcrecon.manifest.VersionManifest
import dev.mcrecon.manifest.VersionType
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.verification.VerificationService
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CompilerProfileServiceTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `option profiles have deliberate stable ordering and preserve distinct spellings`() {
        val profiles = CompilerOptionProfiles.all(25)
        val default = profiles.single { it.id == "release25-default" }
        val parameters = profiles.single { it.id == "release25-parameters" }
        val allDebug = profiles.single { it.id == "release25-g" }
        val explicitAll = profiles.single { it.id == "release25-g-source-lines-vars" }
        val sourceTarget = profiles.single { it.id == "source-target25-default" }

        assertEquals(
            listOf(
                "--release", "25", "-encoding", "UTF-8", "-proc:none", "-Xmaxerrs", "100000",
                "-Xmaxwarns", "100000", "-classpath", "<resolved-manifest-artifacts>", "-d", "<class-output>",
            ),
            default.logicalOptions(25),
        )
        assertTrue(parameters.logicalOptions(25).indexOf("-parameters") < parameters.logicalOptions(25).indexOf("-Xmaxerrs"))
        assertNotEquals(allDebug.logicalOptions(25), explicitAll.logicalOptions(25))
        assertEquals(listOf("-source", "25", "-target", "25"), sourceTarget.logicalOptions(25).take(4))
        assertEquals(profiles.size, profiles.map { it.id }.distinct().size)
    }

    @Test
    fun `probe compiles verifies ranks and reuses isolated candidates deterministically`() {
        val layout = CacheLayout(temporaryDirectory.resolve("cache"))
        val sourceText = "package sample; public class Example { public int value(int input) { return input + 1; } }\n"
        val source = layout.reconstructedSourceDirectory("profile-test", "client")
        Files.createDirectories(source.resolve("sample"))
        Files.writeString(source.resolve("sample/Example.java"), sourceText)
        Files.createDirectories(layout.reconstructionProvenance("profile-test", "client").parent)
        Files.writeString(layout.reconstructionProvenance("profile-test", "client"), "{}\n")

        val referenceSource = temporaryDirectory.resolve("reference-source")
        Files.createDirectories(referenceSource.resolve("sample"))
        Files.writeString(referenceSource.resolve("sample/Example.java"), sourceText)
        val referenceClasses = temporaryDirectory.resolve("reference-classes")
        val defaultOptions = CompilerOptionProfiles.find(25, "release25-default").logicalOptions(25)
        assertTrue(JavaCompilationEngine().compile(referenceSource, referenceClasses, emptyList(), defaultOptions).success)
        val clientJar = ClassfileTestFixtures.writeJar(
            layout.versionArtifact("profile-test", "client.jar"),
            "sample/Example.class" to Files.readAllBytes(referenceClasses.resolve("sample/Example.class")),
        )
        val manifest = manifest(clientJar)

        val canonical = CompilationService(layout).compile(manifest, DecompilationSide.CLIENT, force = true)
        assertEquals("SUCCESS", canonical.report.output().result())
        VerificationService(layout).verify(manifest, dev.mcrecon.classfile.BinarySide.CLIENT)
        val canonicalClassesPath = requireNotNull(canonical.classesPath)
        val canonicalClassesBefore = DeterministicTreeHasher.hash(canonicalClassesPath, CompilationService.CLASS_TREE_DOMAIN)
        val canonicalReportBefore = Files.readAllBytes(canonical.reportPath)
        val canonicalVerificationPath = layout.verificationReport("profile-test", "client")
        val canonicalVerificationBefore = Files.readAllBytes(canonicalVerificationPath)

        val identity = identity("compiler-a")
        val probeService = ToolchainProbeService(layout, CompilerToolchainIdentityProvider { identity })
        val selected = listOf(
            CompilerOptionProfiles.find(25, "release25-default"),
            CompilerOptionProfiles.find(25, "release25-parameters"),
        )
        val first = probeService.probe(manifest, DecompilationSide.CLIENT, selected, force = true)
        val firstBytes = Files.readAllBytes(first.reportPath)
        val second = probeService.probe(manifest, DecompilationSide.CLIENT, selected)

        assertContentEquals(firstBytes, Files.readAllBytes(second.reportPath))
        assertEquals(2, first.report.profiles().size)
        assertEquals("release25-default", first.report.profiles().first().profile().optionProfileId())
        val default = first.report.profiles().single { it.profile().optionProfileId() == "release25-default" }
        val parameters = first.report.profiles().single { it.profile().optionProfileId() == "release25-parameters" }
        assertEquals(canonical.report.output().classTreeSha256(), default.classTreeSha256())
        assertEquals(1, default.metrics().classes().exact())
        assertEquals(0, parameters.metrics().classes().exact())
        assertTrue(parameters.metrics().methodParameters().extra() > 0)
        assertEquals(listOf("sample.Example"), parameters.regressions().exactRegressions())
        assertEquals(0, parameters.regressions().semanticCodeRegressions())
        assertTrue(Files.isDirectory(layout.compilerProfileClasses("profile-test", "client", default.profile().id())))
        assertTrue(Files.isDirectory(layout.compilerProfileClasses("profile-test", "client", parameters.profile().id())))
        val reused = CompilerProfileService(layout, identityProvider = CompilerToolchainIdentityProvider { identity })
            .compileAndVerify(manifest, DecompilationSide.CLIENT, selected.first())
        assertTrue(reused.reusedCompilation)
        assertEquals(canonicalClassesBefore, DeterministicTreeHasher.hash(canonicalClassesPath, CompilationService.CLASS_TREE_DOMAIN))
        assertContentEquals(canonicalReportBefore, Files.readAllBytes(canonical.reportPath))
        assertContentEquals(canonicalVerificationBefore, Files.readAllBytes(canonicalVerificationPath))

        val otherService = CompilerProfileService(layout, identityProvider = CompilerToolchainIdentityProvider { identity("compiler-b") })
        val other = otherService.compileAndVerify(manifest, DecompilationSide.CLIENT, selected.first(), force = true)
        assertNotEquals(default.profile().id(), other.profile.id())
        assertTrue(Files.isDirectory(layout.compilerProfileClasses("profile-test", "client", default.profile().id())))
        assertFalse(other.reusedCompilation)
    }

    private fun identity(seed: String) = CompilerProfileModel.ToolchainIdentity(
        "fixture-compiler-module",
        sha256(seed.toByteArray()),
        sha256("identity-$seed".toByteArray()),
        "<selected-java-home>",
        sha256("java-$seed".toByteArray()),
        "25.0-test+$seed",
        "Fixture Vendor",
        "25",
        "Fixture VM",
        "com.sun.tools.javac.api.JavacTool",
        "25",
        "javac 25-$seed",
        sha256("release-$seed".toByteArray()),
    )

    private fun manifest(clientJar: Path): VersionManifest {
        val client = Files.readAllBytes(clientJar)
        val download = Download(sha1(client), client.size.toLong(), URI("https://example.invalid/client.jar"))
        return VersionManifest(
            id = "profile-test",
            type = VersionType.SNAPSHOT,
            time = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            releaseTime = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            downloads = mapOf("client" to download, "server" to download),
            assetIndex = AssetIndex("test", "0".repeat(40), 0, 0, URI("https://example.invalid/assets.json")),
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

    private fun sha1(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes))
    private fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
