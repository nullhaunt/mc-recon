package dev.mcrecon.resolver.assets

import dev.mcrecon.resolver.ArtifactRequest
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.resolver.ResolvedArtifact
import dev.mcrecon.resolver.VerifiedArtifactStore
import java.net.URI

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
