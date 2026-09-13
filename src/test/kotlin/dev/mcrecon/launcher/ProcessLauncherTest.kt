package dev.mcrecon.launcher

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessLauncherTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `process receives direct arguments and working directory`() {
        val output = temporaryDirectory.resolve("process output.txt")
        val working = temporaryDirectory.resolve("working directory")
        Files.createDirectories(working)
        val executableName = if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java"
        val java = Path.of(System.getProperty("java.home"), "bin", executableName)
        val specification = LaunchSpecification(
            side = LaunchSide.SERVER,
            executable = java,
            workingDirectory = working,
            jvmArguments = listOf(
                "-cp",
                System.getProperty("java.class.path"),
                ProcessProbe::class.java.name,
                output.toString(),
                "argument with spaces",
            ),
        )

        assertEquals(0, ProcessLauncher().launch(specification))
        assertEquals(
            listOf(working.toAbsolutePath().normalize().toString(), "argument with spaces"),
            Files.readAllLines(output),
        )
    }
}

object ProcessProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        Files.write(
            Path.of(arguments[0]),
            listOf(Path.of("").toAbsolutePath().normalize().toString()) + arguments.drop(1),
        )
    }
}
