package dev.mcrecon.resolver

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

class VerifiedArtifactStore(
    cacheRoot: Path,
    private val fetcher: JdkArtifactFetcher = JdkArtifactFetcher(),
) {
    private val root = cacheRoot.toAbsolutePath().normalize()
    private val partialDirectory = root.resolve("downloads").resolve("partial")
    private val lockDirectory = root.resolve("downloads").resolve("locks")

    fun resolve(request: ArtifactRequest): ResolvedArtifact {
        validateRequest(request)
        try {
            Files.createDirectories(request.destination.parent)
            Files.createDirectories(partialDirectory)
            Files.createDirectories(lockDirectory)
            val lockPath = lockDirectory.resolve("${lockKey(request.destination)}.lock")
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    return resolveWhileLocked(request)
                }
            }
        } catch (exception: ResolutionException) {
            throw exception
        } catch (exception: IOException) {
            throw ResolutionException("Filesystem failure while resolving '${request.name}' to ${request.destination}: ${exception.message}", exception)
        }
    }

    fun verifyCached(request: ArtifactRequest): ResolvedArtifact {
        validateRequest(request)
        try {
            if (!Files.exists(request.destination)) throw MissingArtifactException(request.name, request.destination)
            if (!Files.isRegularFile(request.destination)) {
                throw ResolutionException("Cache entry for '${request.name}' is not a regular file: ${request.destination}")
            }
            ArtifactVerifier.requireMatches(request)
            return ResolvedArtifact(request, request.destination, ArtifactOrigin.CACHE)
        } catch (exception: ResolutionException) {
            throw exception
        } catch (exception: IOException) {
            throw ResolutionException("Filesystem failure while verifying '${request.name}' at ${request.destination}: ${exception.message}", exception)
        }
    }

    private fun resolveWhileLocked(request: ArtifactRequest): ResolvedArtifact {
        var replacedCorruptEntry = false
        if (Files.exists(request.destination)) {
            if (!Files.isRegularFile(request.destination)) {
                throw ResolutionException("Cache entry for '${request.name}' is not a regular file: ${request.destination}")
            }
            if (ArtifactVerifier.inspect(request.destination).matches(request)) {
                return ResolvedArtifact(request, request.destination, ArtifactOrigin.CACHE)
            }
            Files.delete(request.destination)
            replacedCorruptEntry = true
        }

        val partial = Files.createTempFile(partialDirectory, "${request.expectedSha1}.", ".part")
        try {
            fetcher.fetch(request.name, request.uri, partial)
            val actual = ArtifactVerifier.inspect(partial)
            if (!actual.matches(request)) {
                throw IntegrityException(
                    artifactName = request.name,
                    expectedSha1 = request.expectedSha1,
                    actualSha1 = actual.sha1,
                    expectedSize = request.expectedSize,
                    actualSize = actual.size,
                    source = request.uri,
                    destination = request.destination,
                )
            }
            publish(partial, request.destination)
            return ResolvedArtifact(request, request.destination, ArtifactOrigin.DOWNLOAD, replacedCorruptEntry)
        } finally {
            Files.deleteIfExists(partial)
        }
    }

    private fun publish(partial: Path, destination: Path) {
        try {
            Files.move(
                partial,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun validateRequest(request: ArtifactRequest) {
        val destination = request.destination.toAbsolutePath().normalize()
        if (!destination.startsWith(root) || destination == root) {
            throw CachePathException("Artifact destination escapes cache root: ${request.destination}")
        }
        if (!SHA1.matches(request.expectedSha1)) {
            throw ResolutionException("Invalid expected SHA-1 for '${request.name}': ${request.expectedSha1}")
        }
        if (request.expectedSize < 0) {
            throw ResolutionException("Invalid expected size for '${request.name}': ${request.expectedSize}")
        }
        val scheme = request.uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            throw ResolutionException("Unsupported download URI scheme for '${request.name}': ${request.uri}")
        }
        if (request.uri.host.isNullOrBlank()) {
            throw ResolutionException("Invalid HTTP download URL for '${request.name}': ${request.uri}")
        }
    }

    private fun lockKey(destination: Path): String {
        val relativePath = root.relativize(destination.toAbsolutePath().normalize()).toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(relativePath.toByteArray(StandardCharsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }

    private companion object {
        val SHA1 = Regex("[0-9a-fA-F]{40}")
    }
}
