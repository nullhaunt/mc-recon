package dev.mcrecon.cli

import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class McReconCliTest {
    @Test
    fun `nonexistent manifests fail without a stack trace`() {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exitCode = McReconCli().run(
            arrayOf("inspect", "does-not-exist.json"),
            PrintWriter(stdout),
            PrintWriter(stderr),
        )

        assertEquals(2, exitCode)
        assertContains(stderr.toString(), "Manifest does not exist")
        assertEquals("", stdout.toString())
        assert(!stderr.toString().contains("\tat "))
    }
}
