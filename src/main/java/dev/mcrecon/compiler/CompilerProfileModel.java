package dev.mcrecon.compiler;

import dev.mcrecon.verification.VerificationModel;
import java.util.List;

/** Deterministic provenance and measurements for compiler-profile experiments. */
public final class CompilerProfileModel {
    public static final int COMPILATION_SCHEMA_VERSION = 1;
    public static final String COMPILATION_FORMAT_VERSION = "1";
    public static final int PROBE_SCHEMA_VERSION = 2;
    public static final String PROBE_FORMAT_VERSION = "1";

    private CompilerProfileModel() {}

    public record ToolchainIdentity(
            String compilerFileKind,
            String compilerFileSha256,
            String identitySha256,
            String javaHome,
            String javaLauncherSha256,
            String javaRuntimeVersion,
            String javaVendor,
            String javaVersion,
            String javaVmName,
            String javacImplementation,
            String javacModuleVersion,
            String javacVersion,
            String releaseFileSha256
    ) {}

    public record CompilerProfile(
            String id,
            String optionProfileId,
            List<String> options,
            ToolchainIdentity toolchain
    ) {}

    public record CompilationInputs(
            List<CompilationModel.ClasspathArtifact> classpath,
            String classpathIdentitySha256,
            String compilationFormatVersion,
            CompilerProfile profile,
            String minecraftVersion,
            String reconstructionProvenanceSha256,
            String side,
            String sourceTreeSha256
    ) {}

    public record CompilationOutput(
            int classFiles,
            String classTreeSha256,
            int errors,
            String result,
            int sourceFiles,
            int warnings
    ) {}

    public record CompilationReport(
            List<CompilationModel.Diagnostic> diagnostics,
            List<CompilationModel.DiagnosticGroup> diagnosticGroups,
            CompilationInputs inputs,
            CompilationOutput output,
            int schemaVersion
    ) {}

    public record TableDifferences(
            int contentChanged,
            int different,
            int extra,
            int missing
    ) {}

    public record Metrics(
            VerificationModel.ClassCounts classes,
            VerificationModel.DebugAndAttributeTotals debugAndAttributes,
            int debugMatchingMethods,
            VerificationModel.ExecutableRepresentationTotals executableRepresentation,
            TableDifferences lineNumberTable,
            TableDifferences localVariableTable,
            TableDifferences localVariableTypeTable,
            VerificationModel.MethodTotals methods,
            TableDifferences methodParameters,
            int normalizedEquivalentClasses
    ) {}

    public record RegressionSummary(
            List<String> exactPromotions,
            List<String> exactRegressions,
            int promotedStatuses,
            int regressedStatuses,
            int semanticCodeImprovements,
            int semanticCodeRegressions
    ) {}

    public record ProfileMeasurement(
            String classTreeSha256,
            String compilationReportSha256,
            Metrics metrics,
            CompilerProfile profile,
            RegressionSummary regressions,
            String verificationReportSha256
    ) {}

    public record Baseline(
            String classTreeSha256,
            String compilationReportSha256,
            Metrics metrics,
            String verificationReportSha256
    ) {}

    public record DiagnosticClass(
            String binaryName,
            String purpose
    ) {}

    public record ProbeReport(
            Baseline baseline,
            String classpathIdentitySha256,
            List<DiagnosticClass> diagnosticCorpus,
            String minecraftVersion,
            List<ProfileMeasurement> profiles,
            List<String> rankingPriorities,
            int schemaVersion,
            String side,
            String sourceTreeSha256,
            String toolchainProbeFormatVersion
    ) {}
}
