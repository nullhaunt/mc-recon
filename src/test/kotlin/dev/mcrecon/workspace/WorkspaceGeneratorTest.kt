package dev.mcrecon.workspace

import dev.mcrecon.classfile.JarIndexStore
import dev.mcrecon.decompiler.SourceTreeHasher
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceGeneratorTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `materializes source byte for byte with portable lock and deterministic inventory`() {
        val plan = workspacePlan(temporaryDirectory.resolve("inputs"))
        val first = temporaryDirectory.resolve("workspace-one")
        val second = temporaryDirectory.resolve("workspace-two")

        val firstResult = WorkspaceGenerator().generate(plan, first)
        val secondResult = WorkspaceGenerator().generate(plan, second)

        val canonical = plan.reconstructedSource.resolve("example/Main.java")
        assertContentEquals(Files.readAllBytes(canonical), Files.readAllBytes(first.resolve("src/main/java/example/Main.java")))
        assertFalse(Files.readString(first.resolve("src/main/java/example/Main.java")).contains("class Main {}"))
        assertEquals(SourceTreeHasher.hash(plan.reconstructedSource), SourceTreeHasher.hash(first.resolve("src/main/java")))
        assertEquals(firstResult.workspaceSha256, secondResult.workspaceSha256)
        assertTrue(Files.isRegularFile(first.resolve("src/main/resources/assets/test.txt")))
        assertFalse(Files.exists(first.resolve("src/main/resources/example/Main.class")))

        val lockText = Files.readString(first.resolve("reconstruction.lock.json"))
        assertFalse(lockText.contains(temporaryDirectory.fileName.toString()))
        assertFalse(lockText.contains("access-token", ignoreCase = true))
        val lock = JarIndexStore.deterministicObjectMapper().readTree(lockText)
        assertEquals("test-version", lock.path("minecraft").path("version").asText())
        assertEquals("1.12.0", lock.path("decompiler").path("vineflowerVersion").asText())
        assertEquals(25, lock.path("compilerProfile").path("javaMajorVersion").asInt())
    }

    @Test
    fun `refuses an existing output without changing user files`() {
        val plan = workspacePlan(temporaryDirectory.resolve("inputs"))
        val output = temporaryDirectory.resolve("workspace")
        Files.createDirectories(output.resolve("src/main/java"))
        val edit = output.resolve("src/main/java/UserEdit.java")
        Files.writeString(edit, "user edit\n")

        assertFailsWith<WorkspaceException> { WorkspaceGenerator().generate(plan, output) }

        assertEquals("user edit\n", Files.readString(edit))
    }
}
