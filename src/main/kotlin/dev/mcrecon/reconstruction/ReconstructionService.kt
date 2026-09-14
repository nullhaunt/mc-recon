package dev.mcrecon.reconstruction

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mcrecon.classfile.JarIndexStore
import dev.mcrecon.classfile.Sha256
import dev.mcrecon.decompiler.DecompilationModel
import dev.mcrecon.decompiler.DecompilationModel.Provenance as DecompilationProvenance
import dev.mcrecon.decompiler.DecompilationSide
import dev.mcrecon.decompiler.DirectoryPublisher
import dev.mcrecon.decompiler.SourceTreeHasher
import dev.mcrecon.manifest.VersionManifest
import dev.mcrecon.reconstruction.ReconstructionModel.Change
import dev.mcrecon.reconstruction.ReconstructionModel.Inputs
import dev.mcrecon.reconstruction.ReconstructionModel.Output
import dev.mcrecon.reconstruction.ReconstructionModel.PatchSummary
import dev.mcrecon.reconstruction.ReconstructionModel.Provenance
import dev.mcrecon.reconstruction.ReconstructionModel.RawSource
import dev.mcrecon.reconstruction.ReconstructionModel.RuleSummary
import dev.mcrecon.resolver.CacheLayout
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class ReconstructionResult(
    val path: Path,
    val provenancePath: Path,
    val provenance: Provenance,
    val reused: Boolean,
)

