package dev.mcrecon.runtime

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JavaRuntimeSelectorTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `required major version is accepted and explicit executable is probed`() {
        val executable = Files.createFile(temporaryDirectory.resolve("custom-java"))
        var probed: Path? = null
        val expected = JavaRuntime(executable, 25, "test Java 25")
        val selector = JavaRuntimeSelector { path -> probed = path; expected }

        val selected = selector.select(25, executable)

        assertSame(expected, selected)
        assertEquals(executable.toAbsolutePath().normalize(), probed)
    }

    @Test
    fun `wrong major version is rejected unless explicitly allowed`() {
        val executable = Files.createFile(temporaryDirectory.resolve("java"))
        val selector = JavaRuntimeSelector { JavaRuntime(it, 26, "test Java 26") }

        val failure = assertFailsWith<RuntimePreparationException> { selector.select(25, executable) }
        assertTrue(failure.message!!.contains("requires Java 25"))
        assertEquals(26, selector.select(25, executable, allowMismatch = true).majorVersion)
    }
}
