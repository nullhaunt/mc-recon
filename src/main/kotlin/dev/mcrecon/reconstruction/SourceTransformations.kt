package dev.mcrecon.reconstruction

import dev.mcrecon.classfile.Sha256

data class TextEdit(
    val start: Int,
    val end: Int,
    val replacement: String,
    val target: String,
)

interface ReconstructionRule {
    val id: String
    val description: String
    fun edits(relativePath: String, source: String): List<TextEdit>
}

object UnavailableCompileAnnotationRule : ReconstructionRule {
    override val id = "VF-ANNOTATION-001"
    override val description = "Remove decompiled compile-time annotations whose exact artifacts are absent from the Mojang manifest"

    private val imports = setOf(
        "import javax.annotation.CheckReturnValue;",
        "import javax.annotation.concurrent.Immutable;",
        "import org.jetbrains.annotations.ApiStatus.Internal;",
        "import org.jetbrains.annotations.Contract;",
    )
    private val annotations = Regex("(?m)^[ \\t]*@(CheckReturnValue|Contract(?:\\([^\\r\\n]*\\))?|Immutable|Internal)[ \\t]*\\n")

    override fun edits(relativePath: String, source: String): List<TextEdit> = buildList {
        source.lineRanges().forEach { range ->
            val line = source.substring(range).trimEnd('\r', '\n')
            if (line in imports) add(TextEdit(range.first, range.last + 1, "", "remove unavailable import '$line'"))
        }
        annotations.findAll(source).forEach { match ->
            add(TextEdit(match.range.first, match.range.last + 1, "", "remove unavailable @${match.groupValues[1].substringBefore('(')}"))
        }
    }.nonOverlapping()
}

object BrainReturnCastRule : ReconstructionRule {
    override val id = "VF-GENERIC-001"
    override val description = "Restore erased covariant Brain return casts in entity getBrain overrides"
    private val pattern = Regex(
        "(?s)(\\bBrain<([^>\\r\\n]+)>\\s+getBrain\\s*\\(\\s*\\)\\s*\\{.*?\\breturn\\s+)super\\.getBrain\\(\\);",
    )

    override fun edits(relativePath: String, source: String): List<TextEdit> = pattern.findAll(source).map { match ->
        val type = match.groupValues[2]
        val returnStart = match.range.first + match.value.lastIndexOf("super.getBrain()")
        TextEdit(
            returnStart,
            returnStart + "super.getBrain()".length,
            "(Brain<$type>)(Brain<?>)super.getBrain()",
            "getBrain return type Brain<$type>",
        )
    }.toList()
}

object QualifiedTypeAnnotationRule : ReconstructionRule {
    override val id = "VF-ANNOTATION-002"
    override val description = "Move a type-use annotation to the simple-name segment of a qualified nested type"
    private val pattern = Regex("@Nullable ([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+)\\.([A-Z_$][\\w$]*)")

    override fun edits(relativePath: String, source: String): List<TextEdit> = pattern.findAll(source).map { match ->
        TextEdit(match.range.first, match.range.last + 1, "${match.groupValues[1]}.@Nullable ${match.groupValues[2]}", "qualified @Nullable type")
    }.toList()
}

object FunctionalInterfaceTargetRule : ReconstructionRule {
    override val id = "VF-LAMBDA-001"
    override val description = "Restore nested functional-interface target casts erased from lambda and method-reference initializers"

    private val patterns = listOf(
        Regex("(?m)^(\\s*IntegerModifier<Integer>\\s+[A-Z_]+\\s*=\\s*+)(?!\\(IntegerModifier\\.Simple\\))") to "(IntegerModifier.Simple)",
        Regex("(?m)^(\\s*FloatModifier<Float>\\s+(?:ADD|MINIMUM|MAXIMUM)\\s*=\\s*+)(?!\\(FloatModifier\\.Simple\\))") to "(FloatModifier.Simple)",
        Regex("(?s)(static <T> EntityDataSerializer<T> forValueType\\([^)]*\\) \\{\\s*return )(?!\\(EntityDataSerializer\\.ForValueType<T>\\))") to
            "(EntityDataSerializer.ForValueType<T>)",
        Regex("(?s)(static <R> LootContextArg<R> of\\([^)]*\\) \\{\\s*return )(?!\\(LootContextArg\\.SimpleGetter<R>\\))") to
            "(LootContextArg.SimpleGetter<R>)",
    )

