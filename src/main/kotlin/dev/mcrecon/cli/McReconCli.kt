package dev.mcrecon.cli

import dev.mcrecon.manifest.Download
import dev.mcrecon.manifest.ManifestException
import dev.mcrecon.manifest.ManifestParser
import dev.mcrecon.manifest.VersionManifest
import dev.mcrecon.manifest.rules.HostPlatformDetector
import dev.mcrecon.manifest.rules.RuleContext
import dev.mcrecon.manifest.rules.RuleEvaluator
import dev.mcrecon.manifest.rules.UnsupportedPlatformException
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
        if (arguments.size != 2 || arguments[0] != "inspect") {
            err.println(if (arguments.isEmpty()) "error: a command is required" else "error: expected 'inspect <manifest>'")
            printUsage(err)
            return 2
        }

        return try {
            val manifestPath = Path.of(arguments[1])
            val manifest = parser.read(manifestPath)
            printInspection(manifest, out)
            0
        } catch (exception: InvalidPathException) {
            fail(err, 2, "error: invalid manifest path: ${exception.input}")
        } catch (exception: ManifestException) {
            fail(err, 2, "error: ${exception.message}")
        } catch (exception: UnsupportedPlatformException) {
            fail(err, 2, "error: ${exception.message}")
        } catch (exception: Exception) {
            fail(err, 1, "internal error: ${exception.message ?: exception::class.simpleName}")
        }
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

    private fun printUsage(writer: PrintWriter) {
        writer.println("Usage: mc-recon inspect <version-manifest.json>")
        writer.flush()
    }

    private fun fail(writer: PrintWriter, exitCode: Int, message: String): Int {
        writer.println(message)
        writer.flush()
        return exitCode
    }
}
