package dev.mcrecon.classfile;

import java.util.List;

/** Structured binary diff model used independently of the CLI renderer. */
public final class DiffModel {
    private DiffModel() {}

    public enum ChangeKind {
        ADDED,
        REMOVED,
        CHANGED,
        UNCHANGED
    }

    public record EntryChange(
            String path,
            IndexModel.EntryKind beforeKind,
            IndexModel.EntryKind afterKind,
            ChangeKind change
    ) {}

    public record ResourceChange(
            String path,
            ChangeKind change
    ) {}

    public record FieldChange(
            String name,
            String descriptor,
            ChangeKind change,
            boolean metadataChanged,
            boolean constantChanged
    ) {}

    public record MethodChange(
            String name,
            String descriptor,
            ChangeKind change,
            boolean metadataChanged,
            boolean implementationChanged,
            boolean debugChanged,
            boolean debugOnly
    ) {}

    public record ClassChange(
            String entryPath,
            String binaryName,
            ChangeKind change,
            boolean structureChanged,
            boolean classDebugChanged,
            boolean debugOnly,
            boolean otherExactChange,
            List<FieldChange> fields,
            List<MethodChange> methods
    ) {}

    public record BinaryDiff(
            int schemaVersion,
            IndexModel.ArtifactIdentity before,
            IndexModel.ArtifactIdentity after,
            List<EntryChange> archiveEntries,
            List<ClassChange> classes,
            List<ResourceChange> resources
    ) {}
}
