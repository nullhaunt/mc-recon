package dev.mcrecon.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class JavaRuntime(
    val executable: Path,
    val majorVersion: Int,
    val versionDescription: String,
)

fun interface JavaRuntimeProbe {
    fun probe(executable: Path): JavaRuntime
}

class ProcessJavaRuntimeProbe : JavaRuntimeProbe {
    override fun probe(executable: Path): JavaRuntime {
        val process = try {
            ProcessBuilder(executable.toString(), "-version").redirectErrorStream(true).start()
        } catch (exception: Exception) {
            throw RuntimePreparationException("Could not execute Java runtime at $executable", exception)
        }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw RuntimePreparationException("Timed out while identifying Java runtime at $executable")
        }
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.exitValue() != 0) {
            throw RuntimePreparationException("Java runtime at $executable exited with ${process.exitValue()}: $output")
        }
        val major = VERSION.find(output)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw RuntimePreparationException("Could not determine the Java major version from '$output'")
        return JavaRuntime(executable, major, output.lineSequence().firstOrNull().orEmpty())
    }

    private companion object {
        val VERSION = Regex("(?:java|openjdk) version \\\"(?:1\\.)?(\\d+)(?:[.\\\"])", RegexOption.IGNORE_CASE)
    }
}

class JavaRuntimeSelector(private val probe: JavaRuntimeProbe = ProcessJavaRuntimeProbe()) {
    fun select(requiredMajor: Int, explicitExecutable: Path? = null, allowMismatch: Boolean = false): JavaRuntime {
        val executable = (explicitExecutable ?: currentJavaExecutable()).toAbsolutePath().normalize()
        if (!Files.isRegularFile(executable)) {
            throw RuntimePreparationException("Selected Java executable does not exist or is not a file: $executable")
        }
        val runtime = probe.probe(executable)
        if (!allowMismatch && runtime.majorVersion != requiredMajor) {
            throw RuntimePreparationException(
                "Minecraft requires Java $requiredMajor. Selected runtime: ${runtime.executable} (Java ${runtime.majorVersion}). " +
                    "Use --java <path-to-java-$requiredMajor> or explicitly allow a version mismatch.",
            )
        }
        return runtime
    }

    private fun currentJavaExecutable(): Path {
        val fileName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", fileName)
    }
}

open class RuntimePreparationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
