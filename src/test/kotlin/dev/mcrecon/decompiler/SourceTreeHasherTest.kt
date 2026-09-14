package dev.mcrecon.decompiler

import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SourceTreeHasherTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `hash is content deterministic and ignores iteration order and timestamps`() {
        val first = temporaryDirectory.resolve("first")
        val second = temporaryDirectory.resolve("second")
        write(first, "z/Z.java", "class Z {}\n")
        write(first, "a/A.java", "class A {}\n")
        write(second, "a/A.java", "class A {}\n")
        write(second, "z/Z.java", "class Z {}\n")

        Files.setLastModifiedTime(first.resolve("a/A.java"), FileTime.fromMillis(1))
        Files.setLastModifiedTime(second.resolve("a/A.java"), FileTime.fromMillis(9_999_999))

        assertEquals(SourceTreeHasher.hash(first), SourceTreeHasher.hash(second))
    }

    @Test
    fun `content and relative path changes alter hash`() {
        val baseline = temporaryDirectory.resolve("baseline")
        val contentChange = temporaryDirectory.resolve("content")
        val pathChange = temporaryDirectory.resolve("path")
        write(baseline, "pkg/A.java", "class A {}\n")
        write(contentChange, "pkg/A.java", "class A { int x; }\n")
        write(pathChange, "other/A.java", "class A {}\n")

        val expected = SourceTreeHasher.hash(baseline).sha256
        assertNotEquals(expected, SourceTreeHasher.hash(contentChange).sha256)
        assertNotEquals(expected, SourceTreeHasher.hash(pathChange).sha256)
    }

    private fun write(root: Path, relative: String, text: String) {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, text, StandardCharsets.UTF_8)
    }
}
