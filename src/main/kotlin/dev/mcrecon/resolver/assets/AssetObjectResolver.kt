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

    fun resolveAll(index: AssetIndexDocument): Map<String, ResolvedArtifact> {
        val byHash = mutableMapOf<String, ResolvedArtifact>()
        val byLogicalName = linkedMapOf<String, ResolvedArtifact>()
        for ((logicalName, asset) in index.objects.toSortedMap()) {
            val resolved = byHash.getOrPut(asset.hash) {
                artifactStore.resolve(
                    ArtifactRequest(
                        name = "asset $logicalName",
                        uri = baseUri.resolve("${asset.hash.take(2)}/${asset.hash}"),
                        expectedSha1 = asset.hash,
                        expectedSize = asset.size,
                        destination = cacheLayout.assetObject(asset.hash),
                    ),
                )
            }
            byLogicalName[logicalName] = resolved
        }
        return byLogicalName
    }
}
