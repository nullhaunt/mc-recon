package dev.mcrecon.verification;

import dev.mcrecon.classfile.IndexModel;
import dev.mcrecon.compiler.CompilationModel;
import java.util.List;

/** Deterministic, persistence-safe classfile reconstruction verification model. */
public final class VerificationModel {
    private VerificationModel() {}

    public static final int SCHEMA_VERSION = 1;
    public static final String FORMAT_VERSION = "1";

    public enum Status {
        EXACT,
        STRUCTURAL,
        FUNCTIONAL,
        COMPILES,
        MISSING,
        EXTRA
    }

    public enum AttributeChangeKind {
        ADDED,
        REMOVED,
        OPAQUE_CONTENT_CHANGED
    }

    public record ReferenceIdentity(
            String artifactKind,
            String artifactSha256,
            String mojangSha1,
            long size
    ) {}

    public record CandidateIdentity(
            int classFiles,
            String classTreeSha256,
            List<CompilationModel.ClasspathArtifact> classpath,
            String compilationFormatVersion,
            String compilationReportSha256,
            int compilationSchemaVersion,
            CompilationModel.CompilerIdentity compiler,
            List<String> compilerOptions,
            String reconstructionProvenanceSha256,
            String sourceTreeSha256
    ) {}

    public record ToolIdentity(
            int classfileIndexSchemaVersion,
            String verificationFormatVersion
    ) {}

    public record ClassCounts(
            int compiles,
            int exact,
            int extra,
            int functional,
            int missing,
            int rebuilt,
            int reference,
            int structural
    ) {}

    public record DifferenceDimensions(
            int classStructure,
            int debug,
            int exactBytesOutsideNormalizedCoverage,
            int fieldMetadata,
            int methodImplementation,
            int methodMetadata
    ) {}

    /** Percentages use the reference-class count as the denominator. */
    public record ClassPercentages(
            double compiles,
            double exact,
            double extra,
            double functional,
            double missing,
            double structural
    ) {}

    public record FieldTotals(
            int changedMetadata,
            int constantChanged,
            int corresponding,
            int extra,
            int matchingMetadata,
            int missing
    ) {}

    public record MethodTotals(
            int codeChanged,
            int codeMatching,
            int corresponding,
            int debugChanged,
            int executableCorresponding,
            int extra,
            int metadataChanged,
            int metadataMatching,
            int missing
    ) {}

    /** Representation differences counted only among semantically matching executable methods. */
    public record ExecutableRepresentationTotals(
            int exactCodeArrayChanged,
            int maxLocalsChanged,
            int maxStackChanged,
            int semanticCodeMatches,
            int stackMapChanged
    ) {}

    public record DebugAndAttributeTotals(
            int codeTypeAnnotationsChanged,
            int constantPoolOrUnmodeledSerializationClasses,
            int lineNumberTablesChanged,
            int localVariableTablesChanged,
            int localVariableTypeTablesChanged,
            int methodParametersChanged,
            int sourceFilesChanged,
            int attributeInventoryOrOpaqueDifferences
    ) {}

    /** Facets overlap; they are evidence views rather than a partition of all classes. */
    public record ClassFacet(
            String name,
            int exact,
            int total
    ) {}

    public record DifferencePattern(
            int classes,
            String pattern
    ) {}

    public record Aggregate(
            ClassCounts classes,
            ClassPercentages classPercentages,
            DebugAndAttributeTotals debugAndAttributes,
            DifferenceDimensions differenceDimensions,
            ExecutableRepresentationTotals executableRepresentation,
            List<ClassFacet> exactClassFacets,
            FieldTotals fields,
            List<DifferencePattern> mismatchPatterns,
            MethodTotals methods
    ) {}

    public record ClassDimensions(
            boolean classStructureChanged,
            boolean debugChanged,
            boolean exactBytesOutsideNormalizedCoverage,
            boolean fieldMetadataChanged,
            boolean methodImplementationChanged,
            boolean methodMetadataChanged
    ) {}

    public record FieldSummary(
            int changedMetadata,
            List<String> changedMetadataFields,
            List<String> constantChangedFields,
            int corresponding,
            int extra,
            List<String> extraFields,
            int matchingMetadata,
            int missing,
            List<String> missingFields
    ) {}

    public record MethodSummary(
            int codeChanged,
            List<String> codeChangedMethods,
            int codeMatching,
            int corresponding,
            int debugChanged,
            List<String> debugChangedMethods,
            int executableCorresponding,
            int extra,
            List<String> extraMethods,
            int metadataChanged,
            List<String> metadataChangedMethods,
            int metadataMatching,
            int missing,
            List<String> missingMethods
    ) {}

    public record AttributeDifference(
            AttributeChangeKind change,
            String candidateOpaqueHash,
            String location,
            String name,
            String referenceOpaqueHash
    ) {}

    public record RepresentationSummary(
            int codeTypeAnnotationsChanged,
            boolean constantPoolOrUnmodeledSerialization,
            int exactCodeArrayChanged,
            int lineNumberTablesChanged,
            int localVariableTablesChanged,
            int localVariableTypeTablesChanged,
            int maxLocalsChanged,
            int maxStackChanged,
            int methodParametersChanged,
            boolean sourceFileChanged,
            int stackMapChanged,
            int attributeInventoryOrOpaqueDifferences
    ) {}

    public record ClassVerification(
            List<AttributeDifference> attributeDifferences,
            String binaryName,
            String candidateBinaryName,
            String candidateExactHash,
            Long candidateSize,
            ClassDimensions differences,
            String entryPath,
            boolean exact,
            FieldSummary fields,
            MethodSummary methods,
            String referenceExactHash,
            Long referenceSize,
            RepresentationSummary representation,
            Status status
    ) {}

    public record Report(
            Aggregate aggregate,
            CandidateIdentity candidate,
            List<ClassVerification> classes,
            String minecraftVersion,
            ReferenceIdentity reference,
            int schemaVersion,
            String side,
            ToolIdentity tools,
            String verificationFormatVersion
    ) {}
}
