package dev.mcrecon.classfile

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

internal object Sha256 {
    fun bytes(bytes: ByteArray): String = digest().run {
        update(bytes)
        hex(digest())
    }

    fun text(value: String): String = bytes(value.toByteArray(StandardCharsets.UTF_8))

    fun file(path: Path): String = Files.newInputStream(path).use(::stream)

    fun stream(input: InputStream): String {
        val digest = digest()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return hex(digest.digest())
    }

    private fun digest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    private fun hex(value: ByteArray): String = HexFormat.of().formatHex(value)
}

/** Collision-safe canonical token stream used as the input to semantic fingerprints. */
internal class CanonicalForm(private val domain: String) {
    private val value = StringBuilder().append(domain.length).append(':').append(domain).append(';')

    fun token(name: String, content: String?): CanonicalForm = apply {
        value.append(name.length).append(':').append(name).append('=')
        if (content == null) {
            value.append('-')
        } else {
            value.append(content.length).append(':').append(content)
        }
        value.append(';')
    }

    fun token(name: String, content: Int): CanonicalForm = token(name, content.toString())

    fun token(name: String, content: Long): CanonicalForm = token(name, content.toString())

    fun hash(): String = Sha256.text(value.toString())

    fun encoded(): String = value.toString()
}