class ReconstructionService(
    private val cacheLayout: CacheLayout,
    private val mapper: ObjectMapper = JarIndexStore.deterministicObjectMapper(),
    private val rules: List<ReconstructionRule> = DEFAULT_RULES,
    private val patches: List<ExactSourcePatch> = DEFAULT_PATCHES,
    private val implementationVersion: String = IMPLEMENTATION_VERSION,
    private val ruleSetVersion: String = RULE_SET_VERSION,
    private val patchSetVersion: String = PATCH_SET_VERSION,
) {
    fun reconstruct(manifest: VersionManifest, side: DecompilationSide, force: Boolean = false): ReconstructionResult {
        if (side == DecompilationSide.SERVER) {
            throw ReconstructionException("Server reconstruction awaits server bundle extraction")
        }
        val raw = cacheLayout.rawSourceDirectory(manifest.id, side.cliName)
        val decompilationPath = cacheLayout.decompilationProvenance(manifest.id, side.cliName)
        if (!Files.isDirectory(raw, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(decompilationPath)) {
            throw ReconstructionException("Raw source is unavailable. Run 'mc-recon decompile <manifest> --side ${side.cliName}' first.")
        }
        val rawBefore = SourceTreeHasher.hash(raw)
        val decompilation = Files.newInputStream(decompilationPath).use { mapper.readValue(it, DecompilationProvenance::class.java) }
        validateDecompilation(manifest, side, decompilation, rawBefore.sha256)
        val inputs = Inputs(
            manifest.id,
            patchSetVersion,
            RawSource(Sha256.file(decompilationPath), rawBefore.sha256),
            ReconstructionModel.FORMAT_VERSION,
            implementationVersion,
            ruleSetVersion,
            side.cliName,
        )
        val output = cacheLayout.reconstructedSourceDirectory(manifest.id, side.cliName)
        val provenancePath = cacheLayout.reconstructionProvenance(manifest.id, side.cliName)
        if (!force) {
            loadReusable(provenancePath, output, inputs)?.let {
                return ReconstructionResult(output, provenancePath, it, true)
            }
        }

        Files.createDirectories(output.parent)
        val staging = Files.createTempDirectory(output.parent, ".reconstructed-source.")
        try {
            copyRawTree(raw, staging)
            val changes = mutableListOf<AppliedTransformation>()
            val files = javaFiles(staging)
            val activePatches = patches.filter { it.minecraftVersion == null || it.minecraftVersion == manifest.id }
            for (file in files) {
                val relative = relative(staging, file)
                var source = Files.readString(file, StandardCharsets.UTF_8)
                rules.forEach { rule ->
                    val result = applyRule(relative, source, rule)
                    source = result.first
                    changes += result.second
                }
                activePatches.filter { it.relativePath == relative }.forEach { patch ->
                    val result = patch.apply(source)
                    source = result.first
                    changes += result.second
                }
                Files.writeString(file, source, StandardCharsets.UTF_8)
            }
            val unknownTargets = activePatches.map(ExactSourcePatch::relativePath).toSet() - files.map { relative(staging, it) }.toSet()
            if (unknownTargets.isNotEmpty()) throw ReconstructionException("Patch targets do not exist: ${unknownTargets.sorted().joinToString()}")

            val rawAfter = SourceTreeHasher.hash(raw)
            if (rawAfter != rawBefore) throw ReconstructionException("Raw source changed during reconstruction; publication aborted")
            val resultFingerprint = SourceTreeHasher.hash(staging)
            val provenance = Provenance(
                changes.map {
                    Change(it.afterSha256, it.beforeSha256, it.id, it.kind, it.sourcePath, it.target)
                }.sortedWith(compareBy(Change::sourcePath, Change::kind, Change::changeId, Change::target)),
                inputs,
                Output(resultFingerprint.fileCount, resultFingerprint.sha256),
                activePatches.map { PatchSummary(it.id, it.relativePath) }.sortedBy(PatchSummary::patchId),
                rules.map { rule ->
                    RuleSummary(changes.count { it.kind == "generic-rule" && it.id == rule.id }, rule.description, rule.id)
                }.sortedBy(RuleSummary::ruleId),
                ReconstructionModel.SCHEMA_VERSION,
            )
            val stagedProvenance = Files.createTempFile(output.parent, ".reconstruction.", ".json")
            try {
                writeJson(stagedProvenance, provenance)
                DirectoryPublisher.publishPair(staging, output, stagedProvenance, provenancePath)
            } finally {
                Files.deleteIfExists(stagedProvenance)
            }
            return ReconstructionResult(output, provenancePath, provenance, false)
        } finally {
            DirectoryPublisher.deleteTreeIfExists(staging)
        }
    }

    private fun validateDecompilation(
        manifest: VersionManifest,
        side: DecompilationSide,
        provenance: DecompilationProvenance,
        actualHash: String,
    ) {
        if (provenance.schemaVersion() != DecompilationModel.SCHEMA_VERSION ||
            provenance.inputs().minecraftVersion() != manifest.id || provenance.inputs().side() != side.cliName ||
            provenance.output().sourceTreeSha256() != actualHash || provenance.output().failedClasses().isNotEmpty()
        ) {
            throw ReconstructionException("Raw source provenance does not match Minecraft ${manifest.id} ${side.cliName}")
        }
    }

    private fun loadReusable(path: Path, output: Path, inputs: Inputs): Provenance? {
        if (!Files.isRegularFile(path) || !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) return null
        return runCatching {
            val provenance = Files.newInputStream(path).use { mapper.readValue(it, Provenance::class.java) }
            if (provenance.schemaVersion() != ReconstructionModel.SCHEMA_VERSION || provenance.inputs() != inputs) return@runCatching null
            val actual = SourceTreeHasher.hash(output)
            if (actual.fileCount != provenance.output().javaFiles() || actual.sha256 != provenance.output().sourceTreeSha256()) return@runCatching null
            provenance
        }.getOrNull()
    }

    private fun copyRawTree(raw: Path, staging: Path) {
        Files.walk(raw).use { paths ->
            paths.sorted().forEach { source ->
                if (Files.isSymbolicLink(source)) throw ReconstructionException("Raw source contains a symbolic link: ${relative(raw, source)}")
                val relative = raw.relativize(source)
                val destination = staging.resolve(relative).normalize()
                if (!destination.startsWith(staging)) throw ReconstructionException("Raw source path escapes reconstruction staging")
                when {
                    Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) -> Files.createDirectories(destination)
                    Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) -> Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES)
                    else -> throw ReconstructionException("Raw source contains unsupported entry: ${relative(raw, source)}")
                }
            }
        }
    }

    private fun javaFiles(root: Path): List<Path> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .map { file ->
                if (!file.fileName.toString().endsWith(".java")) throw ReconstructionException("Raw source contains non-Java file: ${relative(root, file)}")
                file
            }.sorted(compareBy { relative(root, it) }).toList()
    }

    private fun relative(root: Path, path: Path): String = root.relativize(path).joinToString("/") { it.toString() }

    private fun writeJson(path: Path, provenance: Provenance) {
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(provenance) + "\n", StandardCharsets.UTF_8)
    }

    companion object {
        const val IMPLEMENTATION_VERSION = "2"
        const val RULE_SET_VERSION = "2"
        const val PATCH_SET_VERSION = "2"

        val DEFAULT_RULES = listOf(
            UnavailableCompileAnnotationRule,
            BrainReturnCastRule,
            QualifiedTypeAnnotationRule,
            FunctionalInterfaceTargetRule,
        )

        val DEFAULT_PATCHES = listOf(
            ExactSourcePatch(
                "VF-CONSTRUCTOR-001",
                "com/mojang/renderpearl/backend/vulkan/init/VulkanFeature.java",
                "Move pre-delegation validation into an argument helper because Java requires this(...) first",
                """   public VulkanFeature(final VulkanPNextStruct struct, final String name) {
      String structClassName = struct.pNextStructClass().getSimpleName();
      if (!structClassName.contains("Features")) {
         throw new IllegalArgumentException(
            "Struct name \"" + structClassName + "\" does not contain \"Features\". All Vulkan features structs are expected to have \"Features\" in the name."
         );
      } else {
         this(struct, name, struct.fieldOffset(name));
      }
   }
""",
                """   public VulkanFeature(final VulkanPNextStruct struct, final String name) {
      this(struct, name, checkedOffset(struct, name));
   }

   private static long checkedOffset(final VulkanPNextStruct struct, final String name) {
      String structClassName = struct.pNextStructClass().getSimpleName();
      if (!structClassName.contains("Features")) {
         throw new IllegalArgumentException(
            "Struct name \"" + structClassName + "\" does not contain \"Features\". All Vulkan features structs are expected to have \"Features\" in the name."
         );
      }

      return struct.fieldOffset(name);
   }
""",
                "26.3-rc-2",
            ),
        ) + Minecraft263Rc2Patches.ALL
    }
}

open class ReconstructionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
