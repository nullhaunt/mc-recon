package dev.mcrecon.resolver

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

data class FileIntegrity(val sha1: String, val size: Long) {
    fun matches(request: ArtifactRequest): Boolean =
        sha1.equals(request.expectedSha1, ignoreCase = true) && size == request.expectedSize
}

object ArtifactVerifier {
    fun inspect(path: Path): FileIntegrity {
        val digest = MessageDigest.getInstance("SHA-1")
        var size = 0L
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                size += read
            }
        }
        return FileIntegrity(HexFormat.of().formatHex(digest.digest()), size)
    }

    fun requireMatches(request: ArtifactRequest, path: Path = request.destination): FileIntegrity {
        val actual = inspect(path)
        if (!actual.matches(request)) {
            throw IntegrityException(
                artifactName = request.name,
                expectedSha1 = request.expectedSha1,
                actualSha1 = actual.sha1,
                expectedSize = request.expectedSize,
                actualSize = actual.size,
                source = request.uri,
                destination = path,
            )
        }
        return actual
    }
}
