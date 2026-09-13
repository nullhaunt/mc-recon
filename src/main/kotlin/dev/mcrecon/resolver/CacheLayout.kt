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
