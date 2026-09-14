package dev.mcrecon.resolver

import dev.mcrecon.manifest.AssetIndex
import dev.mcrecon.manifest.Download
import dev.mcrecon.manifest.VersionManifest
import dev.mcrecon.manifest.rules.HostPlatform
import dev.mcrecon.manifest.rules.RuleContext
import dev.mcrecon.resolver.assets.AssetIndexParser
import dev.mcrecon.resolver.assets.AssetObjectResolver
import java.net.URI

class VersionResolver(
    private val cacheLayout: CacheLayout,
    private val artifactStore: VerifiedArtifactStore = VerifiedArtifactStore(cacheLayout.root),
    private val librarySelector: LibrarySelector = LibrarySelector(),
    private val assetIndexParser: AssetIndexParser = AssetIndexParser(),
    private val assetBaseUri: URI = URI("https://resources.download.minecraft.net/"),
) {
    fun resolve(manifest: VersionManifest, platform: HostPlatform, includeAssetObjects: Boolean = false): ResolvedVersion {
        return resolveWith(manifest, platform, includeAssetObjects, artifactStore::resolve)
    }

    /** Loads a version exclusively from already verified cache entries. No network access is possible. */
    fun resolveCached(manifest: VersionManifest, platform: HostPlatform, includeAssetObjects: Boolean = false): ResolvedVersion =
        resolveWith(manifest, platform, includeAssetObjects, artifactStore::verifyCached)

    fun resolveServerCached(manifest: VersionManifest): ResolvedArtifact = artifactStore.verifyCached(
        manifest.serverDownload.toRequest("Minecraft ${manifest.id} server", cacheLayout.versionArtifact(manifest.id, "server.jar")),
    )

    fun resolveClientCached(manifest: VersionManifest): ResolvedArtifact = artifactStore.verifyCached(
        manifest.clientDownload.toRequest("Minecraft ${manifest.id} client", cacheLayout.versionArtifact(manifest.id, "client.jar")),
    )

    /**
     * Loads only the verified inputs required to analyze/decompile the client. This deliberately
     * does not make an unrelated cached server, asset index, or logging file a prerequisite.
     */
    fun resolveClientInputsCached(manifest: VersionManifest, platform: HostPlatform): ResolvedClientInputs {
        val client = resolveClientCached(manifest)
        val libraries = resolveLibraries(manifest, platform, artifactStore::verifyCached)
        return ResolvedClientInputs(manifest, platform, cacheLayout.root, client, libraries)
    }

    private fun resolveWith(
        manifest: VersionManifest,
        platform: HostPlatform,
        includeAssetObjects: Boolean,
        resolveArtifact: (ArtifactRequest) -> ResolvedArtifact,
    ): ResolvedVersion {
        val client = resolveArtifact(
            manifest.clientDownload.toRequest("Minecraft ${manifest.id} client", cacheLayout.versionArtifact(manifest.id, "client.jar")),
        )
        val server = resolveArtifact(
            manifest.serverDownload.toRequest("Minecraft ${manifest.id} server", cacheLayout.versionArtifact(manifest.id, "server.jar")),
        )

        val libraries = resolveLibraries(manifest, platform, resolveArtifact)

        val resolvedAssetIndexFile = resolveArtifact(
            manifest.assetIndex.toRequest(
                "asset index ${manifest.assetIndex.id}",
                cacheLayout.assetIndex(manifest.assetIndex.id),
            ),
        )
        val assetIndexDocument = assetIndexParser.read(resolvedAssetIndexFile.path)
        val resolvedAssetIndex = ResolvedAssetIndex(manifest.assetIndex, resolvedAssetIndexFile, assetIndexDocument)

        val logging = manifest.logging.toSortedMap().mapValues { (name, configuration) ->
            val id = configuration.file.id ?: "$name-${configuration.file.sha1}.config"
            ResolvedLoggingConfiguration(
                name = name,
                metadata = configuration,
                file = resolveArtifact(
                    configuration.file.toRequest(
                        "logging configuration $name",
                        cacheLayout.loggingFile(id),
                    ),
                ),
            )
        }

        val assetObjects = if (includeAssetObjects) {
            AssetObjectResolver(cacheLayout, artifactStore, assetBaseUri).resolveAll(assetIndexDocument, resolveArtifact)
        } else {
            emptyMap()
        }

        return ResolvedVersion(
            manifest = manifest,
            platform = platform,
            cacheRoot = cacheLayout.root,
            client = client,
            server = server,
            libraries = libraries,
            assetIndex = resolvedAssetIndex,
            assetObjects = assetObjects,
            loggingConfigurations = logging,
        )
    }

    private fun resolveLibraries(
        manifest: VersionManifest,
        platform: HostPlatform,
        resolveArtifact: (ArtifactRequest) -> ResolvedArtifact,
    ): List<ResolvedLibrary> {
        val selectedLibraries = librarySelector.select(manifest.libraries, RuleContext(platform))
        return selectedLibraries.map { selected ->
            val artifacts = selected.artifacts.map { artifact ->
                val manifestPath = artifact.download.path
                    ?: throw ResolutionException(
                        "Library '${selected.library.name}' ${artifact.classifier ?: "artifact"} has no cache path in the manifest",
                    )
                ResolvedLibraryArtifact(
                    role = artifact.role,
                    classifier = artifact.classifier,
                    file = resolveArtifact(
                        artifact.download.toRequest(
                            "library ${selected.library.name}${artifact.classifier?.let { " ($it)" }.orEmpty()}",
                            cacheLayout.libraryArtifact(manifestPath),
                        ),
                    ),
                )
            }
            ResolvedLibrary(selected.library, artifacts)
        }
    }
}

private fun Download.toRequest(name: String, destination: java.nio.file.Path): ArtifactRequest = ArtifactRequest(
    name = name,
    uri = url,
    expectedSha1 = sha1,
    expectedSize = size,
    destination = destination,
)

private fun AssetIndex.toRequest(name: String, destination: java.nio.file.Path): ArtifactRequest = ArtifactRequest(
    name = name,
    uri = url,
    expectedSha1 = sha1,
    expectedSize = size,
    destination = destination,
)
