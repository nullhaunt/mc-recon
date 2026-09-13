package dev.mcrecon.classfile

import dev.mcrecon.classfile.IndexModel.ArtifactIdentity
import dev.mcrecon.classfile.IndexModel.EntryKind
import dev.mcrecon.classfile.IndexModel.JarEntryIndex
import dev.mcrecon.classfile.IndexModel.JarIndex
import dev.mcrecon.classfile.IndexModel.ResourceIndex
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

data class JarSource(
    val minecraftVersion: String,
    val side: String,
    val artifactKind: String,
    val mojangSha1: String,
)

class JarIndexingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Indexes arbitrary JARs; Minecraft provenance is supplied separately by the caller. */
class JarIndexer(private val classfileIndexer: ClassfileIndexer = ClassfileIndexer()) {
    fun artifactIdentity(path: Path, source: JarSource): ArtifactIdentity {
        if (!Files.isRegularFile(path)) throw JarIndexingException("JAR does not exist or is not a regular file: $path")
        return try {
            ArtifactIdentity(
                source.minecraftVersion,
                source.side,
                source.artifactKind,
                Files.size(path),
                source.mojangSha1.lowercase(),
                Sha256.file(path),
            )
        } catch (exception: IOException) {
            throw JarIndexingException("cannot read JAR identity for $path: ${exception.message}", exception)
        }
    }

    fun index(path: Path, source: JarSource, identity: ArtifactIdentity = artifactIdentity(path, source)): JarIndex {
        val entries = mutableListOf<JarEntryIndex>()
        val classes = mutableListOf<IndexModel.ClassIndex>()
        val resources = mutableListOf<ResourceIndex>()
        val seenPaths = mutableSetOf<String>()

        try {
            JarFile(path.toFile(), false).use { jar ->
                val enumeration = jar.entries()
                while (enumeration.hasMoreElements()) {
                    val entry = enumeration.nextElement()
                    val entryPath = entry.name
                    if (!seenPaths.add(entryPath)) {
                        throw JarIndexingException("JAR $path contains duplicate entry path '$entryPath'")
                    }
                    when {
                        entry.isDirectory -> {
                            entries += JarEntryIndex(entryPath, EntryKind.DIRECTORY, 0, Sha256.bytes(ByteArray(0)))
                        }
                        entryPath.endsWith(".class") -> {
                            val bytes = jar.getInputStream(entry).use { it.readAllBytes() }
                            val entryHash = Sha256.bytes(bytes)
                            entries += JarEntryIndex(entryPath, EntryKind.CLASS, bytes.size.toLong(), entryHash)
                            try {
                                classes += classfileIndexer.index(bytes, entryPath)
                            } catch (exception: Exception) {
                                throw JarIndexingException(
                                    "Failed to index JAR $path entry '$entryPath': ${exception.message}",
                                    exception,
                                )
                            }
                        }
                        else -> {
                            val digestAndSize = jar.getInputStream(entry).use(::hashAndSize)
                            entries += JarEntryIndex(entryPath, EntryKind.RESOURCE, digestAndSize.size, digestAndSize.hash)
                            resources += ResourceIndex(entryPath, digestAndSize.size, digestAndSize.hash)
                        }
                    }
                }
            }
        } catch (exception: JarIndexingException) {
            throw exception
        } catch (exception: Exception) {
            throw JarIndexingException("Failed to index JAR $path: ${exception.message}", exception)
        }

        entries.sortBy(JarEntryIndex::path)
        classes.sortWith(compareBy(IndexModel.ClassIndex::entryPath, IndexModel.ClassIndex::binaryName))
        resources.sortBy(ResourceIndex::path)
        return JarIndex(IndexModel.SCHEMA_VERSION, identity, entries, classes, resources)
    }

    private fun hashAndSize(input: java.io.InputStream): DigestAndSize {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var size = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            size += read
        }
        return DigestAndSize(java.util.HexFormat.of().formatHex(digest.digest()), size)
    }

    private data class DigestAndSize(val hash: String, val size: Long)
}
