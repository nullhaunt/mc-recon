package dev.mcrecon.classfile

import dev.mcrecon.classfile.DiffModel.BinaryDiff
import dev.mcrecon.classfile.DiffModel.ChangeKind
import dev.mcrecon.classfile.DiffModel.ClassChange
import dev.mcrecon.classfile.DiffModel.EntryChange
import dev.mcrecon.classfile.DiffModel.FieldChange
import dev.mcrecon.classfile.DiffModel.MethodChange
import dev.mcrecon.classfile.DiffModel.ResourceChange
import dev.mcrecon.classfile.IndexModel.ClassIndex
import dev.mcrecon.classfile.IndexModel.FieldIndex
import dev.mcrecon.classfile.IndexModel.JarEntryIndex
import dev.mcrecon.classfile.IndexModel.JarIndex
import dev.mcrecon.classfile.IndexModel.MethodIndex
import dev.mcrecon.classfile.IndexModel.ResourceIndex

class BinaryDiffException(message: String) : RuntimeException(message)

class BinaryDiffEngine {
    fun compare(before: JarIndex, after: JarIndex): BinaryDiff {
        if (before.schemaVersion() != after.schemaVersion()) {
            throw BinaryDiffException(
                "Cannot compare index schema ${before.schemaVersion()} with ${after.schemaVersion()}",
            )
        }
        return BinaryDiff(
            1,
            before.artifact(),
            after.artifact(),
            compareEntries(before.entries(), after.entries()),
            compareClasses(before.classes(), after.classes()),
            compareResources(before.resources(), after.resources()),
        )
    }

    private fun compareEntries(before: List<JarEntryIndex>, after: List<JarEntryIndex>): List<EntryChange> =
        merge(before.associateBy(JarEntryIndex::path), after.associateBy(JarEntryIndex::path)) { path, old, new ->
            EntryChange(
                path,
                old?.kind(),
                new?.kind(),
                changeKind(old, new) { a, b ->
                    a.kind() == b.kind() && a.size() == b.size() && a.entryHash() == b.entryHash()
                },
            )
        }

    private fun compareResources(before: List<ResourceIndex>, after: List<ResourceIndex>): List<ResourceChange> =
        merge(before.associateBy(ResourceIndex::path), after.associateBy(ResourceIndex::path)) { path, old, new ->
            ResourceChange(
                path,
                changeKind(old, new) { a, b -> a.size() == b.size() && a.entryHash() == b.entryHash() },
            )
        }

    private fun compareClasses(before: List<ClassIndex>, after: List<ClassIndex>): List<ClassChange> =
        merge(before.associateBy(ClassIndex::entryPath), after.associateBy(ClassIndex::entryPath)) { path, old, new ->
            when {
                old == null -> ClassChange(
                    path, requireNotNull(new).binaryName(), ChangeKind.ADDED,
                    false, false, false, false, emptyList(), emptyList(),
                )
                new == null -> ClassChange(
                    path, old.binaryName(), ChangeKind.REMOVED,
                    false, false, false, false, emptyList(), emptyList(),
                )
                old.classExactHash() == new.classExactHash() -> ClassChange(
                    path, new.binaryName(), ChangeKind.UNCHANGED,
                    false, false, false, false, emptyList(), emptyList(),
                )
                else -> changedClass(path, old, new)
            }
        }

    private fun changedClass(path: String, old: ClassIndex, new: ClassIndex): ClassChange {
        val fields = compareFields(old.fields(), new.fields())
        val methods = compareMethods(old.methods(), new.methods())
        val structureChanged = old.classStructureHash() != new.classStructureHash()
        val classDebugChanged = old.classDebugHash() != new.classDebugHash()
        val normalizedMemberChange = fields.isNotEmpty() || methods.any { !it.debugOnly() }
        val debugChange = classDebugChanged || methods.any(MethodChange::debugChanged)
        val debugOnly = !structureChanged && !normalizedMemberChange && debugChange
        val otherExactChange = !structureChanged && !normalizedMemberChange && !debugChange
        return ClassChange(
            path,
            new.binaryName(),
            ChangeKind.CHANGED,
            structureChanged,
            classDebugChanged,
            debugOnly,
            otherExactChange,
            fields,
            methods,
        )
    }

    private fun compareFields(before: List<FieldIndex>, after: List<FieldIndex>): List<FieldChange> =
        merge(before.associateBy(FieldIndex::identity), after.associateBy(FieldIndex::identity)) { _, old, new ->
            when {
                old == null -> FieldChange(
                    requireNotNull(new).name(), new.descriptor(), ChangeKind.ADDED, false, false,
                )
                new == null -> FieldChange(old.name(), old.descriptor(), ChangeKind.REMOVED, false, false)
                old.memberMetadataHash() == new.memberMetadataHash() -> null
                else -> FieldChange(
                    new.name(), new.descriptor(), ChangeKind.CHANGED, true,
                    old.constantValue() != new.constantValue(),
                )
            }
        }.filterNotNull()

    private fun compareMethods(before: List<MethodIndex>, after: List<MethodIndex>): List<MethodChange> =
        merge(before.associateBy(MethodIndex::identity), after.associateBy(MethodIndex::identity)) { _, old, new ->
            when {
                old == null -> MethodChange(
                    requireNotNull(new).name(), new.descriptor(), ChangeKind.ADDED,
                    false, false, false, false,
                )
                new == null -> MethodChange(
                    old.name(), old.descriptor(), ChangeKind.REMOVED,
                    false, false, false, false,
                )
                else -> {
                    val metadata = old.memberMetadataHash() != new.memberMetadataHash()
                    val implementation = old.methodCodeHash() != new.methodCodeHash()
                    val debug = old.debugHash() != new.debugHash()
                    if (!metadata && !implementation && !debug) null
                    else MethodChange(
                        new.name(), new.descriptor(), ChangeKind.CHANGED,
                        metadata, implementation, debug, debug && !metadata && !implementation,
                    )
                }
            }
        }.filterNotNull()

    private fun <T, R> merge(
        before: Map<String, T>,
        after: Map<String, T>,
        transform: (String, T?, T?) -> R,
    ): List<R> = (before.keys + after.keys).toSortedSet().map { key -> transform(key, before[key], after[key]) }

    private fun <T> changeKind(old: T?, new: T?, equal: (T, T) -> Boolean): ChangeKind = when {
        old == null -> ChangeKind.ADDED
        new == null -> ChangeKind.REMOVED
        equal(old, new) -> ChangeKind.UNCHANGED
        else -> ChangeKind.CHANGED
    }
}
