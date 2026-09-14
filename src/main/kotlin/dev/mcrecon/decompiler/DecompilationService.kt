package dev.mcrecon.decompiler

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mcrecon.classfile.JarIndexStore
import dev.mcrecon.decompiler.DecompilationModel.ArtifactIdentity
import dev.mcrecon.decompiler.DecompilationModel.ExternalLibrary
import dev.mcrecon.decompiler.DecompilationModel.Inputs
import dev.mcrecon.decompiler.DecompilationModel.JavaRuntimeIdentity
import dev.mcrecon.decompiler.DecompilationModel.Output
import dev.mcrecon.decompiler.DecompilationModel.Provenance
import dev.mcrecon.decompiler.DecompilationModel.ToolIdentity
import dev.mcrecon.decompiler.DecompilationModel.VineflowerIdentity
import dev.mcrecon.manifest.VersionManifest
import dev.mcrecon.manifest.rules.HostPlatform
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.resolver.ResolvedArtifact
import dev.mcrecon.resolver.VersionResolver
import dev.mcrecon.runtime.JavaRuntime
import dev.mcrecon.runtime.JavaRuntimeSelector
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile

enum class DecompilationSide(val cliName: String) {
    CLIENT("client"),
    SERVER("server"),
}

data class DecompilationResult(
    val path: Path,
    val provenance: Provenance,
    val reused: Boolean,
)

class DecompilationService(
    private val cacheLayout: CacheLayout,
    private val resolver: VersionResolver = VersionResolver(cacheLayout),
    private val javaSelector: JavaRuntimeSelector = JavaRuntimeSelector(),
    private val engine: RawDecompilerEngine = VineflowerEngine(),
    private val mapper: ObjectMapper = JarIndexStore.deterministicObjectMapper(),
) {
    fun decompile(
        manifest: VersionManifest,
        platform: HostPlatform,
        side: DecompilationSide,
        force: Boolean = false,
        javaExecutable: Path? = null,
    ): DecompilationResult {
        if (side == DecompilationSide.SERVER) {
            throw DecompilationException(
                "Server implementation decompilation is not available yet because the published server artifact is a bundler. " +
                    "Server bundle extraction is a separate milestone.",
            )
        }

        val resolved = resolver.resolveClientInputsCached(manifest, platform)
        val runtime = javaSelector.select(manifest.javaVersion.majorVersion, javaExecutable)
        val javaHome = javaHome(runtime)
        val inputs = Inputs(
            DecompilationModel.FORMAT_VERSION,
            resolved.libraries.flatMap { library ->
                library.artifacts.filter { it.role != dev.mcrecon.resolver.LibraryArtifactRole.NATIVE }.map { artifact ->
                    ExternalLibrary(
                        library.metadata.name,
                        artifact.classifier,
                        artifact.role.name.lowercase(),
                        identity(artifact.file),
                    )
                }
            },
            JavaRuntimeIdentity(runtime.majorVersion, manifest.javaVersion.component, runtime.versionDescription),
            manifest.id,
            side.cliName,
            identity(resolved.client),
            VineflowerIdentity(VineflowerConfiguration.VERSION, VineflowerConfiguration.provenanceOptions()),
        )
        val targetClasses = countClasses(resolved.client.path)
        val rawSource = cacheLayout.rawSourceDirectory(manifest.id, side.cliName)
        val provenancePath = cacheLayout.decompilationProvenance(manifest.id, side.cliName)
        val cache = DecompilationCache(mapper)
        if (!force) {
            cache.loadReusable(provenancePath, rawSource, inputs, targetClasses)?.let {
                return DecompilationResult(rawSource, it, true)
            }
        }

        Files.createDirectories(rawSource.parent)
        val staging = Files.createTempDirectory(rawSource.parent, ".raw-source.")
        try {
            val execution = engine.decompile(
                DecompilerInvocation(
                    resolved.classpathLibraries.map { it.file.path.toAbsolutePath().normalize() },
                    javaHome,
                    staging,
                    resolved.client.path.toAbsolutePath().normalize(),
                ),
            )
            val errors = execution.diagnostics.filter { it.severity() == "ERROR" }
            if (execution.failedClasses.isNotEmpty() || errors.isNotEmpty()) {
                val detail = buildList {
                    if (execution.failedClasses.isNotEmpty()) add("failed classes: ${execution.failedClasses.take(10).joinToString()}")
                    if (errors.isNotEmpty()) add("errors: ${errors.take(3).joinToString { it.message() }}")
                }.joinToString("; ")
                throw DecompilationException("Vineflower did not produce a complete source tree ($detail)")
            }
            requireJavaOnly(staging)
            val fingerprint = SourceTreeHasher.hash(staging)
            if (targetClasses > 0 && fingerprint.fileCount == 0) {
                throw DecompilationException("Vineflower emitted no Java files for $targetClasses target classes")
            }
            val provenance = Provenance(
                execution.diagnostics,
                inputs,
                Output(execution.failedClasses.sorted(), fingerprint.fileCount, fingerprint.sha256, targetClasses),
                DecompilationModel.SCHEMA_VERSION,
                ToolIdentity(MC_RECON_VERSION),
            )
            cache.publish(provenancePath, rawSource, staging, provenance)
            return DecompilationResult(rawSource, provenance, false)
        } finally {
            DirectoryPublisher.deleteTreeIfExists(staging)
        }
    }

    private fun identity(artifact: ResolvedArtifact): ArtifactIdentity = ArtifactIdentity(
        artifact.request.expectedSha1.lowercase(),
        sha256(artifact.path),
        artifact.request.expectedSize,
    )

    private fun javaHome(runtime: JavaRuntime): Path {
        val home = runtime.executable.parent?.parent?.toAbsolutePath()?.normalize()
            ?: throw DecompilationException("Cannot determine Java home from selected executable ${runtime.executable}")
        if (!Files.isRegularFile(home.resolve("lib").resolve("jrt-fs.jar"))) {
            throw DecompilationException("Selected Java runtime has no readable lib/jrt-fs.jar: $home")
        }
        return home
    }

    private fun countClasses(jar: Path): Int = try {
        ZipFile(jar.toFile()).use { zip -> zip.entries().asSequence().count { !it.isDirectory && it.name.endsWith(".class") } }
    } catch (exception: Exception) {
        throw DecompilationException("Could not inspect target class inventory in $jar", exception)
    }

    private fun requireJavaOnly(root: Path) {
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { file ->
                if (!file.fileName.toString().endsWith(".java")) {
                    throw DecompilationException("Raw source output contains a non-Java file: ${root.relativize(file)}")
                }
            }
        }
    }

    companion object {
        const val MC_RECON_VERSION = "0.1.0-SNAPSHOT"

        private fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return HexFormat.of().formatHex(digest.digest())
        }
    }
}

