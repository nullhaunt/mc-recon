package dev.mcrecon.classfile

import dev.mcrecon.classfile.DiffModel.BinaryDiff
import dev.mcrecon.classfile.DiffModel.ChangeKind
import java.io.PrintWriter

object BinaryDiffRenderer {
    fun render(diff: BinaryDiff, out: PrintWriter) {
        out.println("Minecraft ${diff.before().minecraftVersion()} -> ${diff.after().minecraftVersion()}")
        out.println("Side: ${diff.after().side()}${if (diff.after().artifactKind() == "server-distribution") " (published distribution/bundler)" else ""}")
        out.println()
        summary(out, "Archive", diff.archiveEntries().map { it.change() })
        summary(out, "Classes", diff.classes().map { it.change() })

        val fields = diff.classes().flatMap { it.fields() }
        out.println("Fields")
        out.println("  added:       ${fields.count { it.change() == ChangeKind.ADDED }}")
        out.println("  removed:     ${fields.count { it.change() == ChangeKind.REMOVED }}")
        out.println("  metadata:    ${fields.count { it.metadataChanged() }}")
        out.println()

        val methods = diff.classes().flatMap { it.methods() }
        out.println("Methods")
        out.println("  added:       ${methods.count { it.change() == ChangeKind.ADDED }}")
        out.println("  removed:     ${methods.count { it.change() == ChangeKind.REMOVED }}")
        out.println("  metadata:    ${methods.count { it.metadataChanged() }}")
        out.println("  code:        ${methods.count { it.implementationChanged() }}")
        out.println("  debug-only:  ${methods.count { it.debugOnly() }}")
        out.println()
        summary(out, "Resources", diff.resources().map { it.change() }, includeUnchanged = false)

        val changedClasses = diff.classes().filter { it.change() != ChangeKind.UNCHANGED }
        if (changedClasses.isNotEmpty()) {
            out.println("Changed classes")
            out.println()
            for (change in changedClasses) {
                when (change.change()) {
                    ChangeKind.ADDED -> out.println("+ ${change.binaryName()}")
                    ChangeKind.REMOVED -> out.println("- ${change.binaryName()}")
                    ChangeKind.CHANGED -> {
                        out.println(change.binaryName())
                        if (change.debugOnly()) out.println("  debug metadata only")
                        else {
                            if (change.structureChanged()) out.println("  class structure changed")
                            if (change.classDebugChanged()) out.println("  class debug metadata changed")
                            if (change.otherExactChange()) out.println("  exact bytes changed (outside normalized coverage)")
                        }
                        for (field in change.fields()) {
                            val prefix = when (field.change()) {
                                ChangeKind.ADDED -> "+"
                                ChangeKind.REMOVED -> "-"
                                else -> "F"
                            }
                            val reason = when {
                                field.constantChanged() -> " (constant/metadata changed)"
                                field.metadataChanged() -> " (metadata changed)"
                                else -> ""
                            }
                            out.println("  $prefix ${field.name()}:${field.descriptor()}$reason")
                        }
                        for (method in change.methods()) {
                            val prefix = when (method.change()) {
                                ChangeKind.ADDED -> "+"
                                ChangeKind.REMOVED -> "-"
                                else -> "M"
                            }
                            val reasons = buildList {
                                if (method.metadataChanged()) add("metadata")
                                if (method.implementationChanged()) add("code")
                                if (method.debugChanged()) add(if (method.debugOnly()) "debug-only" else "debug")
                            }
                            out.println("  $prefix ${method.name()}${method.descriptor()}${if (reasons.isEmpty()) "" else " (${reasons.joinToString() } changed)"}")
                        }
                    }
                    ChangeKind.UNCHANGED -> Unit
                }
                out.println()
            }
        }
        out.flush()
    }

    private fun summary(
        out: PrintWriter,
        title: String,
        changes: List<ChangeKind>,
        includeUnchanged: Boolean = true,
    ) {
        out.println(title)
        out.println("  added:       ${changes.count { it == ChangeKind.ADDED }}")
        out.println("  removed:     ${changes.count { it == ChangeKind.REMOVED }}")
        out.println("  changed:     ${changes.count { it == ChangeKind.CHANGED }}")
        if (includeUnchanged) out.println("  unchanged:   ${changes.count { it == ChangeKind.UNCHANGED }}")
        out.println()
    }
}
