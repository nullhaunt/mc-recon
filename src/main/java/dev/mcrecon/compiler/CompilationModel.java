package dev.mcrecon.compiler;

import java.util.List;

/** Deterministic persisted model for one javac source-tree compilation. */
public final class CompilationModel {
    public static final int SCHEMA_VERSION = 1;
    public static final String FORMAT_VERSION = "1";

    private CompilationModel() {}

    public record ArtifactIdentity(String mojangSha1, String sha256, long size) {}

    public record ClasspathArtifact(
        String artifactName,
        ArtifactIdentity artifact
    ) {}

    public record CompilerIdentity(
        String implementation,
        int majorVersion,
        String runtimeIdentity
    ) {}

    public record Inputs(
        List<ClasspathArtifact> classpath,
        CompilerIdentity compiler,
        String compilationFormatVersion,
        String minecraftVersion,
        List<String> options,
        String side,
        String sourceTreeSha256
    ) {}

    public record Diagnostic(
        long column,
        String diagnosticCode,
        long line,
        String message,
        String severity,
        String sourcePath
    ) {}

    public record DiagnosticGroup(
        int count,
        String diagnosticCode,
        String severity
    ) {}

    public record Output(
        int classFiles,
        String classTreeSha256,
        int errors,
        String result,
        int sourceFiles,
        int warnings
    ) {}

    public record Report(
        List<Diagnostic> diagnostics,
        List<DiagnosticGroup> diagnosticGroups,
        Inputs inputs,
        Output output,
        int schemaVersion
    ) {}
}
