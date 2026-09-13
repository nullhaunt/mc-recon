package dev.mcrecon.cli

import dev.mcrecon.manifest.Download
import dev.mcrecon.manifest.ManifestException
import dev.mcrecon.manifest.ManifestParser
import dev.mcrecon.manifest.VersionManifest
import dev.mcrecon.manifest.rules.HostPlatformDetector
import dev.mcrecon.manifest.rules.RuleContext
import dev.mcrecon.manifest.rules.RuleEvaluator
import dev.mcrecon.manifest.rules.UnsupportedPlatformException
import dev.mcrecon.resolver.ArtifactOrigin
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.resolver.LibraryArtifactRole
import dev.mcrecon.resolver.ResolutionException
import dev.mcrecon.resolver.ResolvedArtifact
import dev.mcrecon.resolver.ResolvedVersion
import dev.mcrecon.resolver.VersionResolver
import java.io.PrintWriter
import java.nio.file.InvalidPathException
import java.nio.file.Path

class McReconCli(
    private val parser: ManifestParser = ManifestParser(),
    private val platformDetector: HostPlatformDetector = HostPlatformDetector(),
) {
    fun run(arguments: Array<String>, out: PrintWriter, err: PrintWriter): Int {
        if (arguments.contentEquals(arrayOf("--help")) || arguments.contentEquals(arrayOf("-h"))) {
            printUsage(out)
            return 0
        }

        return try {
            when (arguments.firstOrNull()) {
                "inspect" -> runInspect(arguments, out)
                "resolve" -> runResolve(arguments, out)
                null -> throw CliUsageException("a command is required")
                else -> throw CliUsageException("unknown command '${arguments[0]}'")
            }
        } catch (exception: CliUsageException) {
            err.println("error: ${exception.message}")
            printUsage(err)
            2
        } catch (exception: InvalidPathException) {
            fail(err, 2, "error: invalid manifest path: ${exception.input}")
        } catch (exception: ManifestException) {
            fail(err, 2, "error: ${exception.message}")
        } catch (exception: ResolutionException) {
            fail(err, 2, "error: ${exception.message}")
        } catch (exception: UnsupportedPlatformException) {
            fail(err, 2, "error: ${exception.message}")
        } catch (exception: Exception) {
            fail(err, 1, "internal error: ${exception.message ?: exception::class.simpleName}")
        }
    }

    private fun runInspect(arguments: Array<String>, out: PrintWriter): Int {
        if (arguments.size != 2) throw CliUsageException("inspect requires exactly one manifest path")
        val manifest = parser.read(Path.of(arguments[1]))
        printInspection(manifest, out)
        return 0
    }

    private fun runResolve(arguments: Array<String>, out: PrintWriter): Int {
        if (arguments.size < 2) throw CliUsageException("resolve requires a manifest path")
        var cacheDirectory = Path.of(".mc-recon")
        var includeAssets = false
        var cacheSpecified = false
        var index = 2
        while (index < arguments.size) {
            when (arguments[index]) {
                "--assets" -> {
                    if (includeAssets) throw CliUsageException("--assets was specified more than once")
                    includeAssets = true
                    index++
                }
                "--cache-dir" -> {
                    if (cacheSpecified) throw CliUsageException("--cache-dir was specified more than once")
                    if (index + 1 >= arguments.size) throw CliUsageException("--cache-dir requires a path")
                    cacheDirectory = Path.of(arguments[index + 1])
                    cacheSpecified = true
                    index += 2
                }
                else -> throw CliUsageException("unknown resolve option '${arguments[index]}'")
            }
        }

        val manifest = parser.read(Path.of(arguments[1]))
        val platform = platformDetector.detect()
        val resolved = VersionResolver(CacheLayout(cacheDirectory)).resolve(manifest, platform, includeAssets)
        printResolution(resolved, includeAssets, out)
        return 0
    }

    private fun printInspection(manifest: VersionManifest, out: PrintWriter) {
        val platform = platformDetector.detect()
        val context = RuleContext(platform)
        val applicableLibraries = manifest.libraries.count { RuleEvaluator.isAllowed(it.rules, context) }

        out.println("Minecraft ${manifest.id}")
        out.println()
        out.println("Type:           ${manifest.type.manifestName}")
        out.println("Java:           ${manifest.javaVersion.majorVersion}")
        out.println("Java component: ${manifest.javaVersion.component}")
        out.println()
        printDownload("Client", manifest.clientDownload, out)
        printDownload("Server", manifest.serverDownload, out)
        out.println("Assets:")
        out.println("  index:        ${manifest.assetIndex.id}")
        out.println("  SHA-1:        ${manifest.assetIndex.sha1}")
        out.println("  Size:         ${formatSize(manifest.assetIndex.size)}")
        out.println("  Total size:   ${formatSize(manifest.assetIndex.totalSize)}")
        out.println("  URL:          ${manifest.assetIndex.url}")
        out.println()
        out.println("Libraries:")
        out.println("  total:        ${manifest.libraries.size}")
        out.println("  applicable:   $applicableLibraries")
        out.println("  platform:     ${platform.operatingSystem.manifestName}/${platform.architecture.canonicalName} ${platform.version}")
        out.println()
        out.println("Client main:")
        out.println("  ${manifest.mainClass}")
        out.println()
        out.println("Release time:")
        out.println("  ${manifest.releaseTime}")
        out.flush()
    }

    private fun printDownload(name: String, download: Download, out: PrintWriter) {
        out.println("$name:")
        out.println("  SHA-1:        ${download.sha1}")
        out.println("  Size:         ${formatSize(download.size)}")
        out.println("  URL:          ${download.url}")
        out.println()
    }

    private fun formatSize(size: Long): String = "%,d bytes".format(size)

    private fun printResolution(resolved: ResolvedVersion, includedAssets: Boolean, out: PrintWriter) {
        val standardLibraries = resolved.libraries.sumOf { library ->
            library.artifacts.count { it.role == LibraryArtifactRole.STANDARD }
        }
        out.println(
            "Resolved Minecraft ${resolved.manifest.id} for " +
                "${resolved.platform.operatingSystem.manifestName}/${resolved.platform.architecture.canonicalName}",
        )
        out.println()
        out.println("Client:                 ${originLabel(resolved.client)}")
        out.println("Server:                 ${originLabel(resolved.server)}")
        out.println("Libraries applicable:  ${resolved.libraries.size}")
        out.println("  standard artifacts:  $standardLibraries")
        out.println("  classifiers/natives: ${resolved.classifierAndNativeArtifacts.size}")
        out.println("Asset index:            ${originLabel(resolved.assetIndex.file)} (${resolved.assetIndex.document.objects.size} objects)")
        if (includedAssets) {
            val uniqueAssets = resolved.assetObjects.values.distinctBy { it.path }
            out.println("Asset objects:          ${describeArtifacts(uniqueAssets)}")
        } else {
            out.println("Asset objects:          not requested (use --assets)")
        }
        out.println(
            "Logging configuration:  " +
                if (resolved.loggingConfigurations.isEmpty()) "not present"
                else describeArtifacts(resolved.loggingConfigurations.values.map { it.file }),
        )
        out.println()
        out.println("Verified artifacts:     ${resolved.summary.verifiedArtifacts}")
        out.println("Downloaded:             ${resolved.summary.downloadedArtifacts}")
        out.println("Cached:                 ${resolved.summary.cachedArtifacts}")
        if (resolved.summary.replacedCorruptArtifacts > 0) {
            out.println("Corrupt entries replaced: ${resolved.summary.replacedCorruptArtifacts}")
        }
        out.println("Bytes downloaded:       ${formatSize(resolved.summary.bytesDownloaded)}")
        out.println()
        out.println("Cache:")
        out.println("  ${resolved.cacheRoot}")
        out.flush()
    }

    private fun originLabel(artifact: ResolvedArtifact): String = when {
        artifact.replacedCorruptEntry -> "downloaded (replaced corrupt cache entry)"
        artifact.origin == ArtifactOrigin.CACHE -> "cached"
        else -> "downloaded"
    }

    private fun describeArtifacts(artifacts: Collection<ResolvedArtifact>): String {
        val cached = artifacts.count { it.origin == ArtifactOrigin.CACHE }
        val downloaded = artifacts.size - cached
        return "${artifacts.size} verified ($cached cached, $downloaded downloaded)"
    }

    private fun printUsage(writer: PrintWriter) {
        writer.println("Usage:")
        writer.println("  mc-recon inspect <version-manifest.json>")
        writer.println("  mc-recon resolve <version-manifest.json> [--cache-dir <path>] [--assets]")
        writer.flush()
    }

    private fun fail(writer: PrintWriter, exitCode: Int, message: String): Int {
        writer.println(message)
        writer.flush()
        return exitCode
    }
}

private class CliUsageException(message: String) : RuntimeException(message)
