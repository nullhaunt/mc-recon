package dev.mcrecon.classfile

import dev.mcrecon.classfile.DiffModel.ChangeKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class JarIndexStoreTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val source = JarSource("fixture", "client", "client", "0".repeat(40))

    @Test
    fun `resources are classified as added removed changed and unchanged`() {
        val classBytes = ClassfileTestFixtures.compile(
            temporaryDirectory, "JarSample", "public class JarSample { int value() { return 1; } }",
        )
        val beforePath = ClassfileTestFixtures.writeJar(
            temporaryDirectory.resolve("before.jar"),
            "JarSample.class" to classBytes,
            "unchanged.txt" to "same".toByteArray(),
            "changed.json" to "{\"value\":1}".toByteArray(),
            "removed.png" to byteArrayOf(1, 2, 3),
        )
        val afterPath = ClassfileTestFixtures.writeJar(
            temporaryDirectory.resolve("after.jar"),
            "JarSample.class" to classBytes,
            "unchanged.txt" to "same".toByteArray(),
            "changed.json" to "{\"value\":2}".toByteArray(),
            "added.bin" to byteArrayOf(4, 5),
        )
        val indexer = JarIndexer()
        val diff = BinaryDiffEngine().compare(indexer.index(beforePath, source), indexer.index(afterPath, source))
        val resources = diff.resources().associateBy { it.path() }

        assertEquals(ChangeKind.UNCHANGED, resources.getValue("unchanged.txt").change())
        assertEquals(ChangeKind.CHANGED, resources.getValue("changed.json").change())
        assertEquals(ChangeKind.REMOVED, resources.getValue("removed.png").change())
        assertEquals(ChangeKind.ADDED, resources.getValue("added.bin").change())
        assertTrue(diff.archiveEntries().all { it.path() in setOf("JarSample.class", "unchanged.txt", "changed.json", "removed.png", "added.bin") })
    }

    @Test
    fun `classes are added and removed by stable class entry identity`() {
        val retained = ClassfileTestFixtures.compile(
            temporaryDirectory, "Retained", "public class Retained {}",
        )
        val removed = ClassfileTestFixtures.compile(
            temporaryDirectory, "Removed", "public class Removed {}",
        )
        val added = ClassfileTestFixtures.compile(
            temporaryDirectory, "Added", "public class Added {}",
        )
        val before = JarIndexer().index(
            ClassfileTestFixtures.writeJar(
                temporaryDirectory.resolve("classes-before.jar"),
                "Retained.class" to retained,
                "Removed.class" to removed,
            ),
            source,
        )
        val after = JarIndexer().index(
            ClassfileTestFixtures.writeJar(
                temporaryDirectory.resolve("classes-after.jar"),
                "Retained.class" to retained,
                "Added.class" to added,
            ),
            source,
        )
        val classes = BinaryDiffEngine().compare(before, after).classes().associateBy { it.binaryName() }

        assertEquals(ChangeKind.UNCHANGED, classes.getValue("Retained").change())
        assertEquals(ChangeKind.REMOVED, classes.getValue("Removed").change())
        assertEquals(ChangeKind.ADDED, classes.getValue("Added").change())
    }

    @Test
    fun `saved indexes reload invalidate and serialize deterministically`() {
        val jarPath = ClassfileTestFixtures.writeJar(
            temporaryDirectory.resolve("sample.jar"),
            "one.txt" to "one".toByteArray(),
            "two.txt" to "two".toByteArray(),
        )
        val indexPath = temporaryDirectory.resolve("indexes/sample.json.gz")
        val store = JarIndexStore()

        val generated = store.loadOrCreate(jarPath, indexPath, source)
        val firstBytes = Files.readAllBytes(indexPath)
        val reused = store.loadOrCreate(jarPath, indexPath, source)
        assertFalse(generated.reused)
        assertTrue(reused.reused)
        assertEquals(generated.index, reused.index)

        val mapper = JarIndexStore.deterministicObjectMapper()
        val stored = Files.newInputStream(indexPath).use { input ->
            GZIPInputStream(input).use { mapper.readValue(it, IndexModel.JarIndex::class.java) }
        }
        val schemaMismatch = IndexModel.JarIndex(
            0, stored.artifact(), stored.entries(), stored.classes(), stored.resources(),
        )
        Files.newOutputStream(indexPath).use { output ->
            GZIPOutputStream(output).use { mapper.writeValue(it, schemaMismatch) }
        }
        assertFalse(store.loadOrCreate(jarPath, indexPath, source).reused)

        Files.delete(indexPath)
        store.loadOrCreate(jarPath, indexPath, source)
        assertTrue(firstBytes.contentEquals(Files.readAllBytes(indexPath)))

        val oldArtifactHash = generated.index.artifact().artifactHash()
        ClassfileTestFixtures.writeJar(jarPath, "one.txt" to "changed".toByteArray())
        val invalidated = store.loadOrCreate(jarPath, indexPath, source)
        assertFalse(invalidated.reused)
        assertNotEquals(oldArtifactHash, invalidated.index.artifact().artifactHash())
    }

    @Test
    fun `malformed class errors identify jar and entry`() {
        val jarPath = ClassfileTestFixtures.writeJar(
            temporaryDirectory.resolve("broken.jar"), "broken/Bad.class" to byteArrayOf(1, 2, 3),
        )
        val error = kotlin.runCatching { JarIndexer().index(jarPath, source) }.exceptionOrNull()
        require(error is JarIndexingException)
        assertTrue(error.message.orEmpty().contains(jarPath.toString()))
        assertTrue(error.message.orEmpty().contains("broken/Bad.class"))
        assertTrue(error.message.orEmpty().contains("parse"))
    }
}
