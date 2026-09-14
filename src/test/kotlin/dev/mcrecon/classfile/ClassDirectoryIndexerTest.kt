package dev.mcrecon.classfile

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class ClassDirectoryIndexerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `directory indexes are deterministic and ignore filesystem timestamps`() {
        val bytes = ClassfileTestFixtures.compile(
            temporaryDirectory,
            "example.DirectorySample",
            "package example; public class DirectorySample { int value() { return 1; } }",
        )
        val firstRoot = writeClass(temporaryDirectory.resolve("one"), "example/DirectorySample.class", bytes)
        val secondRoot = writeClass(temporaryDirectory.resolve("two"), "example/DirectorySample.class", bytes)
        val source = ClassDirectorySource("fixture", "client")
        val indexer = ClassDirectoryIndexer()

        val first = indexer.index(firstRoot, source)
        Files.setLastModifiedTime(firstRoot.resolve("example/DirectorySample.class"), FileTime.fromMillis(123456789L))
        val repeated = indexer.index(firstRoot, source)
        val elsewhere = indexer.index(secondRoot, source)

        assertEquals(first, repeated)
        assertEquals(first, elsewhere)
        assertEquals("example/DirectorySample.class", first.classes().single().entryPath())
        assertEquals(first.classes().single().classExactHash(), elsewhere.classes().single().classExactHash())
    }

    @Test
    fun `directory index rejects non-class files`() {
        val root = temporaryDirectory.resolve("mixed")
        Files.createDirectories(root)
        Files.writeString(root.resolve("not-a-class.txt"), "evidence")

        val error = assertFailsWith<JarIndexingException> {
            ClassDirectoryIndexer().index(root, ClassDirectorySource("fixture", "client"))
        }
        assertTrue(error.message.orEmpty().contains("non-class file"))
    }

    @Test
    fun `directory index does not follow symbolic links`() {
        val bytes = ClassfileTestFixtures.compile(
            temporaryDirectory,
            "LinkedSample",
            "public class LinkedSample {}",
        )
        val outside = temporaryDirectory.resolve("outside.class")
        Files.write(outside, bytes)
        val root = temporaryDirectory.resolve("linked")
        Files.createDirectories(root)
        val link = root.resolve("LinkedSample.class")
        val created = runCatching { Files.createSymbolicLink(link, outside) }.isSuccess
        assumeTrue(created, "symbolic-link creation is not available on this host")

        val error = assertFailsWith<JarIndexingException> {
            ClassDirectoryIndexer().index(root, ClassDirectorySource("fixture", "client"))
        }
        assertTrue(error.message.orEmpty().contains("symbolic link"))
    }

    private fun writeClass(root: Path, entryPath: String, bytes: ByteArray): Path {
        val path = root.resolve(entryPath.replace('/', java.io.File.separatorChar))
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
        return root
    }
}
