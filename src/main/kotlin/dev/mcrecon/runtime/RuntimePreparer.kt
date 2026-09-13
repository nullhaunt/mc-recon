package dev.mcrecon.runtime

import dev.mcrecon.launcher.ClientLaunchBuilder
import dev.mcrecon.launcher.ClientLaunchOptions
import dev.mcrecon.launcher.ClientLaunchPreparation
import dev.mcrecon.launcher.LaunchException
import dev.mcrecon.launcher.LaunchSpecification
import dev.mcrecon.launcher.ServerLaunchBuilder
import dev.mcrecon.launcher.ServerLaunchOptions
import dev.mcrecon.manifest.VersionManifest
import dev.mcrecon.manifest.rules.HostPlatform
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.resolver.VersionResolver
import java.nio.file.Path

data class RuntimeSelectionOptions(
    val javaExecutable: Path? = null,
    val allowJavaVersionMismatch: Boolean = false,
)

class RuntimePreparer(
    private val cacheLayout: CacheLayout,
    private val javaSelector: JavaRuntimeSelector = JavaRuntimeSelector(),
) {
    fun prepareClient(
        manifest: VersionManifest,
        platform: HostPlatform,
        runtime: RuntimeSelectionOptions,
        launch: ClientLaunchOptions,
    ): ClientLaunchPreparation {
        val resolved = VersionResolver(cacheLayout).resolveCached(manifest, platform)
        val java = javaSelector.select(manifest.javaVersion.majorVersion, runtime.javaExecutable, runtime.allowJavaVersionMismatch)
        return ClientLaunchBuilder(cacheLayout).build(resolved, java, launch)
    }

    fun prepareServer(
        manifest: VersionManifest,
        runtime: RuntimeSelectionOptions,
        launch: ServerLaunchOptions,
    ): LaunchSpecification {
        val server = VersionResolver(cacheLayout).resolveServerCached(manifest)
        val java = javaSelector.select(manifest.javaVersion.majorVersion, runtime.javaExecutable, runtime.allowJavaVersionMismatch)
        return ServerLaunchBuilder().build(server, java, launch)
    }

    fun requireClientAssets(preparation: ClientLaunchPreparation, manifestPath: Path) {
        if (preparation.assets.complete) return
        throw LaunchException(
            "Client runtime is missing ${preparation.assets.missingObjects} asset objects. " +
                "Resolve them first with: mc-recon resolve $manifestPath --assets",
        )
    }
}
