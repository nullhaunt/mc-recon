package dev.mcrecon.launcher

import dev.mcrecon.manifest.rules.RuleContext
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.resolver.LibraryArtifactRole
import dev.mcrecon.resolver.ResolvedVersion
import dev.mcrecon.resolver.ResolvedArtifact
import dev.mcrecon.resolver.assets.AssetAvailability
import dev.mcrecon.resolver.assets.AssetObjectResolver
import dev.mcrecon.runtime.JavaRuntime
import dev.mcrecon.runtime.NativePreparer
import dev.mcrecon.runtime.PreparedNatives
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

data class ClientSession(
    val username: String,
    val uuid: String,
    val accessToken: String,
    val clientId: String = "",
    val xuid: String = "",
)

data class ClientLaunchOptions(
    val gameDirectory: Path,
    val session: ClientSession,
    val demo: Boolean = false,
    val useDefaultJvmArguments: Boolean = true,
    val width: Int? = null,
    val height: Int? = null,
    val quickPlayPath: String? = null,
    val quickPlaySingleplayer: String? = null,
    val quickPlayMultiplayer: String? = null,
    val quickPlayRealms: String? = null,
)

data class ClientLaunchPreparation(
    val specification: LaunchSpecification,
    val natives: PreparedNatives,
    val assets: AssetAvailability,
)

