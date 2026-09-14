package dev.mcrecon.compiler

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mcrecon.classfile.BinaryIndexService
import dev.mcrecon.classfile.BinarySide
import dev.mcrecon.classfile.ClassDirectoryIndexer
import dev.mcrecon.classfile.ClassDirectorySource
import dev.mcrecon.classfile.JarIndexStore
import dev.mcrecon.classfile.Sha256
import dev.mcrecon.compiler.CompilationModel.ArtifactIdentity
import dev.mcrecon.compiler.CompilationModel.ClasspathArtifact
import dev.mcrecon.compiler.CompilationModel.DiagnosticGroup
import dev.mcrecon.compiler.CompilerProfileModel.CompilationInputs
import dev.mcrecon.compiler.CompilerProfileModel.CompilationOutput
import dev.mcrecon.compiler.CompilerProfileModel.CompilationReport
import dev.mcrecon.decompiler.DecompilationSide
import dev.mcrecon.decompiler.DirectoryPublisher
import dev.mcrecon.decompiler.SourceTreeHasher
import dev.mcrecon.manifest.VersionManifest
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.resolver.ResolvedArtifact
import dev.mcrecon.resolver.VersionResolver
import dev.mcrecon.verification.VerificationEngine
import dev.mcrecon.verification.VerificationModel.CandidateIdentity
import dev.mcrecon.verification.VerificationModel.Report
import dev.mcrecon.verification.VerificationReportStore
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class CompilerProfileResult(
    val classesPath: Path?,
    val compilationReport: CompilationReport,
    val compilationReportPath: Path,
    val profile: CompilerProfileModel.CompilerProfile,
    val reusedCompilation: Boolean,
    val verificationReport: Report?,
    val verificationReportPath: Path?,
)

