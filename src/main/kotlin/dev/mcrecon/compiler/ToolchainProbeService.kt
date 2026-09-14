package dev.mcrecon.compiler

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mcrecon.classfile.BinaryIndexService
import dev.mcrecon.classfile.BinarySide
import dev.mcrecon.classfile.ClassDirectoryIndexer
import dev.mcrecon.classfile.ClassDirectorySource
import dev.mcrecon.classfile.IndexModel.JarIndex
import dev.mcrecon.classfile.IndexModel.MethodIndex
import dev.mcrecon.classfile.JarIndexStore
import dev.mcrecon.classfile.Sha256
import dev.mcrecon.compiler.CompilerProfileModel.Baseline
import dev.mcrecon.compiler.CompilerProfileModel.DiagnosticClass
import dev.mcrecon.compiler.CompilerProfileModel.Metrics
import dev.mcrecon.compiler.CompilerProfileModel.ProbeReport
import dev.mcrecon.compiler.CompilerProfileModel.ProfileMeasurement
import dev.mcrecon.compiler.CompilerProfileModel.RegressionSummary
import dev.mcrecon.compiler.CompilerProfileModel.TableDifferences
import dev.mcrecon.decompiler.DecompilationSide
import dev.mcrecon.decompiler.SourceTreeHasher
import dev.mcrecon.manifest.VersionManifest
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.verification.VerificationComparisonEngine
import dev.mcrecon.verification.VerificationModel.Report
import dev.mcrecon.verification.VerificationModel.Status
import dev.mcrecon.verification.VerificationReportStore
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class ToolchainProbeResult(val report: ProbeReport, val reportPath: Path)

object CompilerProfileMetrics {
    fun extract(report: Report, reference: JarIndex, candidate: JarIndex): Metrics {
        val aggregate = report.aggregate()
        val attributes = aggregate.debugAndAttributes()
        return Metrics(
            aggregate.classes(),
            attributes,
            aggregate.methods().corresponding() - aggregate.methods().debugChanged(),
            aggregate.executableRepresentation(),
            table(reference, candidate, "LineNumberTable", attributes.lineNumberTablesChanged()) { it.lineNumberHash() },
            table(reference, candidate, "LocalVariableTable", attributes.localVariableTablesChanged()) { it.localVariableHash() },
            table(
                reference,
                candidate,
                "LocalVariableTypeTable",
                attributes.localVariableTypeTablesChanged(),
            ) { it.localVariableTypeHash() },
            aggregate.methods(),
            table(reference, candidate, "MethodParameters", attributes.methodParametersChanged()) { it.parameters() },
            aggregate.classes().exact() + aggregate.classes().structural(),
        )
    }

    /** Counts corresponding methods, rather than raw attribute instances, in every category. */
    private fun table(
        reference: JarIndex,
        candidate: JarIndex,
        attributeName: String,
        normalizedDifferences: Int,
        content: (MethodIndex) -> Any?,
    ): TableDifferences {
        var extra = 0
        var missing = 0
        var contentChanged = 0
        val rebuiltClasses = candidate.classes().associateBy { it.entryPath() }
        reference.classes().forEach { originalClass ->
            val rebuiltClass = rebuiltClasses[originalClass.entryPath()] ?: return@forEach
            val rebuiltMethods = rebuiltClass.methods().associateBy { it.identity() }
            originalClass.methods().forEach { original ->
                val rebuilt = rebuiltMethods[original.identity()] ?: return@forEach
                val originalPresent = original.attributes().any { it.name() == attributeName } ||
                    original.codeAttributes().any { it.name() == attributeName }
                val rebuiltPresent = rebuilt.attributes().any { it.name() == attributeName } ||
                    rebuilt.codeAttributes().any { it.name() == attributeName }
                when {
                    originalPresent && !rebuiltPresent -> missing++
                    !originalPresent && rebuiltPresent -> extra++
                    originalPresent && rebuiltPresent && content(original) != content(rebuilt) -> contentChanged++
                    else -> Unit
                }
            }
        }
        // Presence counts include empty attributes; the verifier's content fingerprint deliberately does not.
        return TableDifferences(contentChanged, normalizedDifferences, extra, missing)
    }
}

