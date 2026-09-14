package dev.mcrecon.workspace

import dev.mcrecon.classfile.JarIndexStore
import dev.mcrecon.decompiler.DecompilationModel
import dev.mcrecon.decompiler.SourceTreeHasher
import dev.mcrecon.launcher.LaunchSide
import dev.mcrecon.launcher.LaunchSpecification
import dev.mcrecon.reconstruction.ReconstructionModel
import dev.mcrecon.resolvedVersion
import dev.mcrecon.artifact
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun workspacePlan(root: Path): WorkspacePlan {
    val source = root.resolve("canonical/reconstructed-source")
    val raw = root.resolve("canonical/raw-source")
    Files.createDirectories(source.resolve("example"))
    Files.createDirectories(raw.resolve("example"))
    Files.writeString(source.resolve("example/Main.java"), "package example; public class Main { public static void main(String[] a) {} }\n")
    Files.writeString(raw.resolve("example/Main.java"), "package example; public class Main {}\n")
    val sourceFingerprint = SourceTreeHasher.hash(source)
    val rawFingerprint = SourceTreeHasher.hash(raw)

    val clientJar = root.resolve("artifacts/client.jar")
    Files.createDirectories(clientJar.parent)
    ZipOutputStream(Files.newOutputStream(clientJar)).use { zip ->
        fun entry(name: String, value: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(value.toByteArray())
            zip.closeEntry()
        }
        entry("example/Main.class", "class")
        entry("assets/test.txt", "resource\r\nbytes")
        entry("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        entry("META-INF/CLIENT.SF", "signature")
    }
    val resolved = resolvedVersion(root.resolve("resolved")).let { version ->
        val client = artifact(clientJar, Files.readAllBytes(clientJar), "client")
        version.copy(client = client)
    }

    val provenanceRoot = root.resolve("canonical")
    val decompilationPath = provenanceRoot.resolve("decompile.json")
    val reconstructionPath = provenanceRoot.resolve("reconstruction.json")
    val zeroArtifact = DecompilationModel.ArtifactIdentity("0".repeat(40), "0".repeat(64), 0)
    val decompilation = DecompilationModel.Provenance(
        emptyList(),
        DecompilationModel.Inputs(
            "1", emptyList(), DecompilationModel.JavaRuntimeIdentity(25, "java-runtime-test", "test"),
            resolved.manifest.id, "client", zeroArtifact,
            DecompilationModel.VineflowerIdentity("1.12.0", emptyMap()),
        ),
        DecompilationModel.Output(emptyList(), 1, rawFingerprint.sha256, 1),
        DecompilationModel.SCHEMA_VERSION,
        DecompilationModel.ToolIdentity("test"),
    )
    val reconstruction = ReconstructionModel.Provenance(
        emptyList(),
        ReconstructionModel.Inputs(
            resolved.manifest.id, "2", ReconstructionModel.RawSource("0".repeat(64), rawFingerprint.sha256),
            "1", "2", "2", "client",
        ),
        ReconstructionModel.Output(1, sourceFingerprint.sha256),
        emptyList(), emptyList(), ReconstructionModel.SCHEMA_VERSION,
    )
    val mapper = JarIndexStore.deterministicObjectMapper()
    Files.writeString(decompilationPath, mapper.writeValueAsString(decompilation))
    Files.writeString(reconstructionPath, mapper.writeValueAsString(reconstruction))
    val manifestPath = root.resolve("manifest.json")
    Files.writeString(manifestPath, "{}\n")
    val natives = root.resolve("prepared-natives")
    Files.createDirectories(natives.resolve("java"))
    Files.writeString(natives.resolve("native.bin"), "native")

    return WorkspacePlan(
        resolved.manifest,
        manifestPath,
        dev.mcrecon.classfile.Sha256.file(manifestPath),
        resolved.platform,
        source,
        raw,
        decompilationPath,
        reconstructionPath,
        resolved,
        emptyList(),
        natives,
        LaunchSpecification(
            LaunchSide.CLIENT, Path.of("java"), Path.of(WorkspacePlaceholders.GAME_DIRECTORY),
            listOf("-Djava.library.path=${WorkspacePlaceholders.NATIVES_DIRECTORY}"), emptyList(),
            "example.Main", listOf("--demo", "--gameDir", WorkspacePlaceholders.GAME_DIRECTORY),
        ),
        listOf("-source", "25", "-target", "25", "-encoding", "UTF-8", "-proc:none", "-g", "-parameters"),
    )
}
