package dev.mcrecon.workspace

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipFile

data class ResourceExtractionResult(val files: Int, val excludedEntries: Int)

class WorkspaceResourceExtractor {
    fun extract(clientJar: Path, output: Path): ResourceExtractionResult {
        var files = 0
        var excluded = 0
        try {
            Files.createDirectories(output)
            ZipFile(clientJar.toFile()).use { archive ->
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val relative = validate(if (entry.isDirectory) entry.name.trimEnd('/') else entry.name)
                    if (entry.isDirectory || excluded(relative)) {
                        if (!entry.isDirectory) excluded++
                        continue
                    }
                    val destination = output.resolve(relative).normalize()
                    if (!destination.startsWith(output.toAbsolutePath().normalize())) {
                        throw WorkspaceException("Client resource entry escapes the workspace: ${entry.name}")
                    }
                    Files.createDirectories(destination.parent)
                    archive.getInputStream(entry).use { input ->
                        Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW).use(input::transferTo)
                    }
                    files++
                }
            }
            return ResourceExtractionResult(files, excluded)
        } catch (exception: WorkspaceException) {
            throw exception
        } catch (exception: Exception) {
            throw WorkspaceException("Could not extract client resources from $clientJar", exception)
        }
    }

    private fun validate(name: String): Path {
        if (name.isBlank() || '\\' in name || name.startsWith('/') || DRIVE_PREFIX.containsMatchIn(name)) {
            throw WorkspaceException("Unsafe client resource entry '$name'")
        }
        val segments = name.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            throw WorkspaceException("Unsafe client resource entry '$name'")
        }
        val path = Path.of(name).normalize()
        if (path.isAbsolute || path.nameCount == 0) throw WorkspaceException("Unsafe client resource entry '$name'")
        return path
    }

    private fun excluded(path: Path): Boolean {
        val normalized = path.joinToString("/") { it.toString() }
        if (normalized.endsWith(".class", ignoreCase = true)) return true
        if (!normalized.startsWith("META-INF/", ignoreCase = true)) return false
        val name = normalized.substringAfterLast('/')
        return name.equals("MANIFEST.MF", ignoreCase = true) ||
            name.equals("INDEX.LIST", ignoreCase = true) ||
            name.endsWith(".SF", ignoreCase = true) ||
            name.endsWith(".RSA", ignoreCase = true) ||
            name.endsWith(".DSA", ignoreCase = true) ||
            name.endsWith(".EC", ignoreCase = true) ||
            name.startsWith("SIG-", ignoreCase = true)
    }

    private companion object {
        val DRIVE_PREFIX = Regex("^[A-Za-z]:")
    }
}
