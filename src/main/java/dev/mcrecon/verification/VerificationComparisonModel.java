package dev.mcrecon.verification;

import java.util.List;

/** Deterministic comparison between two verification baselines for the same reference artifact. */
public final class VerificationComparisonModel {
    private VerificationComparisonModel() {}

    public enum Direction {
        PROMOTED,
        REGRESSED,
        CHANGED
    }

    public record StatusTransition(
            VerificationModel.Status after,
            VerificationModel.Status before,
            int classes,
            Direction direction
    ) {}

    public record ClassTransition(
            VerificationModel.Status after,
            VerificationModel.Status before,
            String binaryName,
            Direction direction,
            String entryPath
    ) {}

    public record Comparison(
            int afterExact,
            String afterRebuiltClassTreeSha256,
            int beforeExact,
            String beforeRebuiltClassTreeSha256,
            List<ClassTransition> changedClasses,
            String minecraftVersion,
            int promoted,
            String referenceArtifactSha256,
            int regressed,
            String side,
            List<StatusTransition> transitions
    ) {}
}
