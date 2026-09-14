package dev.mcrecon.verification

import dev.mcrecon.verification.VerificationComparisonModel.ClassTransition
import dev.mcrecon.verification.VerificationComparisonModel.Comparison
import dev.mcrecon.verification.VerificationComparisonModel.Direction
import dev.mcrecon.verification.VerificationComparisonModel.StatusTransition
import dev.mcrecon.verification.VerificationModel.Report
import dev.mcrecon.verification.VerificationModel.Status
import java.io.PrintWriter

object VerificationComparisonEngine {
    fun compare(before: Report, after: Report): Comparison {
        if (before.minecraftVersion() != after.minecraftVersion() || before.side() != after.side() ||
            before.reference().artifactSha256() != after.reference().artifactSha256()
        ) {
            throw VerificationException("Verification reports do not describe the same Minecraft reference artifact")
        }
        val old = before.classes().associateBy { it.entryPath() }
        val current = after.classes().associateBy { it.entryPath() }
        if (old.keys != current.keys) throw VerificationException("Verification reports have different class inventories")
        val changed = old.keys.sorted().mapNotNull { path ->
            val first = old.getValue(path)
            val second = current.getValue(path)
            if (first.status() == second.status()) null
            else ClassTransition(
                second.status(), first.status(), second.binaryName(), direction(first.status(), second.status()), path,
            )
        }
        val transitions = changed.groupBy { Triple(it.before(), it.after(), it.direction()) }
            .map { (key, values) -> StatusTransition(key.second, key.first, values.size, key.third) }
            .sortedWith(compareBy(StatusTransition::direction, StatusTransition::before, StatusTransition::after))
        return Comparison(
            after.aggregate().classes().exact(),
            after.candidate().classTreeSha256(),
            before.aggregate().classes().exact(),
            before.candidate().classTreeSha256(),
            changed,
            before.minecraftVersion(),
            changed.count { it.direction() == Direction.PROMOTED },
            before.reference().artifactSha256(),
            changed.count { it.direction() == Direction.REGRESSED },
            before.side(),
            transitions,
        )
    }

    private fun direction(before: Status, after: Status): Direction {
        val old = rank(before)
        val current = rank(after)
        return when {
            old == null || current == null -> Direction.CHANGED
            current > old -> Direction.PROMOTED
            current < old -> Direction.REGRESSED
            else -> Direction.CHANGED
        }
    }

    private fun rank(status: Status): Int? = when (status) {
        Status.COMPILES -> 0
        Status.FUNCTIONAL -> 1
        Status.STRUCTURAL -> 2
        Status.EXACT -> 3
        Status.MISSING, Status.EXTRA -> null
    }
}

object VerificationComparisonRenderer {
    fun render(comparison: Comparison, out: PrintWriter) {
        out.println("Verification comparison: Minecraft ${comparison.minecraftVersion()} ${comparison.side()}")
        out.println()
        out.println("EXACT:")
        out.printf("  before:    %,d%n", comparison.beforeExact())
        out.printf("  after:     %,d%n", comparison.afterExact())
        out.printf("  delta:     %+,d%n", comparison.afterExact() - comparison.beforeExact())
        out.println()
        out.printf("Promoted:    %,d%n", comparison.promoted())
        out.printf("Regressed:   %,d%n", comparison.regressed())
        if (comparison.transitions().isNotEmpty()) {
            out.println()
            out.println("Transitions:")
            comparison.transitions().forEach {
                out.printf("  %,6d  %s -> %s (%s)%n", it.classes(), it.before(), it.after(), it.direction().name.lowercase())
            }
        }
        out.flush()
    }
}
