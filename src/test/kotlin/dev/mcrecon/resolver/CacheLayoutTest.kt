package dev.mcrecon.resolver

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CacheLayoutTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `uses Mojang Maven paths beneath the libraries directory`() {
        val layout = CacheLayout(temporaryDirectory.resolve("cache"))
        assertEquals(
            layout.root.resolve("libraries/org/example/demo/1.0/demo-1.0.jar"),
            layout.libraryArtifact("org/example/demo/1.0/demo-1.0.jar"),
        )
        assertEquals(
            layout.root.resolve("indexes/26.3-rc-2/client.json.gz"),
            layout.binaryIndex("26.3-rc-2", "client"),
        )
        assertEquals(
            layout.root.resolve("reconstruction/26.3-rc-2/client/reconstructed-source"),
            layout.reconstructedSourceDirectory("26.3-rc-2", "client"),
        )
        assertEquals(
            layout.root.resolve("reconstruction/26.3-rc-2/client/classes"),
            layout.compiledClassesDirectory("26.3-rc-2", "client"),
        )
        assertEquals(
            layout.root.resolve("reconstruction/26.3-rc-2/client/compile-report.json"),
            layout.compilationReport("26.3-rc-2", "client"),
        )
    }

    @Test
    fun `rejects traversal absolute and drive-qualified library paths`() {
        val layout = CacheLayout(temporaryDirectory.resolve("cache"))
        listOf(
            "../../outside.jar",
            "/outside.jar",
            "C:/outside.jar",
            "org/example/../outside.jar",
            "org\\example\\outside.jar",
        ).forEach { maliciousPath ->
            assertFailsWith<CachePathException>(maliciousPath) { layout.libraryArtifact(maliciousPath) }
        }
    }
}
