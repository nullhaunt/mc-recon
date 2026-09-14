package dev.mcrecon.decompiler

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DirectoryPublisherTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `successful publication replaces the complete tree and removes stale files`() {
        val destination = temporaryDirectory.resolve("raw-source")
        val staging = temporaryDirectory.resolve("staging")
        Files.createDirectories(destination)
        Files.writeString(destination.resolve("stale.java"), "stale")
        Files.createDirectories(staging.resolve("pkg"))
        Files.writeString(staging.resolve("pkg/New.java"), "new")

        DirectoryPublisher.publish(staging, destination)

        assertFalse(Files.exists(destination.resolve("stale.java")))
        assertEquals("new", Files.readString(destination.resolve("pkg/New.java")))
    }
}
