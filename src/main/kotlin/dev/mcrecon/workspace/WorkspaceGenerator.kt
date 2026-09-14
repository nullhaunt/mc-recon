package dev.mcrecon.workspace

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mcrecon.classfile.JarIndexStore
import dev.mcrecon.classfile.Sha256
import dev.mcrecon.decompiler.DecompilationModel
import dev.mcrecon.decompiler.SourceTreeHasher
import dev.mcrecon.reconstruction.ReconstructionModel
import dev.mcrecon.workspace.WorkspaceLockModel.ArtifactIdentity
import dev.mcrecon.workspace.WorkspaceLockModel.AssetIndex
import dev.mcrecon.workspace.WorkspaceLockModel.CompilerProfile
import dev.mcrecon.workspace.WorkspaceLockModel.Decompiler
import dev.mcrecon.workspace.WorkspaceLockModel.Dependency
import dev.mcrecon.workspace.WorkspaceLockModel.Inventory
import dev.mcrecon.workspace.WorkspaceLockModel.Lock
import dev.mcrecon.workspace.WorkspaceLockModel.Minecraft
import dev.mcrecon.workspace.WorkspaceLockModel.Reconstruction
import dev.mcrecon.workspace.WorkspaceLockModel.Runtime
import dev.mcrecon.workspace.WorkspaceLockModel.Source
import dev.mcrecon.workspace.WorkspaceLockModel.Workspace
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

data class WorkspaceGenerationResult(
    val output: Path,
    val javaFiles: Int,
    val resourceFiles: Int,
    val dependencyArtifacts: Int,
    val assetObjects: Int,
    val workspaceFiles: Int,
    val workspaceSha256: String,
)

