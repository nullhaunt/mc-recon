package dev.mcrecon.verification

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mcrecon.classfile.BinaryIndexService
import dev.mcrecon.classfile.BinarySide
import dev.mcrecon.classfile.ClassDirectoryIndexer
import dev.mcrecon.classfile.ClassDirectorySource
import dev.mcrecon.classfile.JarIndexStore
import dev.mcrecon.classfile.Sha256
import dev.mcrecon.compiler.CompilationModel
import dev.mcrecon.compiler.CompilationModel.Report
import dev.mcrecon.compiler.CompilationService
import dev.mcrecon.compiler.DeterministicTreeHasher
import dev.mcrecon.manifest.VersionManifest
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.verification.VerificationModel.CandidateIdentity
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

data class VerificationResult(
    val report: VerificationModel.Report,
    val reportPath: Path,
    val referenceJar: Path,
    val candidateClasses: Path,
)

class VerificationService(
    private val cacheLayout: CacheLayout,
    private val binaryIndexService: BinaryIndexService = BinaryIndexService(cacheLayout),
    private val directoryIndexer: ClassDirectoryIndexer = ClassDirectoryIndexer(),
    private val engine: VerificationEngine = VerificationEngine(),
    private val reportStore: VerificationReportStore = VerificationReportStore(),
    private val mapper: ObjectMapper = JarIndexStore.deterministicObjectMapper(),
) {
    fun verify(manifest: VersionManifest, side: BinarySide): VerificationResult {
        if (side != BinarySide.CLIENT) {
            throw VerificationException("Server reconstruction verification awaits server implementation compilation")
        }
        val classesPath = cacheLayout.compiledClassesDirectory(manifest.id, side.cliName)
        val compilationPath = cacheLayout.compilationReport(manifest.id, side.cliName)
        val reconstructionPath = cacheLayout.reconstructionProvenance(manifest.id, side.cliName)
        val compilation = readCompilationReport(compilationPath)
        validateCompilation(manifest, side, classesPath, compilation)
        if (!Files.isRegularFile(reconstructionPath, LinkOption.NOFOLLOW_LINKS)) {
            throw VerificationException("Reconstruction provenance does not exist at $reconstructionPath")
        }

        val tree = DeterministicTreeHasher.hash(classesPath, CompilationService.CLASS_TREE_DOMAIN)
        val reference = binaryIndexService.loadOrCreate(manifest, side).index
        val candidate = directoryIndexer.index(
            classesPath,
            ClassDirectorySource(manifest.id, side.cliName),
            tree,
        )
        val identity = CandidateIdentity(
            compilation.output().classFiles(),
            requireNotNull(compilation.output().classTreeSha256()),
            compilation.inputs().classpath(),
            compilation.inputs().compilationFormatVersion(),
            Sha256.file(compilationPath),
            compilation.schemaVersion(),
            compilation.inputs().compiler(),
            compilation.inputs().options(),
            Sha256.file(reconstructionPath),
            compilation.inputs().sourceTreeSha256(),
        )
        val report = engine.compare(manifest.id, side.cliName, reference, candidate, identity)
        val reportPath = cacheLayout.verificationReport(manifest.id, side.cliName)
        reportStore.write(reportPath, report)
        return VerificationResult(
            report,
            reportPath,
            cacheLayout.versionArtifact(manifest.id, "${side.cliName}.jar"),
            classesPath,
        )
    }

    private fun readCompilationReport(path: Path): Report {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw VerificationException("Compilation report does not exist at $path. Run 'mc-recon compile <manifest> --side client' first.")
        }
        return try {
            Files.newInputStream(path).use { mapper.readValue(it, Report::class.java) }
        } catch (exception: Exception) {
            throw VerificationException("Cannot read compilation report $path: ${exception.message}", exception)
        }
    }

    private fun validateCompilation(
        manifest: VersionManifest,
        side: BinarySide,
        classesPath: Path,
        report: Report,
    ) {
        if (report.schemaVersion() != CompilationModel.SCHEMA_VERSION) {
            throw VerificationException(
                "Compilation report schema ${report.schemaVersion()} is not supported; expected ${CompilationModel.SCHEMA_VERSION}",
            )
        }
        if (report.inputs().minecraftVersion() != manifest.id || report.inputs().side() != side.cliName) {
            throw VerificationException("Compilation report identity does not match Minecraft ${manifest.id} ${side.cliName}")
        }
        if (report.output().result() != "SUCCESS" || report.output().errors() != 0) {
            throw VerificationException("Candidate compilation did not succeed with zero errors")
        }
        if (!Files.isDirectory(classesPath, LinkOption.NOFOLLOW_LINKS)) {
            throw VerificationException("Compiled class directory does not exist at $classesPath")
        }
        val actual = DeterministicTreeHasher.hash(classesPath, CompilationService.CLASS_TREE_DOMAIN)
        if (actual.fileCount != report.output().classFiles() || actual.sha256 != report.output().classTreeSha256()) {
            throw VerificationException(
                "Compiled class directory does not match compile-report.json; run 'mc-recon compile <manifest> --side client' again",
            )
        }
    }
}
