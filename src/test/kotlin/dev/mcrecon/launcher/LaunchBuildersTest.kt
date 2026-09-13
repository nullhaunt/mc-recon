package dev.mcrecon.launcher

import dev.mcrecon.artifact
import dev.mcrecon.manifest.LoggingConfiguration
import dev.mcrecon.manifest.ManifestArgument
import dev.mcrecon.manifest.rules.Rule
import dev.mcrecon.manifest.rules.RuleAction
import dev.mcrecon.resolvedVersion
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.resolver.LibraryArtifactRole
import dev.mcrecon.resolver.ResolvedLoggingConfiguration
import dev.mcrecon.runtime.JavaRuntime
import dev.mcrecon.testLibrary
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchBuildersTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `classpath preserves standard and primary classifier order and excludes selected natives`() {
        val standard = artifact(temporaryDirectory.resolve("libraries/a.jar"), byteArrayOf(1))
        val native = artifact(temporaryDirectory.resolve("libraries/native.jar"), byteArrayOf(2))
        val classifier = artifact(temporaryDirectory.resolve("libraries/primary-classifier.jar"), byteArrayOf(3))
        val version = resolvedVersion(
            temporaryDirectory,
            listOf(
                testLibrary("example:a:1", listOf(LibraryArtifactRole.STANDARD to standard)),
                testLibrary("example:native:1", listOf(LibraryArtifactRole.NATIVE to native)),
                testLibrary("example:classified:1:natives-windows", listOf(LibraryArtifactRole.CLASSIFIER to classifier)),
            ),
        )

        val classpath = ClientLaunchBuilder(CacheLayout(temporaryDirectory)).buildClasspath(version)

        assertEquals(listOf(standard.path, classifier.path, version.client.path).map { it.toAbsolutePath().normalize() }, classpath)
        assertEquals(classpath.joinToString(File.pathSeparator), classpath.joinToString(File.pathSeparator))
    }

    @Test
    fun `client arguments apply features defaults logging and redact token only when rendered`() {
        val arguments = mapOf(
            "default-user-jvm" to listOf(ManifestArgument.Literal("-Xmx1G")),
            "jvm" to listOf(ManifestArgument.Literal("-cp"), ManifestArgument.Literal("\${classpath}")),
            "game" to listOf(
                ManifestArgument.Literal("--accessToken"),
                ManifestArgument.Literal("\${auth_access_token}"),
                ManifestArgument.Conditional(
                    listOf("--demo"),
                    listOf(Rule(RuleAction.ALLOW, features = mapOf("is_demo_user" to true))),
                ),
                ManifestArgument.Conditional(
                    listOf("--width", "\${resolution_width}", "--height", "\${resolution_height}"),
                    listOf(Rule(RuleAction.ALLOW, features = mapOf("has_custom_resolution" to true))),
                ),
            ),
        )
        var version = resolvedVersion(temporaryDirectory, arguments = arguments)
        val logFile = artifact(temporaryDirectory.resolve("logging/client.xml"), "log".toByteArray())
        val logging = LoggingConfiguration(
            "-Dlog.path=\${path}",
            dev.mcrecon.manifest.Download(logFile.request.expectedSha1, logFile.request.expectedSize, logFile.request.uri),
            "test",
        )
        version = version.copy(loggingConfigurations = mapOf("client" to ResolvedLoggingConfiguration("client", logging, logFile)))
        val java = JavaRuntime(temporaryDirectory.resolve("java"), 25, "test")

        val preparation = ClientLaunchBuilder(CacheLayout(temporaryDirectory)).build(
            version,
            java,
            ClientLaunchOptions(
                temporaryDirectory.resolve("game"),
                ClientSession("Player", "12345678-1234-1234-1234-123456789abc", "very-secret"),
                demo = true,
                width = 1280,
                height = 720,
            ),
        )

        assertEquals("-Xmx1G", preparation.specification.jvmArguments.first())
        assertContains(preparation.specification.jvmArguments, "-Dlog.path=${logFile.path.toAbsolutePath().normalize()}")
        assertContains(preparation.specification.gameArguments, "very-secret")
        assertContains(preparation.specification.gameArguments, "--demo")
        assertContains(preparation.specification.gameArguments, "1280")
        val rendered = LaunchCommandRenderer.render(preparation.specification)
        assertFalse(rendered.contains("very-secret"))
        assertContains(rendered, "<redacted>")
        assertTrue(preparation.assets.complete)
        Files.walk(temporaryDirectory).use { paths ->
            assertFalse(
                paths.filter(Files::isRegularFile).anyMatch { file ->
                    runCatching { Files.readString(file).contains("very-secret") }.getOrDefault(false)
                },
            )
        }
    }

    @Test
    fun `absent resolution leaves feature argument disabled and defaults may be omitted`() {
        val conditional = ManifestArgument.Conditional(
            listOf("\${resolution_width}"),
            listOf(Rule(RuleAction.ALLOW, features = mapOf("has_custom_resolution" to true))),
        )
        val version = resolvedVersion(
            temporaryDirectory,
            arguments = mapOf(
                "default-user-jvm" to listOf(ManifestArgument.Literal("-Xmx1G")),
                "jvm" to emptyList(),
                "game" to listOf(conditional),
            ),
        )
        val preparation = ClientLaunchBuilder(CacheLayout(temporaryDirectory)).build(
            version,
            JavaRuntime(temporaryDirectory.resolve("java"), 25, "test"),
            ClientLaunchOptions(temporaryDirectory.resolve("game"), ClientLaunchBuilder.DEMO_SESSION, demo = true, useDefaultJvmArguments = false),
        )
        assertEquals(emptyList(), preparation.specification.jvmArguments)
        assertEquals(emptyList(), preparation.specification.gameArguments)
    }

    @Test
    fun `server uses java jar invocation and never adds EULA acceptance`() {
        val version = resolvedVersion(temporaryDirectory)
        val java = JavaRuntime(temporaryDirectory.resolve("custom-java"), 25, "test")
        val specification = ServerLaunchBuilder().build(
            version,
            java,
            ServerLaunchOptions(temporaryDirectory.resolve("server-run")),
        )
        assertEquals(java.executable, specification.executable)
        assertEquals(listOf("-jar", version.server.path.toAbsolutePath().normalize().toString()), specification.jvmArguments)
        assertEquals(listOf("nogui"), specification.gameArguments)
        assertFalse(specification.command().any { it.contains("eula", ignoreCase = true) })
    }

    @Test
    fun `UUID input is normalized and malformed values are rejected`() {
        assertEquals(
            "12345678123412341234123456789abc",
            ClientLaunchBuilder.normalizeUuid("12345678-1234-1234-1234-123456789abc"),
        )
        assertEquals(
            "12345678123412341234123456789abc",
            ClientLaunchBuilder.normalizeUuid("12345678123412341234123456789abc"),
        )
        kotlin.test.assertFailsWith<LaunchException> { ClientLaunchBuilder.normalizeUuid("not-a-uuid") }
    }
}
