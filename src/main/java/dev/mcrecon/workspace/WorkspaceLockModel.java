package dev.mcrecon.workspace;

import java.util.List;

/** Portable, deterministic provenance for an editable generated workspace. */
public final class WorkspaceLockModel {
    public static final int SCHEMA_VERSION = 1;
    public static final String WORKSPACE_FORMAT_VERSION = "1";

    private WorkspaceLockModel() {}

    public record ArtifactIdentity(String mojangSha1, String sha256, long size) {}

    public record Minecraft(
            ArtifactIdentity client,
            String mainClass,
            String manifestSha256,
            String side,
            String version
    ) {}

    public record Source(int javaFiles, String reconstructedSourceTreeSha256) {}

    public record Decompiler(
            String decompilationProvenanceSha256,
            String rawSourceTreeSha256,
            String vineflowerVersion
    ) {}

    public record Reconstruction(
            String formatVersion,
            String implementationVersion,
            String patchSetVersion,
            String provenanceSha256,
            String ruleSetVersion
    ) {}

    public record CompilerProfile(
            String encoding,
            int javaMajorVersion,
            List<String> options,
            int sourceLevel,
            int targetLevel
    ) {}

    public record Dependency(
            ArtifactIdentity artifact,
            String artifactName,
            Integer compileOrder,
            String role,
            Integer runtimeOrder,
            String workspacePath
    ) {}

    public record AssetIndex(
            ArtifactIdentity artifact,
            String id,
            int uniqueObjects
    ) {}

    public record Runtime(
            String architecture,
            AssetIndex assetIndex,
            String operatingSystem
    ) {}

    public record Workspace(
            String generatorVersion,
            String gradleVersion,
            String inventoryFile,
            String portability,
            String workspaceFormatVersion
    ) {}

    public record Lock(
            CompilerProfile compilerProfile,
            List<Dependency> dependencies,
            Decompiler decompiler,
            Minecraft minecraft,
            Reconstruction reconstruction,
            Runtime runtime,
            int schemaVersion,
            Source source,
            Workspace workspace
    ) {}

    public record Inventory(
            List<String> excludedTopLevelPaths,
            int fileCount,
            String hashFormat,
            int schemaVersion,
            String sha256
    ) {}
}
