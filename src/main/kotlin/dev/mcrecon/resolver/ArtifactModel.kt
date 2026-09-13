package dev.mcrecon.resolver

import java.net.URI
import java.nio.file.Path

data class ArtifactRequest(
    val name: String,
    val uri: URI,
    val expectedSha1: String,
    val expectedSize: Long,
    val destination: Path,
)

enum class ArtifactOrigin {
    CACHE,
    DOWNLOAD,
}

data class ResolvedArtifact(
    val request: ArtifactRequest,
    val path: Path,
    val origin: ArtifactOrigin,
    val replacedCorruptEntry: Boolean = false,
)

data class ResolutionSummary(
    val verifiedArtifacts: Int,
    val downloadedArtifacts: Int,
    val cachedArtifacts: Int,
    val replacedCorruptArtifacts: Int,
    val bytesDownloaded: Long,
)

open class ResolutionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class IntegrityException(
    artifactName: String,
    expectedSha1: String,
    actualSha1: String,
    expectedSize: Long,
    actualSize: Long,
    source: URI,
    destination: Path,
) : ResolutionException(
    buildString {
        append("Integrity verification failed for '$artifactName': ")
        append("expected SHA-1 $expectedSha1 and size $expectedSize, ")
        append("but received SHA-1 $actualSha1 and size $actualSize ")
        append("from $source (cache path $destination)")
    },
)

class CachePathException(message: String) : ResolutionException(message)
