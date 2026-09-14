package dev.mcrecon.decompiler;

import java.util.List;
import java.util.Map;

/** Jackson-friendly deterministic provenance model for raw decompiler output. */
public final class DecompilationModel {
    public static final int SCHEMA_VERSION = 1;
    public static final String FORMAT_VERSION = "1";

    private DecompilationModel() {}

    public record ArtifactIdentity(
        String mojangSha1,
        String sha256,
        long size
    ) {}

    public record ExternalLibrary(
        String artifactName,
        String classifier,
        String role,
        ArtifactIdentity artifact
    ) {}

    public record VineflowerIdentity(
        String version,
        Map<String, String> effectiveOptions
    ) {}

    public record JavaRuntimeIdentity(
        int majorVersion,
        String manifestComponent,
        String runtimeIdentity
    ) {}

    public record Inputs(
        String decompilationFormatVersion,
        List<ExternalLibrary> externalLibraries,
        JavaRuntimeIdentity javaRuntime,
        String minecraftVersion,
        String side,
        ArtifactIdentity targetArtifact,
        VineflowerIdentity vineflower
    ) {}

    public record Diagnostic(
        String exceptionMessage,
        String exceptionType,
        String message,
        String severity
    ) {}

    public record Output(
        List<String> failedClasses,
        int javaFiles,
        String sourceTreeSha256,
        int targetClasses
    ) {}

    public record Provenance(
        List<Diagnostic> diagnostics,
        Inputs inputs,
        Output output,
        int schemaVersion,
        ToolIdentity tool
    ) {}

    public record ToolIdentity(
        String mcReconVersion
    ) {}
}
