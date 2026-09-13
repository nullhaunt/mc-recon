package dev.mcrecon.runtime

import dev.mcrecon.resolver.ArtifactVerifier
import dev.mcrecon.resolver.LibraryArtifactRole
import dev.mcrecon.resolver.ResolvedLibraryArtifact
import dev.mcrecon.resolver.ResolvedVersion
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.zip.ZipFile

data class PreparedNatives(val directory: Path, val reused: Boolean, val extractedFiles: Int)

class NativePreparer {
    fun prepare(version: ResolvedVersion, nativeDirectory: Path): PreparedNatives {
        val sources = version.libraries.flatMap { library ->
            library.artifacts
                .filter { it.role == LibraryArtifactRole.NATIVE }
                .map { NativeSource(it, library.metadata.extract?.exclude.orEmpty()) }
        }
        val expectedFingerprint = fingerprint(version.manifest.id, version.platform.operatingSystem.manifestName, version.platform.architecture.canonicalName, sources)
        val parent = nativeDirectory.toAbsolutePath().normalize().parent
            ?: throw RuntimePreparationException("Native directory has no parent: $nativeDirectory")
        try {
            Files.createDirectories(parent)
            val lockPath = parent.resolve(".${nativeDirectory.fileName}.lock")
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val reusableCount = readyFileCount(nativeDirectory, expectedFingerprint)
                    if (reusableCount != null) return PreparedNatives(nativeDirectory, true, reusableCount)
                    return rebuild(nativeDirectory, expectedFingerprint, sources)
                }
            }
        } catch (exception: RuntimePreparationException) {
            throw exception
        } catch (exception: Exception) {
            throw RuntimePreparationException("Could not prepare native directory $nativeDirectory", exception)
        }
    }

    private fun rebuild(directory: Path, fingerprint: String, sources: List<NativeSource>): PreparedNatives {
        val parent = directory.parent
        val stage = parent.resolve(".${directory.fileName}.partial-${UUID.randomUUID()}")
        val backup = parent.resolve(".${directory.fileName}.backup-${UUID.randomUUID()}")
        try {
            Files.createDirectories(stage)
            val outputs = linkedMapOf<String, OutputIntegrity>()
            for (source in sources) extract(source, stage, outputs)
            listOf("java", "jna", "lwjgl", "netty").forEach { Files.createDirectories(stage.resolve(it)) }
            writeMarker(stage.resolve(MARKER), fingerprint, outputs)

            var hadExisting = false
            if (Files.exists(directory)) {
                move(directory, backup)
                hadExisting = true
            }
            try {
                move(stage, directory)
            } catch (exception: Exception) {
                if (hadExisting && !Files.exists(directory)) move(backup, directory)
                throw exception
            }
            if (Files.exists(backup)) deleteTree(backup, parent)
            return PreparedNatives(directory, false, outputs.size)
        } catch (exception: RuntimePreparationException) {
            throw exception
        } catch (exception: Exception) {
            throw RuntimePreparationException("Could not prepare native directory $directory", exception)
        } finally {
            if (Files.exists(stage)) deleteTree(stage, parent)
            if (Files.exists(backup) && Files.exists(directory)) deleteTree(backup, parent)
        }
    }

    private fun extract(source: NativeSource, stage: Path, outputs: MutableMap<String, OutputIntegrity>) {
        try {
            ZipFile(source.artifact.file.path.toFile()).use { archive ->
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val relative = validateEntry(entry.name)
                    if (entry.isDirectory || excluded(relative, source.exclusions)) continue
                    val destination = stage.resolve(relative).normalize()
                    if (!destination.startsWith(stage)) throw RuntimePreparationException("Native ZIP entry escapes output directory: ${entry.name}")
                    Files.createDirectories(destination.parent)
                    val bytes = archive.getInputStream(entry).use { it.readAllBytes() }
                    val integrity = OutputIntegrity(sha1(bytes), bytes.size.toLong())
                    val key = relative.toString().replace('\\', '/')
                    val previous = outputs[key]
                    if (previous != null) {
                        if (previous != integrity) {
                            throw RuntimePreparationException("Conflicting native entry '$key' from ${source.artifact.file.path}")
                        }
                        continue
                    }
                    Files.write(destination, bytes, StandardOpenOption.CREATE_NEW)
                    outputs[key] = integrity
                }
            }
        } catch (exception: RuntimePreparationException) {
            throw exception
        } catch (exception: Exception) {
            throw RuntimePreparationException("Could not extract native artifact ${source.artifact.file.path}", exception)
        }
    }

    private fun validateEntry(name: String): Path {
        if (name.isBlank() || '\\' in name || name.startsWith('/') || DRIVE_PREFIX.containsMatchIn(name)) {
            throw RuntimePreparationException("Unsafe native ZIP entry '$name'")
        }
        val path = Path.of(name).normalize()
        if (path.isAbsolute || path.nameCount == 0 || path.any { it.toString() == ".." } || name.split('/').any { it == ".." }) {
            throw RuntimePreparationException("Unsafe native ZIP entry '$name'")
        }
        return path
    }

    private fun excluded(path: Path, exclusions: List<String>): Boolean {
        val normalized = path.toString().replace('\\', '/')
        if (normalized.equals("META-INF", true) || normalized.startsWith("META-INF/", true)) return true
        return exclusions.any { exclusion ->
            val prefix = exclusion.replace('\\', '/').trimStart('/')
            normalized == prefix.trimEnd('/') || normalized.startsWith(if (prefix.endsWith('/')) prefix else "$prefix/")
        }
    }

    private fun readyFileCount(directory: Path, expectedFingerprint: String): Int? {
        val marker = directory.resolve(MARKER)
        if (!Files.isRegularFile(marker)) return null
        return try {
            val lines = Files.readAllLines(marker, StandardCharsets.UTF_8)
            if (lines.firstOrNull() != "format=$FORMAT" || lines.getOrNull(1) != "fingerprint=$expectedFingerprint") return null
            var count = 0
            for (line in lines.drop(2)) {
                val parts = line.split('\t')
                if (parts.size != 3) return null
                val file = directory.resolve(parts[0]).normalize()
                if (!file.startsWith(directory) || !Files.isRegularFile(file)) return null
                val actual = ArtifactVerifier.inspect(file)
                if (actual.sha1 != parts[1] || actual.size != parts[2].toLongOrNull()) return null
                count++
            }
            count
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMarker(path: Path, fingerprint: String, outputs: Map<String, OutputIntegrity>) {
        val text = buildString {
            appendLine("format=$FORMAT")
            appendLine("fingerprint=$fingerprint")
            outputs.toSortedMap().forEach { (name, integrity) -> appendLine("$name\t${integrity.sha1}\t${integrity.size}") }
        }
        Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
    }

    private fun fingerprint(version: String, os: String, arch: String, sources: List<NativeSource>): String {
        val material = buildString {
            appendLine("$FORMAT\t$version\t$os\t$arch")
            sources.forEach { source ->
                append(source.artifact.file.request.expectedSha1.lowercase()).append('\t')
                append(source.artifact.file.request.expectedSize).append('\t')
                append(source.artifact.classifier.orEmpty()).append('\t')
                appendLine(source.exclusions.joinToString("|"))
            }
        }.toByteArray(StandardCharsets.UTF_8)
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material))
    }

    private fun sha1(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes))

    private fun move(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun deleteTree(target: Path, allowedParent: Path) {
        val normalized = target.toAbsolutePath().normalize()
        val parent = allowedParent.toAbsolutePath().normalize()
        if (normalized.parent != parent || normalized == parent) {
            throw RuntimePreparationException("Refusing to remove native staging path outside $parent: $normalized")
        }
        Files.walk(normalized).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private data class NativeSource(val artifact: ResolvedLibraryArtifact, val exclusions: List<String>)
    private data class OutputIntegrity(val sha1: String, val size: Long)

    private companion object {
        const val FORMAT = 1
        const val MARKER = ".mc-recon-natives"
        val DRIVE_PREFIX = Regex("^[A-Za-z]:")
    }
}
