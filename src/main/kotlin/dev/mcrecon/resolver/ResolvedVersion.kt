package dev.mcrecon.resolver

import dev.mcrecon.manifest.Library
import dev.mcrecon.manifest.LoggingConfiguration
import dev.mcrecon.manifest.VersionManifest
import dev.mcrecon.manifest.rules.HostPlatform
import dev.mcrecon.resolver.assets.AssetIndexDocument
import java.nio.file.Path

data class ResolvedLibraryArtifact(
    val role: LibraryArtifactRole,
    val classifier: String?,
    val file: ResolvedArtifact,
)

data class ResolvedLibrary(
    val metadata: Library,
    val artifacts: List<ResolvedLibraryArtifact>,
)

data class ResolvedAssetIndex(
    val metadata: dev.mcrecon.manifest.AssetIndex,
    val file: ResolvedArtifact,
    val document: AssetIndexDocument,
    /** Root passed to Minecraft through the manifest's `${assets_root}` placeholder. */
    val runtimeRoot: Path,
    /** Shared content-addressed object store from which the runtime view is materialized. */
    val objectStoreRoot: Path,
)

data class ResolvedLoggingConfiguration(
    val name: String,
    val metadata: LoggingConfiguration,
    val file: ResolvedArtifact,
)

data class ResolvedClientInputs(
    val manifest: VersionManifest,
    val platform: HostPlatform,
    val cacheRoot: Path,
    val client: ResolvedArtifact,
    val libraries: List<ResolvedLibrary>,
) {
    /** Manifest-ordered Java classpath context. Legacy native selections are extraction inputs only. */
    val classpathLibraries: List<ResolvedLibraryArtifact>
        get() = libraries.flatMap(ResolvedLibrary::artifacts).filter { it.role != LibraryArtifactRole.NATIVE }
}

data class ResolvedCompileLibrary(
    val artifactName: String,
    val file: ResolvedArtifact,
)

data class ResolvedClientCompileInputs(
    val manifest: VersionManifest,
    val client: ResolvedArtifact,
    val libraries: List<ResolvedCompileLibrary>,
)

data class ResolvedVersion(
    val manifest: VersionManifest,
    val platform: HostPlatform,
    val cacheRoot: Path,
    val client: ResolvedArtifact,
    val server: ResolvedArtifact,
    val libraries: List<ResolvedLibrary>,
    val assetIndex: ResolvedAssetIndex,
    val assetObjects: Map<String, ResolvedArtifact>,
    val loggingConfigurations: Map<String, ResolvedLoggingConfiguration>,
) {
    val classifierAndNativeArtifacts: List<ResolvedLibraryArtifact>
        get() = libraries.flatMap(ResolvedLibrary::artifacts).filter { it.role != LibraryArtifactRole.STANDARD }

    val allArtifacts: List<ResolvedArtifact>
        get() = buildList {
            add(client)
            add(server)
            libraries.flatMapTo(this) { library -> library.artifacts.map(ResolvedLibraryArtifact::file) }
            add(assetIndex.file)
            loggingConfigurations.values.mapTo(this) { it.file }
            assetObjects.values.mapTo(this) { it }
        }.distinctBy { it.path.toAbsolutePath().normalize() }

    val summary: ResolutionSummary
        get() {
            val artifacts = allArtifacts
            return ResolutionSummary(
                verifiedArtifacts = artifacts.size,
                downloadedArtifacts = artifacts.count { it.origin == ArtifactOrigin.DOWNLOAD },
                cachedArtifacts = artifacts.count { it.origin == ArtifactOrigin.CACHE },
                replacedCorruptArtifacts = artifacts.count(ResolvedArtifact::replacedCorruptEntry),
                bytesDownloaded = artifacts.filter { it.origin == ArtifactOrigin.DOWNLOAD }.sumOf { it.request.expectedSize },
            )
        }
}
