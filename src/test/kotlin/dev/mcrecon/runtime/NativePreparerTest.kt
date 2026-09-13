package dev.mcrecon.runtime

import dev.mcrecon.artifact
import dev.mcrecon.resolvedVersion
import dev.mcrecon.resolver.LibraryArtifactRole
import dev.mcrecon.testLibrary
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativePreparerTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `extracts files honors exclusions and reuses unchanged native set`() {
        val native = artifact(
            temporaryDirectory.resolve("libraries/native.jar"),
            zip("native/test.dll" to byteArrayOf(1, 2, 3), "META-INF/MANIFEST.MF" to byteArrayOf(9), "skip/file.txt" to byteArrayOf(8)),
        )
        val version = resolvedVersion(
            temporaryDirectory,
            listOf(testLibrary("example:native:1", listOf(LibraryArtifactRole.NATIVE to native), excludes = listOf("skip/"))),
        )
        val directory = temporaryDirectory.resolve("runtime/natives")
        val preparer = NativePreparer()

        val first = preparer.prepare(version, directory)
        val second = preparer.prepare(version, directory)

        assertFalse(first.reused)
        assertTrue(second.reused)
        assertEquals(1, first.extractedFiles)
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(directory.resolve("native/test.dll")))
        assertFalse(Files.exists(directory.resolve("META-INF")))
        assertFalse(Files.exists(directory.resolve("skip")))
    }

    @Test
    fun `identical duplicate entries are deterministic but conflicts fail`() {
        val first = artifact(temporaryDirectory.resolve("libraries/one.jar"), zip("same.dll" to byteArrayOf(1)))
        val identical = artifact(temporaryDirectory.resolve("libraries/two.jar"), zip("same.dll" to byteArrayOf(1)))
        val conflict = artifact(temporaryDirectory.resolve("libraries/three.jar"), zip("same.dll" to byteArrayOf(2)))
        fun versionWith(second: dev.mcrecon.resolver.ResolvedArtifact) = resolvedVersion(
            temporaryDirectory,
            listOf(
                testLibrary("example:one:1", listOf(LibraryArtifactRole.NATIVE to first)),
                testLibrary("example:two:1", listOf(LibraryArtifactRole.NATIVE to second)),
            ),
        )

        assertEquals(1, NativePreparer().prepare(versionWith(identical), temporaryDirectory.resolve("ok/natives")).extractedFiles)
        val failure = assertFailsWith<RuntimePreparationException> {
            NativePreparer().prepare(versionWith(conflict), temporaryDirectory.resolve("bad/natives"))
        }
        assertTrue(failure.message!!.contains("Conflicting native entry"))
        assertFalse(Files.exists(temporaryDirectory.resolve("bad/natives/.mc-recon-natives")))
    }

    @Test
    fun `ZIP traversal and absolute paths are rejected without a ready directory`() {
        for ((case, name) in listOf("traversal" to "../escape.dll", "absolute" to "/escape.dll", "drive" to "C:/escape.dll")) {
            val native = artifact(temporaryDirectory.resolve("libraries/$case.jar"), zip(name to byteArrayOf(1)))
            val version = resolvedVersion(
                temporaryDirectory.resolve(case),
                listOf(testLibrary("example:$case:1", listOf(LibraryArtifactRole.NATIVE to native))),
            )
            val directory = temporaryDirectory.resolve("$case/runtime/natives")
            assertFailsWith<RuntimePreparationException> { NativePreparer().prepare(version, directory) }
            assertFalse(Files.exists(directory.resolve(".mc-recon-natives")))
            assertFalse(Files.exists(temporaryDirectory.resolve("escape.dll")))
        }
    }

    @Test
    fun `missing extracted output invalidates readiness and rebuilds`() {
        val native = artifact(temporaryDirectory.resolve("libraries/native.jar"), zip("test.dll" to byteArrayOf(1)))
        val version = resolvedVersion(
            temporaryDirectory,
            listOf(testLibrary("example:native:1", listOf(LibraryArtifactRole.NATIVE to native))),
        )
        val directory = temporaryDirectory.resolve("runtime/natives")
        val preparer = NativePreparer()
        preparer.prepare(version, directory)
        Files.delete(directory.resolve("test.dll"))

        val rebuilt = preparer.prepare(version, directory)

        assertFalse(rebuilt.reused)
        assertTrue(Files.isRegularFile(directory.resolve("test.dll")))
    }

    @Test
    fun `changed native artifact hash rebuilds prepared directory`() {
        val first = artifact(temporaryDirectory.resolve("libraries/first.jar"), zip("test.dll" to byteArrayOf(1)))
        val second = artifact(temporaryDirectory.resolve("libraries/second.jar"), zip("test.dll" to byteArrayOf(2)))
        fun version(native: dev.mcrecon.resolver.ResolvedArtifact) = resolvedVersion(
            temporaryDirectory,
            listOf(testLibrary("example:native:1", listOf(LibraryArtifactRole.NATIVE to native))),
        )
        val directory = temporaryDirectory.resolve("runtime/natives")
        val preparer = NativePreparer()
        preparer.prepare(version(first), directory)

        val rebuilt = preparer.prepare(version(second), directory)

        assertFalse(rebuilt.reused)
        assertContentEquals(byteArrayOf(2), Files.readAllBytes(directory.resolve("test.dll")))
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { archive ->
            for ((name, bytes) in entries) {
                archive.putNextEntry(ZipEntry(name))
                archive.write(bytes)
                archive.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
