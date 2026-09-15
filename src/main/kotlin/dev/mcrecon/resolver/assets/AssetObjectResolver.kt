package dev.mcrecon.resolver.assets

import dev.mcrecon.resolver.ArtifactRequest
import dev.mcrecon.resolver.ArtifactVerifier
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.resolver.ResolvedArtifact
import dev.mcrecon.resolver.VerifiedArtifactStore
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class AssetObjectResolver(
    private val cacheLayout: CacheLayout,
    private val artifactStore: VerifiedArtifactStore,
    assetBaseUri: URI = URI("https://resources.download.minecraft.net/"),
) {
    private val baseUri = URI(assetBaseUri.toString().let { if (it.endsWith('/')) it else "$it/" })

    fun resolveAll(
        index: AssetIndexDocument,
        resolveArtifact: (ArtifactRequest) -> ResolvedArtifact = artifactStore::resolve,
    ): Map<String, ResolvedArtifact> {
        val byHash = mutableMapOf<String, ResolvedArtifact>()
        val byLogicalName = linkedMapOf<String, ResolvedArtifact>()
        for ((logicalName, asset) in index.objects.toSortedMap()) {
            val resolved = byHash.getOrPut(asset.hash) {
                resolveArtifact(request(logicalName, asset.hash, asset.size))
            }
            byLogicalName[logicalName] = resolved
        }
        return byLogicalName
    }

    fun availability(index: AssetIndexDocument): AssetAvailability {
        val unique = index.objects.entries.distinctBy { it.value.hash }
        var available = 0
        val missing = mutableListOf<String>()
        for ((logicalName, asset) in unique) {
            try {
                artifactStore.verifyCached(request(logicalName, asset.hash, asset.size))
                available++
            } catch (_: dev.mcrecon.resolver.MissingArtifactException) {
                missing += logicalName
            }
        }
        return AssetAvailability(unique.size, available, missing)
    }

    /**
     * Materializes the identity-specific assets root expected by Minecraft without duplicating
     * the shared content-addressed object payload when hard links are supported.
     */
    fun prepareRuntimeView(index: AssetIndexDocument, runtimeRoot: Path): AssetAvailability {
        val availability = availability(index)
        if (!availability.complete) return availability

        index.objects.entries.distinctBy { it.value.hash }.sortedBy { it.value.hash }.forEach { (logicalName, asset) ->
            val request = request(logicalName, asset.hash, asset.size)
            val source = request.destination
            val destination = runtimeRoot.resolve("objects/${asset.hash.take(2)}/${asset.hash}")
            Files.createDirectories(destination.parent)
            if (Files.exists(destination)) {
                val valid = runCatching {
                    Files.isSameFile(source, destination) || ArtifactVerifier.inspect(destination).matches(request)
                }.getOrDefault(false)
                if (valid) return@forEach
                Files.delete(destination)
            }
            try {
                Files.createLink(destination, source)
            } catch (_: UnsupportedOperationException) {
                Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES)
            } catch (_: IOException) {
                Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
        return availability
    }

    private fun request(logicalName: String, hash: String, size: Long) = ArtifactRequest(
        name = "asset $logicalName",
        uri = baseUri.resolve("${hash.take(2)}/$hash"),
        expectedSha1 = hash,
        expectedSize = size,
        destination = cacheLayout.assetObject(hash),
    )
}

data class AssetAvailability(
    val expectedObjects: Int,
    val availableObjects: Int,
    val missingLogicalNames: List<String>,
) {
    val missingObjects: Int get() = expectedObjects - availableObjects
    val complete: Boolean get() = missingObjects == 0
}