internal class DecompilationCache(private val mapper: ObjectMapper = JarIndexStore.deterministicObjectMapper()) {
    fun loadReusable(path: Path, rawSource: Path, inputs: Inputs, targetClasses: Int): Provenance? {
        if (!Files.isRegularFile(path) || !Files.isDirectory(rawSource, LinkOption.NOFOLLOW_LINKS)) return null
        return runCatching {
            val candidate = Files.newInputStream(path).use { mapper.readValue(it, Provenance::class.java) }
            if (candidate.schemaVersion() != DecompilationModel.SCHEMA_VERSION || candidate.inputs() != inputs) return@runCatching null
            if (candidate.output().failedClasses().isNotEmpty() || candidate.output().targetClasses() != targetClasses) return@runCatching null
            if (candidate.diagnostics().any { it.severity() == "ERROR" }) return@runCatching null
            Files.walk(rawSource).use { paths ->
                val javaOnly = paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .allMatch { it.fileName.toString().endsWith(".java") }
                if (!javaOnly) return@runCatching null
            }
            val actual = SourceTreeHasher.hash(rawSource)
            if (actual.fileCount != candidate.output().javaFiles() || actual.sha256 != candidate.output().sourceTreeSha256()) {
                return@runCatching null
            }
            candidate
        }.getOrNull()
   }

