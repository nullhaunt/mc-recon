package dev.mcrecon.resolver

import java.nio.file.Path
import dev.mcrecon.manifest.rules.HostPlatform

class CacheLayout(cacheRoot: Path) {
    val root: Path = cacheRoot.toAbsolutePath().normalize()

    fun versionArtifact(versionId: String, fileName: String): Path =
        safeResolve(root.resolve("versions"), listOf(safeSegment(versionId, "version ID"), safeSegment(fileName, "version artifact name")))

    fun libraryArtifact(manifestPath: String): Path {
        if (manifestPath.isBlank()) throw CachePathException("Library artifact path must not be blank")
        if ('\\' in manifestPath) throw CachePathException("Unsafe library artifact path '$manifestPath': backslashes are not allowed")
        if (manifestPath.startsWith('/') || DRIVE_PREFIX.containsMatchIn(manifestPath)) {
            throw CachePathException("Unsafe library artifact path '$manifestPath': absolute paths are not allowed")
        }
        val segments = manifestPath.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            throw CachePathException("Unsafe library artifact path '$manifestPath': path traversal is not allowed")
        }
        return safeResolve(root.resolve("libraries"), segments)
    }

    fun assetIndex(indexId: String): Path =
        safeResolve(root.resolve("assets").resolve("indexes"), listOf("${safeSegment(indexId, "asset index ID")}.json"))

    fun assetObject(hash: String): Path {
        val normalized = hash.lowercase()
        if (!SHA1.matches(normalized)) throw CachePathException("Invalid asset object SHA-1 '$hash'")
        return safeResolve(root.resolve("assets").resolve("objects"), listOf(normalized.take(2), normalized))
    }

    fun loggingFile(id: String): Path =
        safeResolve(root.resolve("logging"), listOf(safeSegment(id, "logging configuration ID")))

    fun binaryIndex(versionId: String, side: String): Path = safeResolve(
        root.resolve("indexes"),
        listOf(safeSegment(versionId, "version ID"), "${safeSegment(side, "index side")}.json.gz"),
    )

    fun reconstructionDirectory(versionId: String, side: String): Path = safeResolve(
        root.resolve("reconstruction"),
        listOf(safeSegment(versionId, "version ID"), safeSegment(side, "reconstruction side")),
    )

    fun rawSourceDirectory(versionId: String, side: String): Path =
        reconstructionDirectory(versionId, side).resolve("raw-source")

    fun decompilationProvenance(versionId: String, side: String): Path =
        reconstructionDirectory(versionId, side).resolve("decompile.json")

    fun reconstructedSourceDirectory(versionId: String, side: String): Path =
        reconstructionDirectory(versionId, side).resolve("reconstructed-source")

    fun reconstructionProvenance(versionId: String, side: String): Path =
        reconstructionDirectory(versionId, side).resolve("reconstruction.json")

    fun compiledClassesDirectory(versionId: String, side: String): Path =
        reconstructionDirectory(versionId, side).resolve("classes")

    fun compilationReport(versionId: String, side: String): Path =
        reconstructionDirectory(versionId, side).resolve("compile-report.json")

    fun rawCompilationReport(versionId: String, side: String): Path =
        reconstructionDirectory(versionId, side).resolve("raw-compile-report.json")

    fun verificationReport(versionId: String, side: String): Path =
        reconstructionDirectory(versionId, side).resolve("verification.json")

    fun compilerProfilesDirectory(versionId: String, side: String): Path =
        reconstructionDirectory(versionId, side).resolve("compiler-profiles")

    fun compilerProfileDirectory(versionId: String, side: String, profileId: String): Path = safeResolve(
        compilerProfilesDirectory(versionId, side),
        listOf(safeSegment(profileId, "compiler profile ID")),
    )

    fun compilerProfileClasses(versionId: String, side: String, profileId: String): Path =
        compilerProfileDirectory(versionId, side, profileId).resolve("classes")

    fun compilerProfileCompilationReport(versionId: String, side: String, profileId: String): Path =
        compilerProfileDirectory(versionId, side, profileId).resolve("compile-report.json")

    fun compilerProfileVerificationReport(versionId: String, side: String, profileId: String): Path =
        compilerProfileDirectory(versionId, side, profileId).resolve("verification.json")

    fun toolchainProbeReport(versionId: String, side: String): Path =
        compilerProfilesDirectory(versionId, side).resolve("toolchain-probe.json")

    fun assetsRoot(): Path = root.resolve("assets")

    fun runtimeDirectory(versionId: String, platform: HostPlatform): Path = safeResolve(
        root.resolve("runtime"),
        listOf(
            safeSegment(versionId, "version ID"),
            safeSegment("${platform.operatingSystem.manifestName}-${platform.architecture.canonicalName}", "platform"),
        ),
    )

    fun nativeDirectory(versionId: String, platform: HostPlatform): Path =
        runtimeDirectory(versionId, platform).resolve("natives")

    fun defaultGameDirectory(versionId: String, platform: HostPlatform, side: String): Path =
        safeResolve(runtimeDirectory(versionId, platform).resolve("run"), listOf(safeSegment(side, "launch side")))

    private fun safeResolve(base: Path, segments: List<String>): Path {
        val normalizedBase = base.toAbsolutePath().normalize()
        val result = segments.fold(normalizedBase, Path::resolve).normalize()
        if (!result.startsWith(normalizedBase) || result == normalizedBase) {
            throw CachePathException("Resolved cache path escapes $normalizedBase")
        }
        return result
    }

    private fun safeSegment(value: String, description: String): String {
        if (!SAFE_SEGMENT.matches(value) || value == "." || value == "..") {
            throw CachePathException("Unsafe $description '$value'")
        }
        return value
    }

    private companion object {
        val SHA1 = Regex("[0-9a-f]{40}")
        val DRIVE_PREFIX = Regex("^[A-Za-z]:")
        val SAFE_SEGMENT = Regex("[A-Za-z0-9._-]+")
    }
}
