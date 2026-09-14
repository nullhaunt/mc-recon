package dev.mcrecon.workspace

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceResourceExtractorTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `retains resource bytes and excludes classes manifest and signatures`() {
        val jar = jar(
            "META-INF/" to null,
            "pkg/Test.class" to byteArrayOf(1),
            "assets/data.bin" to byteArrayOf(0, 13, 10, -1),
            "META-INF/MANIFEST.MF" to "manifest".toByteArray(),
            "META-INF/TEST.SF" to "sf".toByteArray(),
            "META-INF/TEST.RSA" to "rsa".toByteArray(),
        )
        val output = temporaryDirectory.resolve("resources")

        WorkspaceResourceExtractor().extract(jar, output)

        assertContentEquals(byteArrayOf(0, 13, 10, -1), Files.readAllBytes(output.resolve("assets/data.bin")))
        assertFalse(Files.exists(output.resolve("pkg/Test.class")))
        assertFalse(Files.exists(output.resolve("META-INF/MANIFEST.MF")))
        assertFalse(Files.exists(output.resolve("META-INF/TEST.SF")))
    }

    @Test
    fun `rejects path traversal`() {
        val jar = jar("../escape.txt" to byteArrayOf(1))
        assertFailsWith<WorkspaceException> {
            WorkspaceResourceExtractor().extract(jar, temporaryDirectory.resolve("resources"))
        }
        assertFalse(Files.exists(temporaryDirectory.resolve("escape.txt")))
    }

    private fun jar(vararg entries: Pair<String, ByteArray?>): Path {
        val path = temporaryDirectory.resolve("client-${entries.size}.jar")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                if (bytes != null) zip.write(bytes)
                zip.closeEntry()
            }
        }
        assertTrue(Files.isRegularFile(path))
        return path
    }
}