class ToolchainProbeService(
    private val cacheLayout: CacheLayout,
    private val identityProvider: CompilerToolchainIdentityProvider = CompilerToolchainIdentityProbe(),
    private val binaryIndexService: BinaryIndexService = BinaryIndexService(cacheLayout),
    private val directoryIndexer: ClassDirectoryIndexer = ClassDirectoryIndexer(),
    private val verificationStore: VerificationReportStore = VerificationReportStore(),
    private val mapper: ObjectMapper = JarIndexStore.deterministicObjectMapper(),
) {
    fun probe(
        manifest: VersionManifest,
        side: DecompilationSide,
        optionProfiles: List<CompilerOptionProfile> = CompilerOptionProfiles.all(manifest.javaVersion.majorVersion),
        force: Boolean = false,
        javaHome: Path? = null,
        progress: ((String) -> Unit)? = null,
    ): ToolchainProbeResult {
        if (side != DecompilationSide.CLIENT) throw CompilationException("Server toolchain probing awaits server reconstruction")
        require(optionProfiles.isNotEmpty()) { "At least one compiler option profile is required" }
        require(optionProfiles.map { it.id }.distinct().size == optionProfiles.size) { "Compiler option profile IDs must be unique" }

        val canonicalCompilationPath = cacheLayout.compilationReport(manifest.id, side.cliName)
        val canonicalVerificationPath = cacheLayout.verificationReport(manifest.id, side.cliName)
        val source = cacheLayout.reconstructedSourceDirectory(manifest.id, side.cliName)
        if (!Files.isRegularFile(canonicalCompilationPath, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isRegularFile(canonicalVerificationPath, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw CompilationException("Canonical compile and verification reports are required before a toolchain probe")
        }
        val canonicalCompilation = Files.newInputStream(canonicalCompilationPath).use {
            mapper.readValue(it, CompilationModel.Report::class.java)
        }
        val baselineReport = verificationStore.read(canonicalVerificationPath)
        val referenceIndex = binaryIndexService.loadOrCreate(manifest, BinarySide.CLIENT).index
        val sourceBefore = SourceTreeHasher.hash(source)
        if (sourceBefore.sha256 != canonicalCompilation.inputs().sourceTreeSha256() ||
            sourceBefore.sha256 != baselineReport.candidate().sourceTreeSha256()
        ) {
            throw CompilationException(
                "Reconstructed source identity ${sourceBefore.sha256} does not match the canonical baseline; diagnose the source change before probing",
            )
        }
        val baseline = Baseline(
            baselineReport.candidate().classTreeSha256(),
            Sha256.file(canonicalCompilationPath),
            CompilerProfileMetrics.extract(
                baselineReport,
                referenceIndex,
                directoryIndexer.index(
                    cacheLayout.compiledClassesDirectory(manifest.id, side.cliName),
                    ClassDirectorySource(manifest.id, side.cliName, "canonical-class-directory"),
                    TreeFingerprint(
                        baselineReport.candidate().classFiles(),
                        baselineReport.candidate().classTreeSha256(),
                    ),
                ),
            ),
            Sha256.file(canonicalVerificationPath),
        )
        val currentHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize()
        javaHome?.let { requested ->
            val normalized = requested.toAbsolutePath().normalize()
            val same = runCatching { Files.isSameFile(currentHome, normalized) }.getOrDefault(currentHome == normalized)
            if (!same) {
                throw CompilationException(
                    "The active compiler belongs to $currentHome, not $normalized. Launch mc-recon under the candidate JDK.",
                )
            }
        }
        val toolchain = identityProvider.identify(currentHome)
        val service = CompilerProfileService(
            cacheLayout,
            identityProvider = CompilerToolchainIdentityProvider { toolchain },
        )
        val measurements = loadCompatibleMeasurements(manifest, side, baseline, sourceBefore.sha256).associateBy {
            it.profile().id()
        }.toMutableMap()
        var classpathIdentity: String? = null

        optionProfiles.forEachIndexed { index, optionProfile ->
            progress?.invoke("[${index + 1}/${optionProfiles.size}] ${optionProfile.id}")
            val result = service.compileAndVerify(manifest, side, optionProfile, force, currentHome)
            val verification = result.verificationReport
                ?: throw CompilationException("Compiler profile ${result.profile.id()} failed with ${result.compilationReport.output().errors()} errors")
            classpathIdentity = result.compilationReport.inputs().classpathIdentitySha256()
            measurements[result.profile.id()] = ProfileMeasurement(
                requireNotNull(result.compilationReport.output().classTreeSha256()),
                Sha256.file(result.compilationReportPath),
                CompilerProfileMetrics.extract(
                    verification,
                    referenceIndex,
                    directoryIndexer.index(
                        requireNotNull(result.classesPath),
                        ClassDirectorySource(
                            manifest.id,
                            side.cliName,
                            "compiler-profile-class-directory",
                        ),
                        TreeFingerprint(
                            result.compilationReport.output().classFiles(),
                            requireNotNull(result.compilationReport.output().classTreeSha256()),
                        ),
                    ),
                ),
                result.profile,
                regressions(baselineReport, verification),
                Sha256.file(requireNotNull(result.verificationReportPath)),
            )
            requireSource(source, sourceBefore.sha256)
        }
        val classpathHash = requireNotNull(classpathIdentity) { "No compiler profile was measured" }
        val sorted = measurements.values.sortedWith(MEASUREMENT_RANKING)
        val report = ProbeReport(
            baseline,
            classpathHash,
            diagnosticCorpus(baselineReport),
            manifest.id,
            sorted,
            RANKING_PRIORITIES,
            CompilerProfileModel.PROBE_SCHEMA_VERSION,
            side.cliName,
            sourceBefore.sha256,
            CompilerProfileModel.PROBE_FORMAT_VERSION,
        )
        val path = cacheLayout.toolchainProbeReport(manifest.id, side.cliName)
        write(path, report)
        requireSource(source, sourceBefore.sha256)
        return ToolchainProbeResult(report, path)
    }

    private fun loadCompatibleMeasurements(
        manifest: VersionManifest,
        side: DecompilationSide,
        baseline: Baseline,
        sourceHash: String,
    ): List<ProfileMeasurement> {
        val path = cacheLayout.toolchainProbeReport(manifest.id, side.cliName)
        if (!Files.isRegularFile(path)) return emptyList()
        return runCatching {
            val previous = Files.newInputStream(path).use { mapper.readValue(it, ProbeReport::class.java) }
            previous.takeIf {
                it.schemaVersion() == CompilerProfileModel.PROBE_SCHEMA_VERSION &&
                    it.minecraftVersion() == manifest.id && it.side() == side.cliName &&
                    it.sourceTreeSha256() == sourceHash && it.baseline() == baseline
            }?.profiles().orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun regressions(before: Report, after: Report): RegressionSummary {
        val old = before.classes().associateBy { it.entryPath() }
        val current = after.classes().associateBy { it.entryPath() }
        if (old.keys != current.keys) throw CompilationException("Profile verification changed the class inventory")
        val exactPromotions = old.keys.filter {
            old.getValue(it).status() != Status.EXACT && current.getValue(it).status() == Status.EXACT
        }.map { current.getValue(it).binaryName() }.sorted()
        val exactRegressions = old.keys.filter {
            old.getValue(it).status() == Status.EXACT && current.getValue(it).status() != Status.EXACT
        }.map { current.getValue(it).binaryName() }.sorted()
        val comparison = VerificationComparisonEngine.compare(before, after)
        val oldChanged = changedCodeMethods(before)
        val currentChanged = changedCodeMethods(after)
        return RegressionSummary(
            exactPromotions,
            exactRegressions,
            comparison.promoted(),
            comparison.regressed(),
            (oldChanged - currentChanged).size,
            (currentChanged - oldChanged).size,
        )
    }

    private fun changedCodeMethods(report: Report): Set<String> = buildSet {
        report.classes().forEach { clazz ->
            clazz.methods().codeChangedMethods().forEach { method -> add("${clazz.entryPath()}#$method") }
        }
    }

    private fun diagnosticCorpus(report: Report): List<DiagnosticClass> {
        val classes = report.classes().associateBy { it.binaryName() }
        val selected = linkedMapOf<String, String>()
        fun add(name: String, purpose: String) {
            if (name in classes) selected.putIfAbsent(name, purpose)
        }
        add("com.mojang.blaze3d.Blaze3D", "semantically matching executable code with parameter/debug differences")
        add("com.mojang.blaze3d.audio.DeviceTracker", "existing non-package EXACT interface")
        add("com.mojang.renderpearl.backend.api.BackendRenderPipeline", "STRUCTURAL constant-pool/InnerClasses-order example")
        report.classes().firstOrNull { it.representation().stackMapChanged() > 0 }?.let {
            add(it.binaryName(), "normalized StackMapTable difference")
        }
        add("net.minecraft.world.entity.ai.behavior.SetRaidStatus", "semantic-code negative control")
        return selected.map { DiagnosticClass(it.key, it.value) }
    }

    private fun requireSource(source: Path, expectedHash: String) {
        if (SourceTreeHasher.hash(source).sha256 != expectedHash) {
            throw CompilationException("Reconstructed source changed during the toolchain probe; experiment aborted")
        }
    }

    private fun write(path: Path, report: ProbeReport) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            Files.writeString(
                temporary,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardCharsets.UTF_8,
            )
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        val RANKING_PRIORITIES = listOf(
            "maximize EXACT classes",
            "maximize EXACT plus STRUCTURAL classes",
            "avoid semantic method-code regressions against the canonical baseline",
            "maximize method debug and metadata agreement",
            "maximize semantic method-code matches",
        )

        val MEASUREMENT_RANKING: Comparator<ProfileMeasurement> =
            compareByDescending<ProfileMeasurement> { it.metrics().classes().exact() }
                .thenByDescending { it.metrics().normalizedEquivalentClasses() }
                .thenBy { it.regressions().semanticCodeRegressions() }
                .thenByDescending { it.metrics().debugMatchingMethods() }
                .thenByDescending { it.metrics().methods().metadataMatching() }
                .thenByDescending { it.metrics().methods().codeMatching() }
                .thenBy { it.profile().id() }
    }
}

object ToolchainProbeRenderer {
    fun render(result: ToolchainProbeResult, out: PrintWriter) {
        val report = result.report
        out.println("Compiler toolchain probe: Minecraft ${report.minecraftVersion()} ${report.side()}")
        out.println("Source tree: ${report.sourceTreeSha256()}")
        out.println()
        out.println(
            "Profile                                                               EXACT  STRUCT  FUNCTIONAL  COMPILES  CODE-MATCH  MP-MISS  LVT-MISS",
        )
        report.profiles().forEach { measurement ->
            val metrics = measurement.metrics()
            out.printf(
                "%-68s %,6d %,7d %,11d %,9d %,11d %,8d %,9d%n",
                measurement.profile().id(),
                metrics.classes().exact(),
                metrics.classes().structural(),
                metrics.classes().functional(),
                metrics.classes().compiles(),
                metrics.methods().codeMatching(),
                metrics.methodParameters().missing(),
                metrics.localVariableTable().missing(),
            )
        }
        out.println()
        out.println("Report: ${result.reportPath}")
        out.flush()
    }
}