/** Compiles and verifies one isolated compiler profile without touching the canonical baseline. */
class CompilerProfileService(
    private val cacheLayout: CacheLayout,
    private val resolver: VersionResolver = VersionResolver(cacheLayout),
    private val engine: JavaCompilationEngine = JavaCompilationEngine(),
    private val identityProvider: CompilerToolchainIdentityProvider = CompilerToolchainIdentityProbe(),
    private val binaryIndexService: BinaryIndexService = BinaryIndexService(cacheLayout),
    private val directoryIndexer: ClassDirectoryIndexer = ClassDirectoryIndexer(),
    private val verificationEngine: VerificationEngine = VerificationEngine(),
    private val verificationStore: VerificationReportStore = VerificationReportStore(),
    private val mapper: ObjectMapper = JarIndexStore.deterministicObjectMapper(),
) {
    fun compileAndVerify(
        manifest: VersionManifest,
        side: DecompilationSide,
        optionProfile: CompilerOptionProfile,
        force: Boolean = false,
        javaHome: Path? = null,
    ): CompilerProfileResult {
        if (side != DecompilationSide.CLIENT) {
            throw CompilationException("Server compiler profiling awaits server implementation reconstruction")
        }
        if (Runtime.version().feature() != manifest.javaVersion.majorVersion) {
            throw CompilationException(
                "Compiler profiles must run under Java ${manifest.javaVersion.majorVersion}; current runtime is Java ${Runtime.version().feature()}",
            )
        }
        val currentHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize()
        javaHome?.let { requireCurrentJavaHome(currentHome, it) }
        val toolchain = identityProvider.identify(currentHome)
        val profile = CompilerProfiles.materialize(optionProfile, toolchain, manifest.javaVersion.majorVersion)
        val source = cacheLayout.reconstructedSourceDirectory(manifest.id, side.cliName)
        val reconstruction = cacheLayout.reconstructionProvenance(manifest.id, side.cliName)
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(reconstruction, LinkOption.NOFOLLOW_LINKS)) {
            throw CompilationException("Reconstructed source and provenance are required before compiler profiling")
        }
        val sourceBefore = SourceTreeHasher.hash(source)
        val resolved = resolver.resolveClientCompileInputsCached(manifest)
        val classpath = resolved.libraries.map { ClasspathArtifact(it.artifactName, identity(it.file)) }
        val inputs = CompilationInputs(
            classpath,
            classpathIdentity(classpath),
            CompilerProfileModel.COMPILATION_FORMAT_VERSION,
            profile,
            manifest.id,
            Sha256.file(reconstruction),
            side.cliName,
            sourceBefore.sha256,
        )
        val classes = cacheLayout.compilerProfileClasses(manifest.id, side.cliName, profile.id())
        val compilationPath = cacheLayout.compilerProfileCompilationReport(manifest.id, side.cliName, profile.id())
        val verificationPath = cacheLayout.compilerProfileVerificationReport(manifest.id, side.cliName, profile.id())

        if (!force) {
            loadReusable(compilationPath, classes, inputs)?.let { compilation ->
                val verification = verify(manifest, side, classes, compilationPath, compilation, verificationPath)
                requireUnchangedSource(source, sourceBefore.sha256)
                return CompilerProfileResult(classes, compilation, compilationPath, profile, true, verification, verificationPath)
            }
        }

        Files.createDirectories(classes.parent)
        val staging = Files.createTempDirectory(classes.parent, ".classes.")
        try {
            val execution = engine.compile(source, staging, resolved.libraries.map { it.file.path }, profile.options())
            requireUnchangedSource(source, sourceBefore.sha256)
            val errors = execution.diagnostics.count { it.severity() == "ERROR" }
            val warnings = execution.diagnostics.count { it.severity() == "WARNING" || it.severity() == "MANDATORY_WARNING" }
            val classFingerprint = if (execution.success && errors == 0) {
                DeterministicTreeHasher.hash(staging, CompilationService.CLASS_TREE_DOMAIN)
            } else null
            val report = CompilationReport(
                execution.diagnostics,
                execution.diagnostics.groupBy { it.severity() to it.diagnosticCode() }
                    .map { (key, values) -> DiagnosticGroup(values.size, key.second, key.first) }
                    .sortedWith(compareBy(DiagnosticGroup::severity, DiagnosticGroup::diagnosticCode)),
                inputs,
                CompilationOutput(
                    classFingerprint?.fileCount ?: 0,
                    classFingerprint?.sha256,
                    errors,
                    if (classFingerprint != null) "SUCCESS" else "FAILED",
                    execution.sourceFiles,
                    warnings,
                ),
                CompilerProfileModel.COMPILATION_SCHEMA_VERSION,
            )
            if (classFingerprint == null) {
                writeReport(compilationPath, report)
                return CompilerProfileResult(null, report, compilationPath, profile, false, null, null)
            }
            val stagedReport = Files.createTempFile(classes.parent, ".compile-report.", ".json")
            try {
                writeJson(stagedReport, report)
                DirectoryPublisher.publishPair(staging, classes, stagedReport, compilationPath)
            } finally {
                Files.deleteIfExists(stagedReport)
            }
            val verification = verify(manifest, side, classes, compilationPath, report, verificationPath)
            return CompilerProfileResult(classes, report, compilationPath, profile, false, verification, verificationPath)
        } finally {
            DirectoryPublisher.deleteTreeIfExists(staging)
        }
    }

    private fun verify(
        manifest: VersionManifest,
        side: DecompilationSide,
        classes: Path,
        compilationPath: Path,
        compilation: CompilationReport,
        verificationPath: Path,
    ): Report {
        val tree = DeterministicTreeHasher.hash(classes, CompilationService.CLASS_TREE_DOMAIN)
        val reference = binaryIndexService.loadOrCreate(manifest, BinarySide.CLIENT).index
        val candidate = directoryIndexer.index(
            classes,
            ClassDirectorySource(manifest.id, side.cliName, "compiler-profile-class-directory"),
            tree,
        )
        val toolchain = compilation.inputs().profile().toolchain()
        val portableCompiler = CompilationModel.CompilerIdentity(
            toolchain.javacImplementation(),
            manifest.javaVersion.majorVersion,
            "${toolchain.javaVendor()} ${toolchain.javaRuntimeVersion()} | ${toolchain.javacVersion()} | ${toolchain.identitySha256()}",
        )
        val identity = CandidateIdentity(
            compilation.output().classFiles(),
            requireNotNull(compilation.output().classTreeSha256()),
            compilation.inputs().classpath(),
            compilation.inputs().compilationFormatVersion(),
            Sha256.file(compilationPath),
            compilation.schemaVersion(),
            portableCompiler,
            compilation.inputs().profile().options(),
            compilation.inputs().reconstructionProvenanceSha256(),
            compilation.inputs().sourceTreeSha256(),
        )
        val report = verificationEngine.compare(manifest.id, side.cliName, reference, candidate, identity)
        verificationStore.write(verificationPath, report)
        return report
    }

    private fun loadReusable(path: Path, classes: Path, inputs: CompilationInputs): CompilationReport? {
        if (!Files.isRegularFile(path) || !Files.isDirectory(classes, LinkOption.NOFOLLOW_LINKS)) return null
        return runCatching {
            val report = Files.newInputStream(path).use { mapper.readValue(it, CompilationReport::class.java) }
            if (report.schemaVersion() != CompilerProfileModel.COMPILATION_SCHEMA_VERSION || report.inputs() != inputs) return@runCatching null
            if (report.output().result() != "SUCCESS" || report.output().errors() != 0) return@runCatching null
            val actual = DeterministicTreeHasher.hash(classes, CompilationService.CLASS_TREE_DOMAIN)
            if (actual.fileCount != report.output().classFiles() || actual.sha256 != report.output().classTreeSha256()) return@runCatching null
            report
        }.getOrNull()
    }

    private fun requireCurrentJavaHome(currentHome: Path, requestedHome: Path) {
        val requested = requestedHome.toAbsolutePath().normalize()
        val same = runCatching { Files.isSameFile(currentHome, requested) }.getOrDefault(currentHome == requested)
        if (!same) {
            throw CompilationException(
                "The JDK compiler API belongs to $currentHome, but --java-home selected $requested. " +
                    "Launch mc-recon itself under the candidate JDK before running the profile.",
            )
        }
    }

    private fun requireUnchangedSource(source: Path, expectedHash: String) {
        val actual = SourceTreeHasher.hash(source)
        if (actual.sha256 != expectedHash) {
            throw CompilationException("Reconstructed source changed during compiler-profile compilation; experiment aborted")
        }
    }

    private fun identity(artifact: ResolvedArtifact) = ArtifactIdentity(
        artifact.request.expectedSha1.lowercase(),
        Sha256.file(artifact.path),
        artifact.request.expectedSize,
    )

    private fun classpathIdentity(classpath: List<ClasspathArtifact>): String = Sha256.text(
        classpath.joinToString("\u0000") {
            "${it.artifactName()}\u0000${it.artifact().mojangSha1()}\u0000${it.artifact().sha256()}\u0000${it.artifact().size()}"
        },
    )

    private fun writeReport(path: Path, report: CompilationReport) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            writeJson(temporary, report)
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun writeJson(path: Path, report: CompilationReport) {
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n", StandardCharsets.UTF_8)
    }
}
