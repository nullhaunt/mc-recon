package dev.mcrecon.reconstruction;

import java.util.List;

/** Deterministic provenance for the raw-source to reconstructed-source boundary. */
public final class ReconstructionModel {
    public static final int SCHEMA_VERSION = 1;
    public static final String FORMAT_VERSION = "1";

    private ReconstructionModel() {}

    public record RawSource(
        String decompilationProvenanceSha256,
        String sourceTreeSha256
    ) {}

    public record Inputs(
        String minecraftVersion,
        String patchSetVersion,
        RawSource rawSource,
        String reconstructionFormatVersion,
        String reconstructionImplementationVersion,
        String ruleSetVersion,
        String side
    ) {}

    public record RuleSummary(
        int applicationCount,
        String description,
        String ruleId
    ) {}

    public record PatchSummary(
        String patchId,
        String target
    ) {}

    public record Change(
        String afterSha256,
        String beforeSha256,
        String changeId,
        String kind,
        String sourcePath,
        String target
    ) {}

    public record Output(
        int javaFiles,
        String sourceTreeSha256
    ) {}

    public record Provenance(
        List<Change> changes,
        Inputs inputs,
        Output output,
        List<PatchSummary> patches,
        List<RuleSummary> rules,
        int schemaVersion
    ) {}
}
