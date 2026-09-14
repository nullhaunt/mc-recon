package dev.mcrecon.decompiler

import dev.mcrecon.decompiler.DecompilationModel.Diagnostic
import org.jetbrains.java.decompiler.main.Fernflower
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger
import org.jetbrains.java.decompiler.main.extern.IResultSaver
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.Manifest

data class DecompilerInvocation(
    val externalLibraries: List<Path>,
    val javaHome: Path,
    val outputRoot: Path,
    val targetArtifact: Path,
)

data class DecompilerExecution(
    val diagnostics: List<Diagnostic>,
    val failedClasses: List<String>,
)

fun interface RawDecompilerEngine {
    fun decompile(invocation: DecompilerInvocation): DecompilerExecution
}

class VineflowerEngine : RawDecompilerEngine {
    override fun decompile(invocation: DecompilerInvocation): DecompilerExecution {
        val substitutions = buildList {
            add(invocation.targetArtifact to "<target-artifact>")
            invocation.externalLibraries.forEachIndexed { index, path -> add(path to "<external-library-${index + 1}>") }
            add(invocation.javaHome to "<java-runtime>")
            add(invocation.outputRoot to "<raw-source-staging>")
        }
        val logger = CapturingVineflowerLogger(substitutions)
        val saver = SafeSourceResultSaver(invocation.outputRoot)
        val fernflower = Fernflower(saver, VineflowerConfiguration.apiOptions(invocation.javaHome), logger)
        try {
            fernflower.addSource(invocation.targetArtifact.toFile())
            invocation.externalLibraries.forEach { fernflower.addLibrary(it.toFile()) }
            fernflower.decompileContext()
        } catch (exception: Exception) {
            throw DecompilationException(
                "Vineflower failed: ${exception.message ?: exception::class.java.simpleName}",
                exception,
            )
        } finally {
            fernflower.clearContext()
        }
        return DecompilerExecution(
            diagnostics = logger.importantDiagnostics(),
            failedClasses = (logger.failedClasses() + saver.failedClasses()).distinct().sorted(),
        )
    }
}

internal class SafeSourceResultSaver(private val root: Path) : IResultSaver {
    private val normalizedRoot = root.toAbsolutePath().normalize()
    private val written = ConcurrentHashMap.newKeySet<String>()
    private val failures = ConcurrentHashMap.newKeySet<String>()

    init {
        Files.createDirectories(normalizedRoot)
    }

    fun failedClasses(): List<String> = failures.toList()

    // Source writes create their own parents. Ignoring archive directory callbacks prevents empty
    // Minecraft resource directories (assets/, data/, META-INF/) from entering raw-source.
    override fun saveFolder(path: String) = Unit

    override fun saveDirEntry(path: String, archiveName: String, entryName: String) = Unit

    override fun saveClassFile(path: String, qualifiedName: String, entryName: String, content: String?, mapping: IntArray?) {
        val relative = listOf(path, entryName).filter(String::isNotBlank).joinToString("/")
        save(qualifiedName, relative, content)
    }

    override fun saveClassEntry(
        path: String,
        archiveName: String,
        qualifiedName: String,
        entryName: String,
        content: String?,
    ) {
        save(qualifiedName, entryName, content)
    }

    private fun save(qualifiedName: String, relative: String, content: String?) {
        if (content == null) {
            failures += qualifiedName.replace('/', '.')
            return
        }
        val destination = resolve(relative, directory = false)
        val canonicalRelative = normalizedRoot.relativize(destination).joinToString("/") { it.toString() }
        if (!written.add(canonicalRelative)) {
            throw DecompilationException("Vineflower attempted to emit duplicate source path '$canonicalRelative'")
        }
        Files.createDirectories(destination.parent)
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        Files.writeString(
            destination,
            normalized,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
    }

    private fun resolve(relative: String, directory: Boolean): Path {
        if (relative.isBlank() || '\u0000' in relative || '\\' in relative || relative.startsWith('/')) {
            throw DecompilationException("Vineflower produced an unsafe output path '$relative'")
        }
        val parts = relative.split('/').filter(String::isNotEmpty)
        if (parts.isEmpty() || parts.any { it == "." || it == ".." || ':' in it }) {
            throw DecompilationException("Vineflower produced an unsafe output path '$relative'")
        }
        if (!directory && !relative.endsWith(".java")) {
            throw DecompilationException("Vineflower attempted to emit non-Java output '$relative'")
        }
        val destination = parts.fold(normalizedRoot, Path::resolve).normalize()
        if (!destination.startsWith(normalizedRoot) || destination == normalizedRoot) {
            throw DecompilationException("Vineflower output path escapes the raw-source root: '$relative'")
        }
        return destination
    }

    override fun copyFile(source: String, path: String, entryName: String) {
        throw DecompilationException("Vineflower attempted to copy non-source file '$entryName'")
    }

    override fun copyEntry(source: String, path: String, archiveName: String, entry: String) {
        throw DecompilationException("Vineflower attempted to copy non-source archive entry '$entry'")
    }

    override fun createArchive(path: String, archiveName: String, manifest: Manifest?) = Unit
    override fun closeArchive(path: String, archiveName: String) = Unit
}

internal class CapturingVineflowerLogger(substitutions: List<Pair<Path, String>>) : IFernflowerLogger() {
    private val replacements = substitutions
        .map { (path, replacement) -> path.toAbsolutePath().normalize().toString() to replacement }
        .sortedByDescending { it.first.length }
    private val diagnostics = Collections.synchronizedList(mutableListOf<Diagnostic>())
    private val failed = ConcurrentHashMap.newKeySet<String>()

    override fun writeMessage(message: String, severity: Severity) = record(message, severity, null)

    override fun writeMessage(message: String, severity: Severity, throwable: Throwable?) =
        record(message, severity, throwable)

    private fun record(message: String, severity: Severity, throwable: Throwable?) {
        val sanitized = sanitize(message)
        FAILED_CLASS.find(sanitized)?.groupValues?.get(1)?.let { failed += it.replace('/', '.') }
        if (severity == Severity.WARN || severity == Severity.ERROR) {
            diagnostics += Diagnostic(
                throwable?.message?.let(::sanitize),
                throwable?.javaClass?.name,
                sanitized,
                severity.name,
            )
        }
    }

    fun importantDiagnostics(): List<Diagnostic> = synchronized(diagnostics) {
        diagnostics.distinct().sortedWith(
            compareBy<Diagnostic>({ it.severity() }, { it.message() }, { it.exceptionType().orEmpty() }, { it.exceptionMessage().orEmpty() }),
        )
    }

    fun failedClasses(): List<String> = failed.toList()

    private fun sanitize(value: String): String {
        var result = value.replace("\r\n", "\n").replace('\r', '\n')
        for ((path, replacement) in replacements) {
            result = result.replace(path, replacement, ignoreCase = true)
            result = result.replace(path.replace('\\', '/'), replacement, ignoreCase = true)
        }
        return result
    }

    private companion object {
        val FAILED_CLASS = Regex("Class ([^ ]+) couldn't be fully decompiled\\.")
    }
}

open class DecompilationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