class ClientLaunchBuilder(
    private val cacheLayout: CacheLayout,
    private val argumentEvaluator: ManifestArgumentEvaluator = ManifestArgumentEvaluator(),
    private val nativePreparer: NativePreparer = NativePreparer(),
) {
    fun build(version: ResolvedVersion, java: JavaRuntime, options: ClientLaunchOptions): ClientLaunchPreparation {
        validateOptions(options)
        createDirectory(options.gameDirectory, "client game")
        val nativeDirectory = cacheLayout.nativeDirectory(version.manifest.id, version.platform)
        val preparedNatives = nativePreparer.prepare(version, nativeDirectory)
        val classpath = buildClasspath(version)
        val features = LaunchFeatures(
            isDemoUser = options.demo,
            hasCustomResolution = options.width != null && options.height != null,
            hasQuickPlaysSupport = options.quickPlayPath != null,
            isQuickPlaySingleplayer = options.quickPlaySingleplayer != null,
            isQuickPlayMultiplayer = options.quickPlayMultiplayer != null,
            isQuickPlayRealms = options.quickPlayRealms != null,
        )
        val context = RuleContext(version.platform, features.toRuleValues())
        val placeholders = placeholders(version, options, nativeDirectory, classpath)

        val defaultJvm = if (options.useDefaultJvmArguments) {
            argumentEvaluator.evaluate(version.manifest.arguments["default-user-jvm"].orEmpty(), context, placeholders)
        } else {
            ExpandedArguments(emptyList(), emptySet())
        }
        val manifestJvm = argumentEvaluator.evaluate(version.manifest.arguments["jvm"].orEmpty(), context, placeholders)
        val logging = version.loggingConfigurations["client"]?.let { configuration ->
            argumentEvaluator.expand(
                configuration.metadata.argument,
                mapOf("path" to PlaceholderValue(configuration.file.path.toAbsolutePath().normalize().toString())),
            )
        } ?: ExpandedArguments(emptyList(), emptySet())
        val game = argumentEvaluator.evaluate(version.manifest.arguments["game"].orEmpty(), context, placeholders)
        val assetResolver = AssetObjectResolver(cacheLayout, dev.mcrecon.resolver.VerifiedArtifactStore(cacheLayout.root))
        val availability = assetResolver.prepareRuntimeView(version.assetIndex.document, version.assetIndex.runtimeRoot)

        return ClientLaunchPreparation(
            specification = LaunchSpecification(
                side = LaunchSide.CLIENT,
                executable = java.executable,
                workingDirectory = options.gameDirectory.toAbsolutePath().normalize(),
                jvmArguments = defaultJvm.values + manifestJvm.values + logging.values,
                classpath = classpath,
                mainClass = version.manifest.mainClass,
                gameArguments = game.values,
                sensitiveValues = defaultJvm.sensitiveValues + manifestJvm.sensitiveValues + logging.sensitiveValues + game.sensitiveValues,
            ),
            natives = preparedNatives,
            assets = availability,
        )
    }

    fun buildClasspath(version: ResolvedVersion): List<Path> = buildList {
        for (library in version.libraries) {
            for (artifact in library.artifacts) {
                // A primary artifact whose Maven coordinate has a classifier is still an explicit
                // classpath artifact. Only legacy artifacts selected through `natives` are extracted.
                if (artifact.role != LibraryArtifactRole.NATIVE) add(artifact.file.path.toAbsolutePath().normalize())
            }
        }
        add(version.client.path.toAbsolutePath().normalize())
    }

    private fun placeholders(
        version: ResolvedVersion,
        options: ClientLaunchOptions,
        nativeDirectory: Path,
        classpath: List<Path>,
    ): Map<String, PlaceholderValue> = buildMap {
        put("natives_directory", PlaceholderValue(nativeDirectory.toAbsolutePath().normalize().toString()))
        put("launcher_name", PlaceholderValue("mc-recon"))
        put("launcher_version", PlaceholderValue(PROJECT_VERSION))
        put("classpath", PlaceholderValue(classpath.joinToString(File.pathSeparator)))
        put("auth_player_name", PlaceholderValue(options.session.username))
        put("version_name", PlaceholderValue(version.manifest.id))
        put("game_directory", PlaceholderValue(options.gameDirectory.toAbsolutePath().normalize().toString()))
        put("assets_root", PlaceholderValue(version.assetIndex.runtimeRoot.toAbsolutePath().normalize().toString()))
        put("assets_index_name", PlaceholderValue(version.manifest.assetIndex.id))
        put("auth_uuid", PlaceholderValue(normalizeUuid(options.session.uuid)))
        put("auth_access_token", PlaceholderValue(options.session.accessToken, sensitive = true))
        put("clientid", PlaceholderValue(options.session.clientId))
        put("auth_xuid", PlaceholderValue(options.session.xuid))
        put("version_type", PlaceholderValue(version.manifest.type.manifestName))
        options.width?.let { put("resolution_width", PlaceholderValue(it.toString())) }
        options.height?.let { put("resolution_height", PlaceholderValue(it.toString())) }
        options.quickPlayPath?.let { put("quickPlayPath", PlaceholderValue(it)) }
        options.quickPlaySingleplayer?.let { put("quickPlaySingleplayer", PlaceholderValue(it)) }
        options.quickPlayMultiplayer?.let { put("quickPlayMultiplayer", PlaceholderValue(it)) }
        options.quickPlayRealms?.let { put("quickPlayRealms", PlaceholderValue(it)) }
    }

    private fun validateOptions(options: ClientLaunchOptions) {
        if ((options.width == null) != (options.height == null)) throw LaunchException("--width and --height must be specified together")
        if (options.width != null && (options.width !in 1..16384 || options.height!! !in 1..16384)) {
            throw LaunchException("Client resolution must be between 1 and 16384 pixels")
        }
        if (options.session.username.isBlank()) throw LaunchException("Client username must not be blank")
        if (options.session.accessToken.isBlank()) throw LaunchException("Client access token must not be blank")
    }

    private fun createDirectory(path: Path, description: String) {
        try {
            Files.createDirectories(path)
        } catch (exception: Exception) {
            throw LaunchException("Could not create $description directory $path", exception)
        }
        if (!Files.isDirectory(path)) throw LaunchException("$description directory is not usable: $path")
    }

    companion object {
        const val PROJECT_VERSION = "0.1.0-SNAPSHOT"
        val SYNTHETIC_SESSION = ClientSession(
            username = "mc-recon-player",
            uuid = "00000000-0000-0000-0000-000000000000",
            accessToken = "mc-recon-unauthenticated-token",
        )

        val DEMO_SESSION: ClientSession = SYNTHETIC_SESSION.copy(username = "mc-recon-demo")

        fun normalizeUuid(value: String): String {
            val parsed = try {
                if (value.length == 32 && '-' !in value) {
                    UUID.fromString(value.replaceFirst(UUID_COMPACT, "$1-$2-$3-$4-$5"))
                } else {
                    UUID.fromString(value)
                }
            } catch (_: IllegalArgumentException) {
                throw LaunchException("Invalid Minecraft UUID '$value'")
            }
            return parsed.toString().replace("-", "").lowercase()
        }

        private val UUID_COMPACT = Regex("(.{8})(.{4})(.{4})(.{4})(.{12})")
    }
}

data class ServerLaunchOptions(
    val workingDirectory: Path,
    val serverArguments: List<String> = listOf("nogui"),
)

class ServerLaunchBuilder {
    fun build(version: ResolvedVersion, java: JavaRuntime, options: ServerLaunchOptions): LaunchSpecification {
        return build(version.server, java, options)
    }

    fun build(server: ResolvedArtifact, java: JavaRuntime, options: ServerLaunchOptions): LaunchSpecification {
        try {
            Files.createDirectories(options.workingDirectory)
        } catch (exception: Exception) {
            throw LaunchException("Could not create server working directory ${options.workingDirectory}", exception)
        }
        if (!Files.isDirectory(options.workingDirectory)) {
            throw LaunchException("Server working directory is not usable: ${options.workingDirectory}")
        }
        return LaunchSpecification(
            side = LaunchSide.SERVER,
            executable = java.executable,
            workingDirectory = options.workingDirectory.toAbsolutePath().normalize(),
            jvmArguments = listOf("-jar", server.path.toAbsolutePath().normalize().toString()),
            gameArguments = options.serverArguments,
        )
    }
}
