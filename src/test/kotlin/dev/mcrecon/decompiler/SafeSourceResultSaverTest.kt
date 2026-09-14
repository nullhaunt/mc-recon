package dev.mcrecon.decompiler

import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class SafeSourceResultSaverTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `writes UTF-8 with LF line endings`() {
        val root = temporaryDirectory.resolve("raw")
        val saver = SafeSourceResultSaver(root)

        saver.saveClassEntry("", "fixture.jar", "fixture/Test", "fixture/Test.java", "a\r\nb\rcafé\n")

        assertContentEquals(
            "a\nb\ncafé\n".toByteArray(StandardCharsets.UTF_8),
            Files.readAllBytes(root.resolve("fixture/Test.java")),
        )
    }

    @Test
    fun `rejects traversal absolute backslash and non-source paths`() {
        val saver = SafeSourceResultSaver(temporaryDirectory.resolve("raw"))

        listOf("../escape.java", "/absolute.java", "C:/escape.java", "pkg\\escape.java", "pkg/data.json").forEach { path ->
            assertFailsWith<DecompilationException>(path) {
                saver.saveClassEntry("", "fixture.jar", "fixture/Test", path, "class Test {}")
            }
        }
    }
}
