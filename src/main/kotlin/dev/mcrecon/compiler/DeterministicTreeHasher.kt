package dev.mcrecon.compiler

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

data class TreeFingerprint(val fileCount: Int, val sha256: String)

object DeterministicTreeHasher {
    fun hash(root: Path, domain: String): TreeFingerprint {
        val normalizedRoot = root.toAbsolutePath().normalize()
        if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw CompilationException("Tree does not exist or is not a directory: $normalizedRoot")
        }
        val files = Files.walk(normalizedRoot).use { paths ->
            paths.filter { it != normalizedRoot }.map { path ->
                when {
                    Files.isSymbolicLink(path) -> throw CompilationException("Tree contains a symbolic link: ${relative(normalizedRoot, path)}")
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> TreeFile(relative(normalizedRoot, path), path)
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> null
                    else -> throw CompilationException("Tree contains an unsupported entry: ${relative(normalizedRoot, path)}")
                }
            }.filter { it != null }.map { checkNotNull(it) }.toList()
        }.sortedWith { left, right -> compareUnsigned(left.pathBytes, right.pathBytes) }

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$domain\u0000".toByteArray(StandardCharsets.US_ASCII))
        files.forEach { file ->
            digest.update(longBytes(file.pathBytes.size.toLong()))
            digest.update(file.pathBytes)
            digest.update(longBytes(Files.size(file.file)))
            Files.newInputStream(file.file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return TreeFingerprint(files.size, HexFormat.of().formatHex(digest.digest()))
    }

    private fun relative(root: Path, path: Path): String = root.relativize(path).joinToString("/") { it.toString() }
    private fun longBytes(value: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array()
    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    private data class TreeFile(val relativePath: String, val file: Path) {
        val pathBytes = relativePath.toByteArray(StandardCharsets.UTF_8)
    }
}
