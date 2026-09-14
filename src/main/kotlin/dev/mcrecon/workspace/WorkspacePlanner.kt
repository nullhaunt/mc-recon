package dev.mcrecon.workspace

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mcrecon.classfile.JarIndexStore
import dev.mcrecon.classfile.Sha256
import dev.mcrecon.compiler.CompilerOptionProfiles
import dev.mcrecon.decompiler.DecompilationModel
import dev.mcrecon.decompiler.DecompilationSide
import dev.mcrecon.launcher.ClientLaunchBuilder
import dev.mcrecon.launcher.ClientLaunchOptions
import dev.mcrecon.launcher.ClientSession
import dev.mcrecon.launcher.LaunchSpecification
import dev.mcrecon.manifest.VersionManifest
import dev.mcrecon.manifest.rules.HostPlatform
import dev.mcrecon.reconstruction.ReconstructionModel
import dev.mcrecon.reconstruction.ReconstructionService
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.resolver.ResolvedArtifact
import dev.mcrecon.resolver.VersionResolver
import dev.mcrecon.runtime.JavaRuntime
import java.nio.file.Files
import java.nio.file.Path

class WorkspacePlanner(
    private val cacheLayout: CacheLayout,
    private val resolver: VersionResolver = VersionResolver(cacheLayout),
    private val mapper: ObjectMapper = JarIndexStore.deterministicObjectMapper(),
) {
    fun plan(manifest: VersionManifest, manifestPath: Path, side: DecompilationSide, platform: HostPlatform): WorkspacePlan {
        if (side != DecompilationSide.CLIENT) {
            throw WorkspaceException("Server workspaces await server bundle extraction and reconstruction")
        }

        val reconstruction = try {
            ReconstructionService(cacheLayout).reconstruct(manifest, side, force = false)
        } catch (exception: Exception) {
            throw WorkspaceException("A valid reconstructed source tree is required before workspace generation: ${exception.message}", exception)
        }
        val decompilationPath = cacheLayout.decompilationProvenance(manifest.id, side.cliName)
        val reconstructionPath = reconstruction.provenancePath
        validateProvenance(manifest, side, decompilationPath, reconstructionPath)

        val resolved = try {
            resolver.resolveCached(manifest, platform, includeAssetObjects = true)
        } catch (exception: Exception) {
            throw WorkspaceException(
                "The complete verified client runtime, including assets, is required. " +
                    "Run 'mc-recon resolve <manifest> --assets --compile-libraries' first: ${exception.message}",
                exception,
            )
        }
        val compileInputs = try {
            resolver.resolveClientCompileInputsCached(manifest)
        } catch (exception: Exception) {
            throw WorkspaceException(
                "The exact compile dependency set is incomplete. Run 'mc-recon resolve <manifest> --compile-libraries' first: ${exception.message}",
                exception,
            )
        }

        val centralGameDirectory = cacheLayout.defaultGameDirectory(manifest.id, platform, "workspace-planning")
        val preparation = ClientLaunchBuilder(cacheLayout).build(
            resolved,
            JavaRuntime(Path.of("java"), manifest.javaVersion.majorVersion, "workspace launch model"),
            ClientLaunchOptions(
                gameDirectory = centralGameDirectory,
                session = ClientSession(
                    RESERVED_USERNAME,
                    RESERVED_UUID,
                    RESERVED_ACCESS_TOKEN,
                    RESERVED_CLIENT_ID,
                    RESERVED_XUID,
                ),
                demo = true,
            ),
        )
        if (!preparation.assets.complete) {
            throw WorkspaceException("Client asset preflight unexpectedly reported ${preparation.assets.missingObjects} missing objects")
        }

        val dependencies = mergeDependencies(compileInputs.libraries.map { it.artifactName to it.file }, resolved)
        val translatedLaunch = translateLaunch(
            preparation.specification,
            centralGameDirectory,
            preparation.natives.directory,
            resolved,
        )
        val optionProfile = CompilerOptionProfiles.find(
            manifest.javaVersion.majorVersion,
            "source-target${manifest.javaVersion.majorVersion}-g-parameters",
        )
        return WorkspacePlan(
            manifest,
            manifestPath.toAbsolutePath().normalize(),
            Sha256.file(manifestPath),
            platform,
            reconstruction.path,
            cacheLayout.rawSourceDirectory(manifest.id, side.cliName),
            decompilationPath,
            reconstructionPath,
            resolved,
            dependencies,
            preparation.natives.directory,
            translatedLaunch,
            optionProfile.logicalOptions(manifest.javaVersion.majorVersion),
        )
    }

    private fun validateProvenance(
        manifest: VersionManifest,
        side: DecompilationSide,
        decompilationPath: Path,
        reconstructionPath: Path,
    ) {
        if (!Files.isRegularFile(decompilationPath) || !Files.isRegularFile(reconstructionPath)) {
            throw WorkspaceException("Reconstruction provenance is incomplete for Minecraft ${manifest.id} ${side.cliName}")
        }
        val decompilation = Files.newInputStream(decompilationPath).use {
            mapper.readValue(it, DecompilationModel.Provenance::class.java)
        }
        val reconstruction = Files.newInputStream(reconstructionPath).use {
            mapper.readValue(it, ReconstructionModel.Provenance::class.java)
        }
        if (decompilation.inputs().minecraftVersion() != manifest.id || decompilation.inputs().side() != side.cliName ||
            reconstruction.inputs().minecraftVersion() != manifest.id || reconstruction.inputs().side() != side.cliName
        ) {
            throw WorkspaceException("Reconstruction provenance does not match Minecraft ${manifest.id} ${side.cliName}")
        }
    }

    private fun mergeDependencies(
        compile: List<Pair<String, ResolvedArtifact>>,
        resolved: dev.mcrecon.resolver.ResolvedVersion,
    ): List<PlannedDependency> {
        data class MutableDependency(
            var name: String,
            var role: String,
            val artifact: ResolvedArtifact,
            var compileOrder: Int? = null,
            var runtimeOrder: Int? = null,
        )

        val merged = linkedMapOf<String, MutableDependency>()
        compile.forEachIndexed { order, (name, artifact) ->
            merged[artifact.request.expectedSha1.lowercase()] = MutableDependency(name, "standard", artifact, compileOrder = order)
        }
        var runtimeOrder = 0
        resolved.libraries.forEach { library ->
            library.artifacts.filter { it.role != dev.mcrecon.resolver.LibraryArtifactRole.NATIVE }.forEach { selected ->
                val key = selected.file.request.expectedSha1.lowercase()
                val current = merged.getOrPut(key) {
                    MutableDependency(library.metadata.name, selected.role.name.lowercase(), selected.file)
                }
                current.runtimeOrder = runtimeOrder++
                if (current.compileOrder == null) current.role = selected.role.name.lowercase()
            }
        }
        return merged.values.mapIndexed { index, value ->
            val extension = value.artifact.path.fileName.toString().substringAfterLast('.', "jar").lowercase()
            val safeName = value.name.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifBlank { "artifact" }
            PlannedDependency(
                value.name,
                value.role,
                value.artifact,
                ".mc-recon/libraries/${index.toString().padStart(3, '0')}-$safeName.$extension",
                value.compileOrder,
                value.runtimeOrder,
            )
        }
    }

    private fun translateLaunch(
        specification: LaunchSpecification,
        gameDirectory: Path,
        nativesDirectory: Path,
        resolved: dev.mcrecon.resolver.ResolvedVersion,
    ): LaunchSpecification {
        val replacements = linkedMapOf(
            gameDirectory.toAbsolutePath().normalize().toString() to WorkspacePlaceholders.GAME_DIRECTORY,
            cacheLayout.assetsRoot().toAbsolutePath().normalize().toString() to WorkspacePlaceholders.ASSETS_ROOT,
            nativesDirectory.toAbsolutePath().normalize().toString() to WorkspacePlaceholders.NATIVES_DIRECTORY,
            RESERVED_USERNAME to WorkspacePlaceholders.USERNAME,
            ClientLaunchBuilder.normalizeUuid(RESERVED_UUID) to WorkspacePlaceholders.UUID,
            RESERVED_ACCESS_TOKEN to WorkspacePlaceholders.ACCESS_TOKEN,
            RESERVED_CLIENT_ID to WorkspacePlaceholders.CLIENT_ID,
            RESERVED_XUID to WorkspacePlaceholders.XUID,
        )
        resolved.loggingConfigurations["client"]?.file?.path?.toAbsolutePath()?.normalize()?.toString()?.let {
            replacements[it] = WorkspacePlaceholders.LOGGING_FILE
        }
        val translate: (String) -> String = { value ->
            replacements.entries.sortedByDescending { it.key.length }.fold(value) { current, entry ->
                current.replace(entry.key, entry.value)
            }
        }
        return specification.copy(
            executable = Path.of("java"),
            workingDirectory = Path.of(WorkspacePlaceholders.GAME_DIRECTORY),
            jvmArguments = removeClasspathArguments(specification.jvmArguments).map(translate),
            classpath = emptyList(),
            gameArguments = specification.gameArguments.map(translate),
            sensitiveValues = setOf(WorkspacePlaceholders.ACCESS_TOKEN),
        )
    }

    private fun removeClasspathArguments(arguments: List<String>): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index]
            if (argument == "-cp" || argument == "-classpath" || argument == "--class-path") {
                if (index + 1 >= arguments.size) throw WorkspaceException("Manifest classpath option has no value")
                index += 2
                continue
            }
            if (argument.startsWith("--class-path=")) {
                index++
                continue
            }
            result += argument
            index++
        }
        return result
    }

    private companion object {
        const val RESERVED_USERNAME = "__MC_RECON_WORKSPACE_USERNAME__"
        const val RESERVED_UUID = "00000000-0000-0000-0000-000000000001"
        const val RESERVED_ACCESS_TOKEN = "__MC_RECON_WORKSPACE_ACCESS_TOKEN__"
        const val RESERVED_CLIENT_ID = "__MC_RECON_WORKSPACE_CLIENT_ID__"
        const val RESERVED_XUID = "__MC_RECON_WORKSPACE_XUID__"
    }
}
