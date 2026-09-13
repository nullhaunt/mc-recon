package dev.mcrecon.classfile

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import dev.mcrecon.classfile.IndexModel.JarIndex
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class StoredJarIndex(val index: JarIndex, val reused: Boolean, val path: Path)

class JarIndexStore(
    private val indexer: JarIndexer = JarIndexer(),
    private val mapper: ObjectMapper = deterministicObjectMapper(),
) {
    fun loadOrCreate(jarPath: Path, indexPath: Path, source: JarSource): StoredJarIndex {
        val identity = indexer.artifactIdentity(jarPath, source)
        loadValid(indexPath, identity)?.let { return StoredJarIndex(it, true, indexPath) }
        val generated = indexer.index(jarPath, source, identity)
        write(indexPath, generated)
        return StoredJarIndex(generated, false, indexPath)
    }

    private fun loadValid(path: Path, identity: IndexModel.ArtifactIdentity): JarIndex? {
        if (!Files.isRegularFile(path)) return null
        val candidate = runCatching {
            Files.newInputStream(path).use { input ->
                GZIPInputStream(input).use { gzip -> mapper.readValue(gzip, JarIndex::class.java) }
            }
        }.getOrNull() ?: return null
        return candidate.takeIf {
            it.schemaVersion() == IndexModel.SCHEMA_VERSION && it.artifact() == identity &&
                it.entries() != null && it.classes() != null && it.resources() != null
        }
    }

    private fun write(path: Path, index: JarIndex) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            Files.newOutputStream(temporary).use { output ->
                GZIPOutputStream(output).use { gzip ->
                    mapper.writerWithDefaultPrettyPrinter().writeValue(gzip, index)
                }
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        fun deterministicObjectMapper(): ObjectMapper = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build()
    }
}
