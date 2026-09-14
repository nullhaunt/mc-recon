package dev.mcrecon.verification

import dev.mcrecon.verification.VerificationModel.ClassVerification
import dev.mcrecon.verification.VerificationModel.Report
import dev.mcrecon.verification.VerificationModel.Status
import java.io.PrintWriter
import java.nio.file.Path
import java.util.Locale

object VerificationRenderer {
    fun render(report: Report, reportPath: Path, out: PrintWriter) {
        val aggregate = report.aggregate()
        val counts = aggregate.classes()
        out.println("Verified Minecraft ${report.minecraftVersion()} ${report.side()}")
        out.println()
        out.printf("Reference classes:  %,d%n", counts.reference())
        out.printf("Rebuilt classes:    %,d%n", counts.rebuilt())
        out.println()
        status(out, "EXACT", counts.exact(), counts.reference())
        status(out, "STRUCTURAL", counts.structural(), counts.reference())
        status(out, "FUNCTIONAL", counts.functional(), counts.reference())
        status(out, "COMPILES", counts.compiles(), counts.reference())
        out.println()
        status(out, "MISSING", counts.missing(), counts.reference())
        status(out, "EXTRA", counts.extra(), counts.reference())
        out.println()
        out.println("Method implementations:")
        out.printf("  corresponding:    %,d%n", aggregate.methods().executableCorresponding())
        out.printf("  matching:         %,d%n", aggregate.methods().codeMatching())
        out.printf("  changed:          %,d%n", aggregate.methods().codeChanged())
        out.printf("  debug changed:    %,d%n", aggregate.methods().debugChanged())
        out.println()
        out.println("Difference dimensions (overlapping classes):")
        out.printf("  structure:        %,d%n", aggregate.differenceDimensions().classStructure())
        out.printf("  field metadata:   %,d%n", aggregate.differenceDimensions().fieldMetadata())
        out.printf("  method metadata:  %,d%n", aggregate.differenceDimensions().methodMetadata())
        out.printf("  method code:      %,d%n", aggregate.differenceDimensions().methodImplementation())
        out.printf("  debug:            %,d%n", aggregate.differenceDimensions().debug())
        out.printf("  outside coverage: %,d%n", aggregate.differenceDimensions().exactBytesOutsideNormalizedCoverage())
        val patterns = aggregate.mismatchPatterns().take(5)
        if (patterns.isNotEmpty()) {
            out.println()
            out.println("Top mismatch patterns:")
            patterns.forEach { out.printf("  %,6d  %s%n", it.classes(), it.pattern()) }
        }
        out.println()
        out.println("Report:")
        out.println("  $reportPath")
        out.flush()
    }