    fun write(path: Path, provenance: Provenance) {
        Files.createDirectories(path.parent)
        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(provenance) + "\n"
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8)
            moveReplacing(temporary, path)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun publish(path: Path, rawSource: Path, stagedSource: Path, provenance: Provenance) {
        Files.createDirectories(path.parent)
        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(provenance) + "\n"
        val stagedProvenance = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            Files.writeString(stagedProvenance, json, StandardCharsets.UTF_8)
            DirectoryPublisher.publishPair(stagedSource, rawSource, stagedProvenance, path)
        } finally {
            Files.deleteIfExists(stagedProvenance)
        }
    }
}

internal object DirectoryPublisher {
    fun publish(staging: Path, destination: Path) {
        publishPair(staging, destination, null, null)
    }

    fun publishPair(staging: Path, destination: Path, stagedCompanion: Path?, companion: Path?) {
        val parent = destination.toAbsolutePath().normalize().parent
            ?: throw DecompilationException("Raw source destination has no parent: $destination")
        val normalizedStaging = staging.toAbsolutePath().normalize()
        val normalizedDestination = destination.toAbsolutePath().normalize()
        if (normalizedStaging.parent != parent || normalizedDestination.parent != parent || normalizedStaging == normalizedDestination) {
            throw DecompilationException("Raw source publication must use distinct sibling directories")
        }
        if ((stagedCompanion == null) != (companion == null)) {
            throw DecompilationException("Companion publication requires both staging and destination paths")
        }
        val normalizedStagedCompanion = stagedCompanion?.toAbsolutePath()?.normalize()
        val normalizedCompanion = companion?.toAbsolutePath()?.normalize()
        if (normalizedStagedCompanion != null &&
            (normalizedStagedCompanion.parent != parent || normalizedCompanion?.parent != parent || normalizedStagedCompanion == normalizedCompanion)
        ) {
            throw DecompilationException("Companion publication must use distinct sibling files")
        }
        var sourceBackup: Path? = null
        var companionBackup: Path? = null
        var sourcePublished = false
        var companionPublished = false
        try {
            if (Files.exists(normalizedDestination, LinkOption.NOFOLLOW_LINKS)) {
                sourceBackup = Files.createTempDirectory(parent, ".raw-source-backup.")
                Files.delete(sourceBackup)
                moveReplacing(normalizedDestination, sourceBackup)
            }
            if (normalizedCompanion != null && Files.exists(normalizedCompanion, LinkOption.NOFOLLOW_LINKS)) {
                companionBackup = Files.createTempFile(parent, ".decompile-backup.", ".json")
                Files.delete(companionBackup)
                moveReplacing(normalizedCompanion, companionBackup)
            }
            moveReplacing(normalizedStaging, normalizedDestination)
            sourcePublished = true
            if (normalizedStagedCompanion != null && normalizedCompanion != null) {
                moveReplacing(normalizedStagedCompanion, normalizedCompanion)
                companionPublished = true
            }
            sourceBackup?.let { runCatching { deleteTreeIfExists(it) } }
            companionBackup?.let { runCatching { Files.deleteIfExists(it) } }
        } catch (exception: DecompilationException) {
            throw exception
        } catch (exception: Exception) {
            if (companionPublished && normalizedCompanion != null) Files.deleteIfExists(normalizedCompanion)
            if (companionBackup != null && normalizedCompanion != null && Files.exists(companionBackup, LinkOption.NOFOLLOW_LINKS)) {
                moveReplacing(companionBackup, normalizedCompanion)
            }
            if (sourcePublished) deleteTreeIfExists(normalizedDestination)
            if (sourceBackup != null && Files.exists(sourceBackup, LinkOption.NOFOLLOW_LINKS)) {
                moveReplacing(sourceBackup, normalizedDestination)
            }
            throw DecompilationException("Could not publish raw source tree to $normalizedDestination", exception)
        }
    }

    fun deleteTreeIfExists(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        val normalized = path.toAbsolutePath().normalize()
        Files.walk(normalized).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { entry ->
                if (!entry.toAbsolutePath().normalize().startsWith(normalized)) {
                    throw DecompilationException("Refusing to delete an entry outside staging tree $normalized")
                }
                Files.deleteIfExists(entry)
            }
        }
    }
}

private fun moveReplacing(source: Path, destination: Path) {
    try {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }
}
