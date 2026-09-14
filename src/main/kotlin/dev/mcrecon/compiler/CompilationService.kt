package dev.mcrecon.compiler

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mcrecon.classfile.JarIndexStore
import dev.mcrecon.classfile.Sha256
import dev.mcrecon.compiler.CompilationModel.ArtifactIdentity
import dev.mcrecon.compiler.CompilationModel.ClasspathArtifact
import dev.mcrecon.compiler.CompilationModel.CompilerIdentity
import dev.mcrecon.compiler.CompilationModel.DiagnosticGroup
import dev.mcrecon.compiler.CompilationModel.Inputs
import dev.mcrecon.compiler.CompilationModel.Output
import dev.mcrecon.compiler.CompilationModel.Report
import dev.mcrecon.decompiler.DecompilationSide
import dev.mcrecon.decompiler.DirectoryPublisher
import dev.mcrecon.decompiler.SourceTreeHasher
import dev.mcrecon.manifest.VersionManifest
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.resolver.ResolvedArtifact
import dev.mcrecon.resolver.VersionResolver
import dev.mcrecon.runtime.JavaRuntimeSelector
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

data class CompilationResult(
    val classesPath: Path?,
    val reportPath: Path,
    val report: Report,
    val reused: Boolean,
)

class CompilationService(
    private val cacheLayout: CacheLayout,
    private val resolver: VersionResolver = VersionResolver(cacheLayout),
    private val javaSelector: JavaRuntimeSelector = JavaRuntimeSelector(),
    private val engine: JavaCompilationEngine = JavaCompilationEngine(),
    private val mapper: ObjectMapper = JarIndexStore.deterministicObjectMapper(),
) {
    fun compile(
        manifest: VersionManifest,
        side: DecompilationSide,
        force: Boolean = false,
        javaExecutable: Path? = null,
        raw: Boolean = false,
    ): CompilationResult {
        if (side == DecompilationSide.SERVER) {
            throw CompilationException("Server implementation compilation awaits server bundle extraction")
        }
        val source = if (raw) cacheLayout.rawSourceDirectory(manifest.id, side.cliName)
            else cacheLayout.reconstructedSourceDirectory(manifest.id, side.cliName)
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            val command = if (raw) "decompile" else "reconstruct"
            throw CompilationException("Source tree does not exist at $source. Run 'mc-recon $command <manifest> --side ${side.cliName}' first.")
        }
        val runtime = javaSelector.select(manifest.javaVersion.majorVersion, javaExecutable)
        requireCurrentRuntime(runtime.executable)
        val resolved = resolver.resolveClientCompileInputsCached(manifest)
        val fingerprint = SourceTreeHasher.hash(source)
        val inputs = Inputs(
            resolved.libraries.map { library ->
                ClasspathArtifact(library.artifactName, identity(library.file))
            },
            CompilerIdentity("JDK compiler API (javac)", runtime.majorVersion, runtime.versionDescription),
            CompilationModel.FORMAT_VERSION,
            manifest.id,
            JavaCompilationEngine.logicalOptions(manifest.javaVersion.majorVersion),
            side.cliName,
            fingerprint.sha256,
        )
        val reportPath = if (raw) cacheLayout.rawCompilationReport(manifest.id, side.cliName)
            else cacheLayout.compilationReport(manifest.id, side.cliName)
        val classes = if (raw) null else cacheLayout.compiledClassesDirectory(manifest.id, side.cliName)
        if (!raw && !force && classes != null) {
            loadReusable(reportPath, classes, inputs)?.let { return CompilationResult(classes, reportPath, it, true) }
        }

        Files.createDirectories(source.parent)
        val staging = Files.createTempDirectory(source.parent, if (raw) ".raw-compile." else ".classes.")
        try {
            val execution = engine.compile(source, staging, resolved.libraries.map { it.file.path }, manifest.javaVersion.majorVersion)
            val errors = execution.diagnostics.count { it.severity() == "ERROR" }
            val warnings = execution.diagnostics.count { it.severity() == "WARNING" || it.severity() == "MANDATORY_WARNING" }
            val classFingerprint = if (execution.success && errors == 0) {
                DeterministicTreeHasher.hash(staging, CLASS_TREE_DOMAIN)
            } else null
            val report = Report(
                execution.diagnostics,
                execution.diagnostics.groupBy { it.severity() to it.diagnosticCode() }
                    .map { (key, values) -> DiagnosticGroup(values.size, key.second, key.first) }
                    .sortedWith(compareBy(DiagnosticGroup::severity, DiagnosticGroup::diagnosticCode)),
                inputs,
                Output(
                    classFingerprint?.fileCount ?: 0,
                    classFingerprint?.sha256,
                    errors,
                    if (execution.success && errors == 0) "SUCCESS" else "FAILED",
                    execution.sourceFiles,
                    warnings,
                ),
                CompilationModel.SCHEMA_VERSION,
            )
            if (classFingerprint == null || raw) {
                writeReport(reportPath, report)
                return CompilationResult(null, reportPath, report, false)
            }

            val stagedReport = Files.createTempFile(source.parent, ".compile-report.", ".json")
            try {
                writeJson(stagedReport, report)
                DirectoryPublisher.publishPair(staging, checkNotNull(classes), stagedReport, reportPath)
            } finally {
                Files.deleteIfExists(stagedReport)
            }
            return CompilationResult(classes, reportPath, report, false)
        } finally {
            DirectoryPublisher.deleteTreeIfExists(staging)
        }
    }

    private fun requireCurrentRuntime(selectedExecutable: Path) {
        val executableName = if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java"
        val current = Path.of(System.getProperty("java.home"), "bin", executableName).toAbsolutePath().normalize()
        val same = runCatching { Files.isSameFile(current, selectedExecutable) }.getOrDefault(current == selectedExecutable)
        if (!same) {
            throw CompilationException(
                "The JDK compiler API belongs to the runtime executing mc-recon ($current), but --java selected $selectedExecutable. " +
                    "Run mc-recon itself with the selected Java 25 JDK.",
            )
        }
    }

    private fun loadReusable(path: Path, classes: Path, inputs: Inputs): Report? {
        if (!Files.isRegularFile(path) || !Files.isDirectory(classes, LinkOption.NOFOLLOW_LINKS)) return null
        return runCatching {
            val report = Files.newInputStream(path).use { mapper.readValue(it, Report::class.java) }
            if (report.schemaVersion() != CompilationModel.SCHEMA_VERSION || report.inputs() != inputs) return@runCatching null
            if (report.output().result() != "SUCCESS" || report.output().errors() != 0) return@runCatching null
            val actual = DeterministicTreeHasher.hash(classes, CLASS_TREE_DOMAIN)
            if (actual.fileCount != report.output().classFiles() || actual.sha256 != report.output().classTreeSha256()) return@runCatching null
            report
        }.getOrNull()
    }

    private fun identity(artifact: ResolvedArtifact) = ArtifactIdentity(
        artifact.request.expectedSha1.lowercase(),
        Sha256.file(artifact.path),
        artifact.request.expectedSize,
    )

    private fun writeReport(path: Path, report: Report) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            writeJson(temporary, report)
            java.nio.file.Files.move(
                temporary,
                path,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (exception: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun writeJson(path: Path, report: Report) {
        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n"
        Files.writeString(path, json, StandardCharsets.UTF_8)
    }

    companion object {
        const val CLASS_TREE_DOMAIN = "mc-recon-class-tree-v1"
    }
}
