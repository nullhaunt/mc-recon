package dev.mcrecon.compiler

import dev.mcrecon.compiler.CompilationModel.Diagnostic
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

data class CompilerExecution(
    val success: Boolean,
    val sourceFiles: Int,
    val diagnostics: List<Diagnostic>,
)

class JavaCompilationEngine {
    fun compile(sourceRoot: Path, outputRoot: Path, classpath: List<Path>, release: Int): CompilerExecution {
        val root = sourceRoot.toAbsolutePath().normalize()
        val sources = Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }
                .sorted(compareBy { root.relativize(it).joinToString("/") { part -> part.toString() } })
                .toList()
        }
        Files.createDirectories(outputRoot)
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: throw CompilationException("The Java runtime executing mc-recon does not provide the JDK compiler API")
        val collector = DiagnosticCollector<JavaFileObject>()
        val options = physicalOptions(release, classpath, outputRoot)
        val success = compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8).use { manager ->
            compiler.getTask(
                null,
                manager,
                collector,
                options,
                null,
                manager.getJavaFileObjectsFromPaths(sources),
            ).call()
        }
        val diagnostics = collector.diagnostics.map { diagnostic ->
            Diagnostic(
                diagnostic.columnNumber,
                diagnostic.code,
                diagnostic.lineNumber,
                diagnostic.getMessage(Locale.ROOT).replace("\r\n", "\n").replace('\r', '\n'),
                diagnostic.kind.name,
                sourcePath(root, diagnostic.source),
            )
        }.sortedWith(
            compareBy<Diagnostic>(Diagnostic::sourcePath, Diagnostic::line, Diagnostic::column, Diagnostic::diagnosticCode, Diagnostic::message),
        )
        return CompilerExecution(success, sources.size, diagnostics)
    }

    companion object {
        fun logicalOptions(release: Int): List<String> = listOf(
            "--release", release.toString(),
            "-encoding", "UTF-8",
            "-proc:none",
            "-Xmaxerrs", "100000",
            "-Xmaxwarns", "100000",
            "-classpath", "<resolved-manifest-artifacts>",
            "-d", "<class-output>",
        )

        private fun physicalOptions(release: Int, classpath: List<Path>, outputRoot: Path): List<String> =
            logicalOptions(release).map { option ->
                when (option) {
                    "<resolved-manifest-artifacts>" -> classpath.joinToString(java.io.File.pathSeparator) { it.toString() }
                    "<class-output>" -> outputRoot.toString()
                    else -> option
                }
            }

        private fun sourcePath(root: Path, source: JavaFileObject?): String {
            if (source == null) return ""
            return runCatching {
                val path = Path.of(source.toUri()).toAbsolutePath().normalize()
                if (path.startsWith(root)) root.relativize(path).joinToString("/") { it.toString() } else "<outside-source-root>"
            }.getOrDefault("<unknown-source>")
        }
    }
}

open class CompilationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