class WorkspaceGenerator(
    private val gradleGenerator: GradleProjectGenerator = GradleProjectGenerator(),
    private val resourceExtractor: WorkspaceResourceExtractor = WorkspaceResourceExtractor(),
    private val mapper: ObjectMapper = JarIndexStore.deterministicObjectMapper(),
) {
    fun generate(plan: WorkspacePlan, requestedOutput: Path): WorkspaceGenerationResult {
        val output = requestedOutput.toAbsolutePath().normalize()
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw WorkspaceException("Workspace output already exists; refusing to overwrite it: $output")
        }
        val parent = output.parent ?: throw WorkspaceException("Workspace output has no parent: $output")
        try {
            Files.createDirectories(parent)
        } catch (exception: Exception) {
            throw WorkspaceException("Could not create workspace output parent $parent", exception)
        }
        val staging = Files.createTempDirectory(parent, ".${output.fileName}.workspace-${UUID.randomUUID()}-")
        try {
            val sourceOutput = staging.resolve("src/main/java")
            copyTree(plan.reconstructedSource, sourceOutput, "reconstructed source")
            val copiedSource = SourceTreeHasher.hash(sourceOutput)
            val expectedSource = SourceTreeHasher.hash(plan.reconstructedSource)
            if (copiedSource != expectedSource) throw WorkspaceException("Materialized source does not match the authoritative reconstruction")

            val resources = resourceExtractor.extract(plan.resolved.client.path, staging.resolve("src/main/resources"))
            copyDependencies(plan, staging)
            val assetObjects = copyRuntime(plan, staging)
            copyWrapper(staging)
            gradleGenerator.generate(plan, staging)
            Files.createDirectories(staging.resolve("run/client"))
            writeJson(staging.resolve("reconstruction.lock.json"), lock(plan))
            validate(plan, staging, copiedSource.fileCount, resources.files, assetObjects)

            val fingerprint = WorkspaceInventoryHasher.hash(staging)
            writeJson(
                staging.resolve(INVENTORY_FILE),
                Inventory(EXCLUDED_TOP_LEVEL.toList(), fingerprint.fileCount, WorkspaceInventoryHasher.DOMAIN, 1, fingerprint.sha256),
            )
            publish(staging, output)
            return WorkspaceGenerationResult(
                output,
                copiedSource.fileCount,
                resources.files,
                plan.dependencies.size,
                assetObjects,
                fingerprint.fileCount,
                fingerprint.sha256,
            )
        } catch (exception: WorkspaceException) {
            throw exception
        } catch (exception: Exception) {
            throw WorkspaceException("Could not generate workspace at $output", exception)
        } finally {
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) deleteStaging(staging, parent)
        }
    }

    private fun copyDependencies(plan: WorkspacePlan, staging: Path) {
        plan.dependencies.forEach { dependency ->
            val destination = staging.resolve(dependency.workspacePath).normalize()
            requireInside(staging, destination, "dependency")
            Files.createDirectories(destination.parent)
            Files.copy(dependency.artifact.path, destination)
            if (Sha256.file(destination) != Sha256.file(dependency.artifact.path)) {
                throw WorkspaceException("Copied dependency failed SHA-256 validation: ${dependency.artifactName}")
            }
        }
    }

    private fun copyRuntime(plan: WorkspacePlan, staging: Path): Int {
        val assetIndex = staging.resolve(".mc-recon/assets/indexes/${plan.manifest.assetIndex.id}.json")
        Files.createDirectories(assetIndex.parent)
        Files.copy(plan.resolved.assetIndex.file.path, assetIndex)

        val uniqueAssets = plan.resolved.assetObjects.values.distinctBy { it.request.expectedSha1.lowercase() }
            .sortedBy { it.request.expectedSha1.lowercase() }
        uniqueAssets.forEach { artifact ->
            val hash = artifact.request.expectedSha1.lowercase()
            val destination = staging.resolve(".mc-recon/assets/objects/${hash.take(2)}/$hash")
            Files.createDirectories(destination.parent)
            Files.copy(artifact.path, destination)
        }

        copyTree(plan.preparedNatives, staging.resolve(".mc-recon/natives"), "prepared native runtime")
        plan.resolved.loggingConfigurations["client"]?.let { logging ->
            val destination = staging.resolve(".mc-recon/logging/client.xml")
            Files.createDirectories(destination.parent)
            Files.copy(logging.file.path, destination)
        }
        return uniqueAssets.size
    }

    private fun copyWrapper(staging: Path) {
        val resources = listOf(
            "gradlew" to "gradlew",
            "gradlew.bat" to "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.jar" to "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties" to "gradle/wrapper/gradle-wrapper.properties",
        )
        resources.forEach { (resourceName, destinationName) ->
            val classpathName = "/dev/mcrecon/workspace/wrapper/$resourceName"
            val input = javaClass.getResourceAsStream(classpathName)
                ?: throw WorkspaceException("Bundled Gradle wrapper resource is missing: $classpathName")
            val destination = staging.resolve(destinationName)
            Files.createDirectories(destination.parent)
            input.use { Files.newOutputStream(destination).use(it::transferTo) }
        }
        runCatching {
            val script = staging.resolve("gradlew")
            val permissions = Files.getPosixFilePermissions(script).toMutableSet()
            permissions += PosixFilePermission.OWNER_EXECUTE
            permissions += PosixFilePermission.GROUP_EXECUTE
            permissions += PosixFilePermission.OTHERS_EXECUTE
            Files.setPosixFilePermissions(script, permissions)
        }
    }

    private fun lock(plan: WorkspacePlan): Lock {
        val decompilation = Files.newInputStream(plan.decompilationProvenance).use {
            mapper.readValue(it, DecompilationModel.Provenance::class.java)
        }
        val reconstruction = Files.newInputStream(plan.reconstructionProvenance).use {
            mapper.readValue(it, ReconstructionModel.Provenance::class.java)
        }
        val uniqueAssets = plan.resolved.assetObjects.values.distinctBy { it.request.expectedSha1.lowercase() }.size
        return Lock(
            CompilerProfile(
                "UTF-8",
                plan.manifest.javaVersion.majorVersion,
                plan.compilerOptions,
                plan.manifest.javaVersion.majorVersion,
                plan.manifest.javaVersion.majorVersion,
            ),
            plan.dependencies.map { dependency ->
                Dependency(
                    identity(dependency.artifact), dependency.artifactName, dependency.compileOrder,
                    dependency.role, dependency.runtimeOrder, dependency.workspacePath,
                )
            },
            Decompiler(
                Sha256.file(plan.decompilationProvenance),
                decompilation.output().sourceTreeSha256(),
                decompilation.inputs().vineflower().version(),
            ),
            Minecraft(
                identity(plan.resolved.client), plan.manifest.mainClass, plan.manifestSha256,
                "client", plan.manifest.id,
            ),
            Reconstruction(
                reconstruction.inputs().reconstructionFormatVersion(),
                reconstruction.inputs().reconstructionImplementationVersion(),
                reconstruction.inputs().patchSetVersion(),
                Sha256.file(plan.reconstructionProvenance),
                reconstruction.inputs().ruleSetVersion(),
            ),
            Runtime(
                plan.platform.architecture.canonicalName,
                AssetIndex(identity(plan.resolved.assetIndex.file), plan.manifest.assetIndex.id, uniqueAssets),
                plan.platform.operatingSystem.manifestName,
            ),
            WorkspaceLockModel.SCHEMA_VERSION,
            Source(reconstruction.output().javaFiles(), reconstruction.output().sourceTreeSha256()),
            Workspace(
                GENERATOR_VERSION, GRADLE_VERSION, INVENTORY_FILE,
                "self-contained-host-platform", WorkspaceLockModel.WORKSPACE_FORMAT_VERSION,
            ),
        )
    }

    private fun identity(artifact: dev.mcrecon.resolver.ResolvedArtifact): ArtifactIdentity = ArtifactIdentity(
        artifact.request.expectedSha1.lowercase(),
        Sha256.file(artifact.path),
        artifact.request.expectedSize,
    )

    private fun validate(plan: WorkspacePlan, staging: Path, javaFiles: Int, resources: Int, assetObjects: Int) {
        listOf(
            "settings.gradle.kts", "build.gradle.kts", "gradlew", "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
            "reconstruction.lock.json", "README.md", ".gitignore", "gradle.properties",
        ).forEach { relative ->
            if (!Files.isRegularFile(staging.resolve(relative), LinkOption.NOFOLLOW_LINKS)) {
                throw WorkspaceException("Generated workspace is missing $relative")
            }
        }
        if (javaFiles != SourceTreeHasher.hash(plan.reconstructedSource).fileCount) {
            throw WorkspaceException("Generated source inventory is incomplete")
        }
        if (resources < 1) throw WorkspaceException("Generated client resource tree is empty")
        if (plan.dependencies.any { !Files.isRegularFile(staging.resolve(it.workspacePath)) }) {
            throw WorkspaceException("Generated dependency set is incomplete")
        }
        val expectedAssets = plan.resolved.assetObjects.values.distinctBy { it.request.expectedSha1.lowercase() }.size
        if (assetObjects != expectedAssets) throw WorkspaceException("Generated asset object set is incomplete")
        mapper.readTree(staging.resolve("reconstruction.lock.json").toFile())
    }

    private fun copyTree(sourceRoot: Path, destinationRoot: Path, description: String) {
        val source = sourceRoot.toAbsolutePath().normalize()
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw WorkspaceException("$description tree is unavailable: $source")
        Files.walk(source).use { paths ->
            paths.sorted().forEach { entry ->
                if (Files.isSymbolicLink(entry)) throw WorkspaceException("$description contains a symbolic link: ${source.relativize(entry)}")
                val destination = destinationRoot.resolve(source.relativize(entry)).normalize()
                requireInside(destinationRoot, destination, description)
                when {
                    Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) -> Files.createDirectories(destination)
                    Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) -> Files.copy(entry, destination)
                    else -> throw WorkspaceException("$description contains an unsupported entry: ${source.relativize(entry)}")
                }
            }
        }
    }

    private fun requireInside(root: Path, path: Path, description: String) {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedPath = path.toAbsolutePath().normalize()
        if (!normalizedPath.startsWith(normalizedRoot)) throw WorkspaceException("$description path escapes workspace staging")
    }

    private fun writeJson(path: Path, value: Any) {
        Files.createDirectories(path.parent)
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardCharsets.UTF_8)
    }

    private fun publish(staging: Path, output: Path) {
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw WorkspaceException("Workspace output appeared during generation; refusing to overwrite it: $output")
        }
        try {
            Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staging, output)
        }
    }

    private fun deleteStaging(staging: Path, allowedParent: Path) {
        val normalized = staging.toAbsolutePath().normalize()
        if (normalized.parent != allowedParent.toAbsolutePath().normalize() || !normalized.fileName.toString().contains(".workspace-")) {
            throw WorkspaceException("Refusing to remove unexpected workspace staging path $normalized")
        }
        Files.walk(normalized).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    companion object {
        const val GENERATOR_VERSION = "0.1.0-SNAPSHOT"
        const val GRADLE_VERSION = "9.1.0"
        const val INVENTORY_FILE = "workspace-manifest.json"
        val EXCLUDED_TOP_LEVEL = linkedSetOf("run", ".gradle", "build")
    }
}

