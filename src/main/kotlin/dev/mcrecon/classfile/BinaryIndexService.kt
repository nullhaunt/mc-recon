package dev.mcrecon.classfile

import dev.mcrecon.manifest.VersionManifest
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.resolver.ResolvedArtifact
import dev.mcrecon.resolver.VersionResolver

enum class BinarySide(val cliName: String, val artifactKind: String) {
    CLIENT("client", "client"),
    SERVER("server", "server-distribution"),
}

class BinaryIndexService(
    private val cacheLayout: CacheLayout,
    private val resolver: VersionResolver = VersionResolver(cacheLayout),
    private val store: JarIndexStore = JarIndexStore(),
) {
    fun loadOrCreate(manifest: VersionManifest, side: BinarySide): StoredJarIndex {
        val artifact = when (side) {
            BinarySide.CLIENT -> resolver.resolveClientCached(manifest)
            BinarySide.SERVER -> resolver.resolveServerCached(manifest)
        }
        return store.loadOrCreate(
            artifact.path,
            cacheLayout.binaryIndex(manifest.id, side.cliName),
            artifact.source(manifest.id, side),
        )
    }

    private fun ResolvedArtifact.source(versionId: String, side: BinarySide): JarSource = JarSource(
        minecraftVersion = versionId,
        side = side.cliName,
        artifactKind = side.artifactKind,
        mojangSha1 = request.expectedSha1,
    )
}
