package dev.mcrecon.classfile

import dev.mcrecon.classfile.IndexModel.ArtifactIdentity
import dev.mcrecon.classfile.IndexModel.EntryKind
import dev.mcrecon.classfile.IndexModel.JarEntryIndex
import dev.mcrecon.classfile.IndexModel.JarIndex
import dev.mcrecon.compiler.DeterministicTreeHasher
import dev.mcrecon.compiler.TreeFingerprint
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

data class ClassDirectorySource(
    val minecraftVersion: String,
    val side: String,
    val artifactKind: String = "compiled-class-directory",
)

/**
 * Indexes a compiler class-output directory with the same ClassfileIndexer used for JARs.
 * Directory order, timestamps, permissions, and directory entries are not evidence.
 */
class ClassDirectoryIndexer(private val classfileIndexer: ClassfileIndexer = ClassfileIndexer()) {
    fun index(
        root: Path,
        source: ClassDirectorySource,
        treeFingerprint: TreeFingerprint? = null,
    ): JarIndex {
        val normalizedRoot = root.toAbsolutePath().normalize()
        if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw JarIndexingException("Class directory does not exist or is not a directory: $normalizedRoot")
        }

        val fingerprint = treeFingerprint ?: try {
            DeterministicTreeHasher.hash(normalizedRoot, CLASS_TREE_DOMAIN)
        } catch (exception: Exception) {
            throw JarIndexingException("Cannot fingerprint class directory: ${exception.message}", exception)
        }
        val paths = Files.walk(normalizedRoot).use { stream ->
            stream.filter { it != normalizedRoot }.map { path ->
                when {
                    Files.isSymbolicLink(path) -> throw JarIndexingException(
                        "Class directory contains a symbolic link: ${relative(normalizedRoot, path)}",
                    )
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> null
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> {
                        val relative = relative(normalizedRoot, path)
                        if (!relative.endsWith(".class")) {
                            throw JarIndexingException("Class directory contains a non-class file: $relative")
                        }
                        ClassPath(relative, path)
                    }
                    else -> throw JarIndexingException(
                        "Class directory contains an unsupported entry: ${relative(normalizedRoot, path)}",
                    )
                }
            }.filter { it != null }.map { checkNotNull(it) }.toList()
        }.sortedBy(ClassPath::entryPath)

        if (paths.size != fingerprint.fileCount) {
            throw JarIndexingException(
                "Class directory fingerprint contains ${fingerprint.fileCount} files but ${paths.size} classfiles were indexed",
            )
        }

        val entries = ArrayList<JarEntryIndex>(paths.size)
        val classes = ArrayList<IndexModel.ClassIndex>(paths.size)
        var totalSize = 0L
        for (classPath in paths) {
            val bytes = try {
                Files.readAllBytes(classPath.path)
            } catch (exception: Exception) {
                throw JarIndexingException("Failed to read class directory entry '${classPath.entryPath}': ${exception.message}", exception)
            }
            val hash = Sha256.bytes(bytes)
            totalSize += bytes.size
            entries += JarEntryIndex(classPath.entryPath, EntryKind.CLASS, bytes.size.toLong(), hash)
            try {
                classes += classfileIndexer.index(bytes, classPath.entryPath)
            } catch (exception: Exception) {
                throw JarIndexingException(
                    "Failed to index class directory entry '${classPath.entryPath}': ${exception.message}",
                    exception,
                )
            }
        }

        return JarIndex(
            IndexModel.SCHEMA_VERSION,
            ArtifactIdentity(
                source.minecraftVersion,
                source.side,
                source.artifactKind,
                totalSize,
                "",
                fingerprint.sha256,
            ),
            entries,
            classes,
            emptyList(),
        )
    }

    private fun relative(root: Path, path: Path): String {
        val relative = root.relativize(path.toAbsolutePath().normalize())
        if (relative.isAbsolute || relative.nameCount == 0 || relative.any { it.toString() == ".." }) {
            throw JarIndexingException("Unsafe class directory path: $path")
        }
        return relative.joinToString("/") { it.toString() }
    }

    private data class ClassPath(val entryPath: String, val path: Path)

    companion object {
        const val CLASS_TREE_DOMAIN = "mc-recon-class-tree-v1"
    }
}