object WorkspaceInventoryHasher {
    const val DOMAIN = "mc-recon-workspace-tree-v1"

    fun hash(root: Path): dev.mcrecon.compiler.TreeFingerprint {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val files = Files.walk(normalizedRoot).use { paths ->
            paths.filter { it != normalizedRoot }.map { path ->
                if (Files.isSymbolicLink(path)) throw WorkspaceException("Workspace inventory contains a symbolic link")
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return@map null
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw WorkspaceException("Workspace inventory contains an unsupported entry: ${normalizedRoot.relativize(path)}")
                }
                path
            }.filter { it != null }.map { checkNotNull(it) }.filter { path ->
                    val relative = normalizedRoot.relativize(path)
                    relative.toString().replace('\\', '/') != WorkspaceGenerator.INVENTORY_FILE &&
                        relative.firstOrNull()?.toString() !in WorkspaceGenerator.EXCLUDED_TOP_LEVEL
                }
                .map { path ->
                    if (Files.isSymbolicLink(path)) throw WorkspaceException("Workspace inventory contains a symbolic link")
                    val relative = normalizedRoot.relativize(path).joinToString("/") { it.toString() }
                    relative to path
                }
                .toList()
        }.sortedWith { left, right -> compareUnsigned(left.first.toByteArray(StandardCharsets.UTF_8), right.first.toByteArray(StandardCharsets.UTF_8)) }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$DOMAIN\u0000".toByteArray(StandardCharsets.US_ASCII))
        files.forEach { (relative, path) ->
            val pathBytes = relative.toByteArray(StandardCharsets.UTF_8)
            digest.update(longBytes(pathBytes.size.toLong()))
            digest.update(pathBytes)
            digest.update(longBytes(Files.size(path)))
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        return dev.mcrecon.compiler.TreeFingerprint(files.size, HexFormat.of().formatHex(digest.digest()))
    }

    private fun longBytes(value: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array()
    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }
}
