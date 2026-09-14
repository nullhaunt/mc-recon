package dev.mcrecon.workspace

import dev.mcrecon.launcher.LaunchSpecification
import dev.mcrecon.manifest.VersionManifest
import dev.mcrecon.manifest.rules.HostPlatform
import dev.mcrecon.resolver.ResolvedArtifact
import dev.mcrecon.resolver.ResolvedVersion
import java.nio.file.Path

data class PlannedDependency(
    val artifactName: String,
    val role: String,
    val artifact: ResolvedArtifact,
    val workspacePath: String,
    val compileOrder: Int?,
    val runtimeOrder: Int?,
)

data class WorkspacePlan(
    val manifest: VersionManifest,
    val manifestPath: Path,
    val manifestSha256: String,
    val platform: HostPlatform,
    val reconstructedSource: Path,
    val rawSource: Path,
    val decompilationProvenance: Path,
    val reconstructionProvenance: Path,
    val resolved: ResolvedVersion,
    val dependencies: List<PlannedDependency>,
    val preparedNatives: Path,
    val launch: LaunchSpecification,
    val compilerOptions: List<String>,
)

open class WorkspaceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object WorkspacePlaceholders {
    const val GAME_DIRECTORY = "@MC_RECON_GAME_DIRECTORY@"
    const val ASSETS_ROOT = "@MC_RECON_ASSETS_ROOT@"
    const val NATIVES_DIRECTORY = "@MC_RECON_NATIVES_DIRECTORY@"
    const val LOGGING_FILE = "@MC_RECON_LOGGING_FILE@"
    const val USERNAME = "@MC_RECON_USERNAME@"
    const val UUID = "@MC_RECON_UUID@"
    const val ACCESS_TOKEN = "@MC_RECON_ACCESS_TOKEN@"
    const val CLIENT_ID = "@MC_RECON_CLIENT_ID@"
    const val XUID = "@MC_RECON_XUID@"
}
