package dev.mcrecon.classfile

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

internal object ClassfileTestFixtures {
    private val sequence = AtomicInteger()

    fun compile(root: Path, className: String, source: String, debugOption: String = "-g"): ByteArray {
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "tests require a full JDK" }
        val fixture = root.resolve("compile-${sequence.incrementAndGet()}")
        val sourceRoot = fixture.resolve("src")
        val outputRoot = fixture.resolve("classes")
        val sourceFile = sourceRoot.resolve(className.replace('.', '/') + ".java")
        Files.createDirectories(sourceFile.parent)
        Files.createDirectories(outputRoot)
        Files.writeString(sourceFile, source.trimIndent())
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { manager ->
            val units = manager.getJavaFileObjects(sourceFile.toFile())
            val options = listOf(debugOption, "--release", "17", "-d", outputRoot.toString())
            val success = compiler.getTask(null, manager, diagnostics, options, null, units).call()
            check(success) { diagnostics.diagnostics.joinToString("\n") }
        }
        return Files.readAllBytes(outputRoot.resolve(className.replace('.', '/') + ".class"))
    }

    fun writeJar(path: Path, vararg entries: Pair<String, ByteArray>): Path {
        Files.createDirectories(path.parent)
        JarOutputStream(Files.newOutputStream(path)).use { output ->
            for ((name, bytes) in entries) {
                val entry = JarEntry(name)
                entry.time = 0
                output.putNextEntry(entry)
                output.write(bytes)
                output.closeEntry()
            }
        }
        return path
    }
}