    fun renderClass(report: Report, result: ClassVerification, reportPath: Path, out: PrintWriter) {
        out.println("Minecraft ${report.minecraftVersion()} ${report.side()}")
        out.println("Class: ${result.binaryName()}")
        out.println("Entry: ${result.entryPath()}")
        if (result.candidateBinaryName() != null && result.candidateBinaryName() != result.binaryName()) {
            out.println("Candidate binary name: ${result.candidateBinaryName()}")
        }
        out.println("Status: ${result.status()}")
        out.println("Exact bytes: ${if (result.exact()) "equal" else "different"}")
        result.referenceExactHash()?.let { out.println("Reference SHA-256: $it (${result.referenceSize()} bytes)") }
        result.candidateExactHash()?.let { out.println("Candidate SHA-256: $it (${result.candidateSize()} bytes)") }
        out.println()
        out.println("Dimensions:")
        val dimensions = result.differences()
        out.println("  class structure:  ${changed(dimensions.classStructureChanged())}")
        out.println("  field metadata:   ${changed(dimensions.fieldMetadataChanged())}")
        out.println("  method metadata:  ${changed(dimensions.methodMetadataChanged())}")
        out.println("  method code:      ${changed(dimensions.methodImplementationChanged())}")
        out.println("  debug:            ${changed(dimensions.debugChanged())}")
        out.println("  outside coverage: ${if (dimensions.exactBytesOutsideNormalizedCoverage()) "yes" else "no"}")
        out.println()
        out.println("Fields:")
        out.println("  corresponding ${result.fields().corresponding()}, matching ${result.fields().matchingMetadata()}, " +
            "changed ${result.fields().changedMetadata()}, missing ${result.fields().missing()}, extra ${result.fields().extra()}")
        list(out, "changed", result.fields().changedMetadataFields())
        list(out, "constant changed", result.fields().constantChangedFields())
        list(out, "missing", result.fields().missingFields())
        list(out, "extra", result.fields().extraFields())
        out.println()
        out.println("Methods:")
        out.println("  corresponding ${result.methods().corresponding()}, metadata matching ${result.methods().metadataMatching()}, " +
            "metadata changed ${result.methods().metadataChanged()}")
        out.println("  executable ${result.methods().executableCorresponding()}, code matching ${result.methods().codeMatching()}, " +
            "code changed ${result.methods().codeChanged()}, debug changed ${result.methods().debugChanged()}")
        list(out, "metadata changed", result.methods().metadataChangedMethods())
        list(out, "code changed", result.methods().codeChangedMethods())
        list(out, "debug changed", result.methods().debugChangedMethods())
        list(out, "missing", result.methods().missingMethods())
        list(out, "extra", result.methods().extraMethods())
        val representation = result.representation()
        out.println()
        out.println("Representation diagnostics:")
        out.println("  SourceFile:              ${if (representation.sourceFileChanged()) "changed" else "equal"}")
        out.println("  LineNumberTable methods: ${representation.lineNumberTablesChanged()}")
        out.println("  LocalVariableTable:      ${representation.localVariableTablesChanged()}")
        out.println("  LocalVariableTypeTable:  ${representation.localVariableTypeTablesChanged()}")
        out.println("  MethodParameters:        ${representation.methodParametersChanged()}")
        out.println("  StackMapTable:           ${representation.stackMapChanged()}")
        out.println("  max_stack:               ${representation.maxStackChanged()}")
        out.println("  max_locals:              ${representation.maxLocalsChanged()}")
        out.println("  raw Code arrays:         ${representation.exactCodeArrayChanged()}")
        out.println("  CP/unmodeled only:       ${if (representation.constantPoolOrUnmodeledSerialization()) "yes" else "no"}")
        if (result.attributeDifferences().isNotEmpty()) {
            out.println()
            out.println("Attribute inventory/opaque differences:")
            result.attributeDifferences().forEach { difference ->
                val hashes = buildList {
                    difference.referenceOpaqueHash()?.let { add("reference=$it") }
                    difference.candidateOpaqueHash()?.let { add("candidate=$it") }
                }
                out.println(
                    "  ${difference.change()} ${difference.location()} ${difference.name()}" +
                        if (hashes.isEmpty()) "" else " (${hashes.joinToString()})",
                )
            }
        }
        out.println()
        out.println("Report: $reportPath")
        if (result.status() == Status.FUNCTIONAL && result.methods().codeChanged() > 0) {
            out.println("Use --details with --class to render symbolic instruction differences.")
        }
        out.flush()
    }

    private fun status(out: PrintWriter, name: String, count: Int, reference: Int) {
        val percentage = if (reference == 0) 0.0 else count.toDouble() * 100.0 / reference
        out.printf(Locale.ROOT, "%-11s %,9d  %6.2f%%%n", "$name:", count, percentage)
    }

    private fun changed(value: Boolean) = if (value) "changed" else "equal"

    private fun list(out: PrintWriter, label: String, values: List<String>) {
        if (values.isEmpty()) return
        out.println("  $label:")
        values.forEach { out.println("    $it") }
    }
}
