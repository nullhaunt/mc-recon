package dev.mcrecon.decompiler

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

data class SourceTreeFingerprint(val fileCount: Int, val sha256: String)

/**
 * Hashes a tree without consulting filesystem metadata. Paths use forward slashes and are sorted
 * by unsigned UTF-8 bytes. Each entry contributes its 64-bit path length, path, 64-bit byte length,
 * and exact bytes after a domain prefix.
 */
object SourceTreeHasher {
    private val DOMAIN = "mc-recon-source-tree-v1\u0000".toByteArray(StandardCharsets.US_ASCII)

    fun hash(root: Path): SourceTreeFingerprint {
        val normalizedRoot = root.toAbsolutePath().normalize()
        if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw DecompilationException("Raw source tree does not exist or is not a directory: $normalizedRoot")
        }
        val files = Files.walk(normalizedRoot).use { paths ->
            paths.filter { it != normalizedRoot }.map { path ->
                if (Files.isSymbolicLink(path)) {
                    throw DecompilationException("Raw source tree contains a symbolic link: ${relative(normalizedRoot, path)}")
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        throw DecompilationException("Raw source tree contains an unsupported entry: ${relative(normalizedRoot, path)}")
                    }
                    null
                } else {
                    TreeFile(relative(normalizedRoot, path), path)
                }
            }.filter { it != null }.map { checkNotNull(it) }.toList()
        }.sortedWith { left, right -> compareUnsigned(left.pathBytes, right.pathBytes) }

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN)
        for (file in files) {
            digest.update(longBytes(file.pathBytes.size.toLong()))
            digest.update(file.pathBytes)
            val size = Files.size(file.file)
            digest.update(longBytes(size))
            Files.newInputStream(file.file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return SourceTreeFingerprint(files.size, HexFormat.of().formatHex(digest.digest()))
    }

    private fun relative(root: Path, path: Path): String =
        root.relativize(path).joinToString("/") { it.toString() }

    private fun longBytes(value: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array()

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        val common = minOf(left.size, right.size)
        for (index in 0 until common) {
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    private data class TreeFile(val relativePath: String, val file: Path) {
        val pathBytes: ByteArray = relativePath.toByteArray(StandardCharsets.UTF_8)
    }
}