    override fun edits(relativePath: String, source: String): List<TextEdit> = buildList {
        patterns.forEach { (pattern, insertion) ->
            pattern.findAll(source).forEach { match ->
                val location = match.groups[1]!!.range.last + 1
                add(TextEdit(location, location, insertion, "restore target $insertion"))
            }
        }
    }.nonOverlapping()
}

data class AppliedTransformation(
    val id: String,
    val kind: String,
    val sourcePath: String,
    val target: String,
    val beforeSha256: String,
    val afterSha256: String,
)

fun applyRule(relativePath: String, source: String, rule: ReconstructionRule): Pair<String, List<AppliedTransformation>> {
    val edits = rule.edits(relativePath, source).sortedByDescending(TextEdit::start).nonOverlappingDescending()
    if (edits.isEmpty()) return source to emptyList()
    var output = source
    val changes = mutableListOf<AppliedTransformation>()
    edits.forEach { edit ->
        require(edit.start in 0..edit.end && edit.end <= output.length) { "${rule.id} produced an invalid edit for $relativePath" }
        val before = output.substring(edit.start, edit.end)
        output = output.substring(0, edit.start) + edit.replacement + output.substring(edit.end)
        changes += AppliedTransformation(
            rule.id,
            "generic-rule",
            relativePath,
            edit.target,
            Sha256.text(before),
            Sha256.text(edit.replacement),
        )
    }
    return output to changes.reversed()
}

class ExactSourcePatch(
    val id: String,
    val relativePath: String,
    val description: String,
    private val expected: String,
    private val replacement: String,
    val minecraftVersion: String? = null,
) {
    fun apply(source: String): Pair<String, AppliedTransformation> {
        val exact = locate(source, expected, replacement)
            ?: locateIndented(source, expected, replacement)
        if (exact == null) {
            throw ReconstructionException("Patch $id expected exactly one matching context in $relativePath")
        }
        val (first, materializedExpected, materializedReplacement) = exact
        val output = source.replaceRange(first, first + materializedExpected.length, materializedReplacement)
        return output to AppliedTransformation(
            id,
            "explicit-patch",
            relativePath,
            description,
            Sha256.text(materializedExpected),
            Sha256.text(materializedReplacement),
        )
    }

    private fun locate(source: String, expected: String, replacement: String): LocatedPatch? {
        val first = source.indexOf(expected)
        return if (first >= 0 && source.indexOf(expected, first + 1) < 0) LocatedPatch(first, expected, replacement) else null
    }

    private fun locateIndented(source: String, expected: String, replacement: String): LocatedPatch? {
        if ('\n' !in expected) return null
        val expectedTemplate = expected.trimIndent()
        val replacementTemplate = replacement.trimIndent()
        val firstLine = expectedTemplate.substringBefore('\n')
        val matches = mutableListOf<LocatedPatch>()
        var searchFrom = 0
        while (true) {
            val start = source.indexOf(firstLine, searchFrom)
            if (start < 0) break
            val lineStart = source.lastIndexOf('\n', start - 1) + 1
            val indentation = source.substring(lineStart, start)
            if (indentation.all { it == ' ' || it == '\t' }) {
                val materializedExpected = materialize(expectedTemplate, indentation)
                if (source.startsWith(materializedExpected, start)) {
                    matches += LocatedPatch(start, materializedExpected, materialize(replacementTemplate, indentation))
                }
            }
            searchFrom = start + firstLine.length.coerceAtLeast(1)
        }
        return matches.singleOrNull()
    }

    private fun materialize(template: String, indentation: String): String {
        val lines = template.split('\n')
        return buildString {
            append(lines.first())
            lines.drop(1).forEach { line ->
                append('\n')
                if (line.isNotEmpty()) append(indentation)
                append(line)
            }
        }
    }

    private data class LocatedPatch(val start: Int, val expected: String, val replacement: String)
}

private fun String.lineRanges(): Sequence<IntRange> = sequence {
    var start = 0
    while (start < length) {
        val end = indexOf('\n', start).let { if (it < 0) length else it + 1 }
        yield(start until end)
        start = end
    }
}

private fun List<TextEdit>.nonOverlapping(): List<TextEdit> = sortedBy(TextEdit::start).also { edits ->
    edits.zipWithNext().forEach { (left, right) ->
        require(left.end <= right.start) { "Transformation emitted overlapping edits" }
    }
}

private fun List<TextEdit>.nonOverlappingDescending(): List<TextEdit> = also { edits ->
    edits.zipWithNext().forEach { (right, left) ->
        require(left.end <= right.start) { "Transformation emitted overlapping edits" }
    }
}
