package dev.mcrecon.classfile

import dev.mcrecon.classfile.DiffModel.BinaryDiff
import dev.mcrecon.classfile.DiffModel.ChangeKind
import dev.mcrecon.classfile.DiffModel.MethodChange
import java.io.PrintWriter
import java.lang.classfile.ClassFile
import java.lang.classfile.Instruction
import java.lang.classfile.Label
import java.lang.classfile.instruction.ArrayLoadInstruction
import java.lang.classfile.instruction.ArrayStoreInstruction
import java.lang.classfile.instruction.BranchInstruction
import java.lang.classfile.instruction.ConstantInstruction
import java.lang.classfile.instruction.ConvertInstruction
import java.lang.classfile.instruction.DiscontinuedInstruction
import java.lang.classfile.instruction.FieldInstruction
import java.lang.classfile.instruction.IncrementInstruction
import java.lang.classfile.instruction.InvokeDynamicInstruction
import java.lang.classfile.instruction.InvokeInstruction
import java.lang.classfile.instruction.LabelTarget
import java.lang.classfile.instruction.LoadInstruction
import java.lang.classfile.instruction.LookupSwitchInstruction
import java.lang.classfile.instruction.MonitorInstruction
import java.lang.classfile.instruction.NewMultiArrayInstruction
import java.lang.classfile.instruction.NewObjectInstruction
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction
import java.lang.classfile.instruction.NewReferenceArrayInstruction
import java.lang.classfile.instruction.NopInstruction
import java.lang.classfile.instruction.OperatorInstruction
import java.lang.classfile.instruction.ReturnInstruction
import java.lang.classfile.instruction.StackInstruction
import java.lang.classfile.instruction.StoreInstruction
import java.lang.classfile.instruction.TableSwitchInstruction
import java.lang.classfile.instruction.ThrowInstruction
import java.lang.classfile.instruction.TypeCheckInstruction
import java.lang.constant.ClassDesc
import java.lang.constant.DirectMethodHandleDesc
import java.lang.constant.DynamicConstantDesc
import java.lang.constant.MethodTypeDesc
import java.nio.file.Path
import java.util.IdentityHashMap
import java.util.jar.JarFile

enum class SymbolicCodeElementKind {
    INSTRUCTION,
    EXCEPTION_HANDLER,
}

data class SymbolicCodeElement(
    val kind: SymbolicCodeElementKind,
    val ordinal: Int?,
    val text: String,
) {
    val comparisonKey: String = "${kind.name}:$text"
}

data class SymbolicMethodCode(
    val name: String,
    val descriptor: String,
    val hasCode: Boolean,
    val elements: List<SymbolicCodeElement>,
) {
    val identity: String = name + descriptor
}

/**
 * On-demand symbolic classfile inspection. This deliberately does not alter or
 * extend the persisted fingerprint model: it reads only classes selected for a
 * detailed report and resolves constant-pool entities through java.lang.classfile.
 */
class ClassfileMethodInspector {
    private val classFile = ClassFile.of(
        ClassFile.AttributesProcessingOption.PASS_ALL_ATTRIBUTES,
        ClassFile.DebugElementsOption.DROP_DEBUG,
        ClassFile.LineNumbersOption.DROP_LINE_NUMBERS,
    )

    fun inspect(bytes: ByteArray): List<SymbolicMethodCode> {
        val model = try {
            classFile.parse(bytes)
        } catch (exception: Exception) {
            throw ClassfileIndexingException("cannot parse classfile for method details: ${exception.message}", exception)
        }
        return model.methods().map { method ->
            val code = method.code().orElse(null)
            SymbolicMethodCode(
                method.methodName().stringValue(),
                method.methodType().stringValue(),
                code != null,
                code?.let(::renderCode).orEmpty(),
            )
        }.sortedWith(compareBy(SymbolicMethodCode::name, SymbolicMethodCode::descriptor))
    }

    fun inspect(jarPath: Path, binaryClassName: String, methodName: String, descriptor: String): SymbolicMethodCode {
        val entryPath = binaryClassName.replace('.', '/') + ".class"
        JarFile(jarPath.toFile(), false).use { jar ->
            val entry = jar.getJarEntry(entryPath)
                ?: throw JarIndexingException("JAR $jarPath has no class '$binaryClassName' ($entryPath)")
            val methods = jar.getInputStream(entry).use { inspect(it.readAllBytes()) }
            return methods.singleOrNull { it.name == methodName && it.descriptor == descriptor }
                ?: throw JarIndexingException(
                    "Class $binaryClassName has no method '$methodName$descriptor' in JAR $jarPath",
                )
        }
    }

    private fun renderCode(code: java.lang.classfile.CodeModel): List<SymbolicCodeElement> {
        val elements = code.elementList()
        val labels = IdentityHashMap<Label, Int>()
        var instructionOrdinal = 0
        for (element in elements) {
            when (element) {
                is LabelTarget -> labels[element.label()] = instructionOrdinal
                is Instruction -> instructionOrdinal++
                else -> Unit
            }
        }

        fun label(value: Label): String = labels[value]?.let { "L$it" }
            ?: throw ClassfileIndexingException("detailed code references a label that has no target")

        return buildList {
            elements.filterIsInstance<Instruction>().forEachIndexed { ordinal, instruction ->
                add(
                    SymbolicCodeElement(
                        SymbolicCodeElementKind.INSTRUCTION,
                        ordinal,
                        renderInstruction(instruction, ::label, ordinal),
                    ),
                )
            }
            code.exceptionHandlers().forEach { handler ->
                add(
                    SymbolicCodeElement(
                        SymbolicCodeElementKind.EXCEPTION_HANDLER,
                        null,
                        "try ${label(handler.tryStart())}..${label(handler.tryEnd())} " +
                            "catch ${handler.catchType().map(::binaryName).orElse("*")} -> ${label(handler.handler())}",
                    ),
                )
            }
        }
    }

    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    private fun renderInstruction(
        instruction: Instruction,
        label: (Label) -> String,
        ordinal: Int,
    ): String = when (instruction) {
        is ConstantInstruction -> "constant ${instruction.typeKind().name} ${renderConstant(instruction.constantValue())}"
        is LoadInstruction -> "load ${instruction.typeKind().name} local=${instruction.slot()}"
        is StoreInstruction -> "store ${instruction.typeKind().name} local=${instruction.slot()}"
        is IncrementInstruction -> "increment local=${instruction.slot()} by=${instruction.constant()}"
        is ArrayLoadInstruction -> "arrayload ${instruction.typeKind().name}"
        is ArrayStoreInstruction -> "arraystore ${instruction.typeKind().name}"
        is BranchInstruction -> "${normalizedOpcode(instruction.opcode().name).lowercase()} ${label(instruction.target())}"
        is TableSwitchInstruction -> buildString {
            append("tableswitch ").append(instruction.lowValue()).append("..").append(instruction.highValue())
            append(" default:").append(label(instruction.defaultTarget()))
            instruction.cases().sortedBy { it.caseValue() }.forEach {
                append(' ').append(it.caseValue()).append(':').append(label(it.target()))
            }
        }
        is LookupSwitchInstruction -> buildString {
            append("lookupswitch default:").append(label(instruction.defaultTarget()))
            instruction.cases().sortedBy { it.caseValue() }.forEach {
                append(' ').append(it.caseValue()).append(':').append(label(it.target()))
            }
        }
        is FieldInstruction -> "${instruction.opcode().name.lowercase()} " +
            "${binaryName(instruction.owner())}.${instruction.name().stringValue()}:${instruction.type().stringValue()}"
        is InvokeInstruction -> "${instruction.opcode().name.lowercase()} " +
            "${binaryName(instruction.owner())}.${instruction.name().stringValue()}${instruction.type().stringValue()}"
        is InvokeDynamicInstruction -> buildString {
            append("invokedynamic ").append(instruction.name().stringValue()).append(instruction.type().stringValue())
            append(" bootstrap=").append(renderMethodHandle(instruction.bootstrapMethod()))
            if (instruction.bootstrapArgs().isNotEmpty()) {
                append(" args=[")
                append(instruction.bootstrapArgs().joinToString { renderConstant(it) })
                append(']')
            }
        }
        is ConvertInstruction -> "convert ${instruction.fromType().name}->${instruction.toType().name}"
        is NewMultiArrayInstruction -> "multianewarray ${binaryName(instruction.arrayType())} dimensions=${instruction.dimensions()}"
        is NewObjectInstruction -> "new ${binaryName(instruction.className())}"
        is NewPrimitiveArrayInstruction -> "newarray ${instruction.typeKind().name}"
        is NewReferenceArrayInstruction -> "anewarray ${binaryName(instruction.componentType())}"
        is TypeCheckInstruction -> "${instruction.opcode().name.lowercase()} ${binaryName(instruction.type())}"
        is OperatorInstruction -> "${instruction.opcode().name.lowercase()} ${instruction.typeKind().name}"
        is ReturnInstruction -> "return ${instruction.typeKind().name}"
        is StackInstruction -> instruction.opcode().name.lowercase()
        is MonitorInstruction -> instruction.opcode().name.lowercase()
        is ThrowInstruction -> "athrow"
        is NopInstruction -> "nop"
        is DiscontinuedInstruction.JsrInstruction -> "jsr ${label(instruction.target())}"
        is DiscontinuedInstruction.RetInstruction -> "ret local=${instruction.slot()}"
        else -> throw ClassfileIndexingException(
            "unsupported instruction ${instruction::class.java.name} at ordinal $ordinal while rendering details",
        )
    }

    private fun renderConstant(constant: Any): String = when (constant) {
        is Int -> constant.toString()
        is Long -> "${constant}L"
        is Float -> "float(bits=0x${constant.toRawBits().toUInt().toString(16)})"
        is Double -> "double(bits=0x${constant.toRawBits().toULong().toString(16)})"
        is String -> quote(constant)
        is ClassDesc -> "class ${renderClassDesc(constant)}"
        is MethodTypeDesc -> "methodType ${constant.descriptorString()}"
        is DirectMethodHandleDesc -> renderMethodHandle(constant)
        is DynamicConstantDesc<*> -> buildString {
            append("constantDynamic ").append(constant.constantName()).append(':')
            append(constant.constantType().descriptorString())
            append(" bootstrap=").append(renderMethodHandle(constant.bootstrapMethod()))
            if (constant.bootstrapArgsList().isNotEmpty()) {
                append(" args=[")
                append(constant.bootstrapArgsList().joinToString { renderConstant(it) })
                append(']')
            }
        }
        else -> throw ClassfileIndexingException(
            "unsupported symbolic constant ${constant::class.java.name} while rendering details",
        )
    }

    private fun renderMethodHandle(handle: DirectMethodHandleDesc): String =
        "${handle.kind().name} ${renderClassDesc(handle.owner())}.${handle.methodName()}${handle.lookupDescriptor()}" +
            " (refKind=${handle.refKind()}, interface=${handle.isOwnerInterface()})"

    private fun renderClassDesc(value: ClassDesc): String {
        val descriptor = value.descriptorString()
        return if (descriptor.startsWith('L') && descriptor.endsWith(';')) {
            descriptor.substring(1, descriptor.length - 1).replace('/', '.')
        } else descriptor
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (character in value) {
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code in 0x20..0x7e) append(character)
                else append("\\u").append(character.code.toString(16).padStart(4, '0'))
            }
        }
        append('"')
    }

    private fun normalizedOpcode(opcode: String): String = when (opcode) {
        "GOTO_W" -> "GOTO"
        "JSR_W" -> "JSR"
        else -> opcode
    }

    private fun binaryName(entry: java.lang.classfile.constantpool.ClassEntry): String =
        entry.name().stringValue().replace('/', '.')
}

enum class SymbolicDiffKind {
    UNCHANGED,
    REMOVED,
    ADDED,
}

data class SymbolicCodeDiffLine(
    val kind: SymbolicDiffKind,
    val before: SymbolicCodeElement?,
    val after: SymbolicCodeElement?,
) {
    val text: String = before?.text ?: requireNotNull(after).text
}

/** Deterministic LCS-based diff over symbolic instructions and exception regions. */
object SymbolicCodeDiff {
    fun compare(before: List<SymbolicCodeElement>, after: List<SymbolicCodeElement>): List<SymbolicCodeDiffLine> {
        val cellCount = (before.size.toLong() + 1) * (after.size.toLong() + 1)
        if (cellCount > MAX_LCS_CELLS) return before.map { removed(it) } + after.map { added(it) }

        val lengths = Array(before.size + 1) { IntArray(after.size + 1) }
        for (beforeIndex in before.indices.reversed()) {
            for (afterIndex in after.indices.reversed()) {
                lengths[beforeIndex][afterIndex] =
                    if (before[beforeIndex].comparisonKey == after[afterIndex].comparisonKey) {
                        1 + lengths[beforeIndex + 1][afterIndex + 1]
                    } else {
                        maxOf(lengths[beforeIndex + 1][afterIndex], lengths[beforeIndex][afterIndex + 1])
                    }
            }
        }

        return buildList {
            var beforeIndex = 0
            var afterIndex = 0
            while (beforeIndex < before.size && afterIndex < after.size) {
                when {
                    before[beforeIndex].comparisonKey == after[afterIndex].comparisonKey -> {
                        add(SymbolicCodeDiffLine(SymbolicDiffKind.UNCHANGED, before[beforeIndex], after[afterIndex]))
                        beforeIndex++
                        afterIndex++
                    }
                    lengths[beforeIndex + 1][afterIndex] > lengths[beforeIndex][afterIndex + 1] -> {
                        add(removed(before[beforeIndex++]))
                    }
                    else -> add(added(after[afterIndex++]))
                }
            }
            while (beforeIndex < before.size) add(removed(before[beforeIndex++]))
            while (afterIndex < after.size) add(added(after[afterIndex++]))
        }
    }

    private fun removed(value: SymbolicCodeElement) =
        SymbolicCodeDiffLine(SymbolicDiffKind.REMOVED, value, null)

    private fun added(value: SymbolicCodeElement) =
        SymbolicCodeDiffLine(SymbolicDiffKind.ADDED, null, value)

    private const val MAX_LCS_CELLS = 4_000_000L
}

data class DetailedMethodChange(
    val className: String,
    val entryPath: String,
    val change: MethodChange,
    val before: SymbolicMethodCode?,
    val after: SymbolicMethodCode?,
    val codeDiff: List<SymbolicCodeDiffLine>,
)

class BinaryDiffDetailsService(
    private val inspector: ClassfileMethodInspector = ClassfileMethodInspector(),
) {
    fun inspect(beforeJar: Path, afterJar: Path, diff: BinaryDiff): List<DetailedMethodChange> {
        JarFile(beforeJar.toFile(), false).use { before ->
            JarFile(afterJar.toFile(), false).use { after ->
                return diff.classes().asSequence()
                    .filter { it.change() == ChangeKind.CHANGED }
                    .flatMap { classChange ->
                        val relevantMethods = classChange.methods().filter {
                            it.implementationChanged() || it.change() == ChangeKind.ADDED || it.change() == ChangeKind.REMOVED
                        }
                        if (relevantMethods.isEmpty()) return@flatMap emptySequence()

                        val beforeMethods = readClassMethods(before, classChange.entryPath()).associateBy(SymbolicMethodCode::identity)
                        val afterMethods = readClassMethods(after, classChange.entryPath()).associateBy(SymbolicMethodCode::identity)
                        relevantMethods.asSequence().map { methodChange ->
                            val identity = methodChange.name() + methodChange.descriptor()
                            val oldCode = beforeMethods[identity]
                            val newCode = afterMethods[identity]
                            DetailedMethodChange(
                                classChange.binaryName(),
                                classChange.entryPath(),
                                methodChange,
                                oldCode,
                                newCode,
                                SymbolicCodeDiff.compare(oldCode?.elements.orEmpty(), newCode?.elements.orEmpty()),
                            )
                        }
                    }
                    .toList()
            }
        }
    }

    private fun readClassMethods(jar: JarFile, entryPath: String): List<SymbolicMethodCode> {
        val entry = jar.getJarEntry(entryPath)
            ?: throw JarIndexingException("JAR ${jar.name} has no class entry '$entryPath' required by the detailed diff")
        return jar.getInputStream(entry).use { inspector.inspect(it.readAllBytes()) }
    }
}

object BinaryDiffDetailsRenderer {
    fun render(
        details: List<DetailedMethodChange>,
        beforeVersion: String,
        afterVersion: String,
        out: PrintWriter,
    ) {
        out.println("Method implementation details")
        out.println()
        if (details.isEmpty()) {
            out.println("  none")
            out.println()
            out.flush()
            return
        }

        for (detail in details) {
            val method = detail.change
            out.println("${detail.className}.${method.name()}${method.descriptor()}")
            out.println("  entry: ${detail.entryPath}")
            out.println("  status: ${status(method)}")
            out.println("  columns: $beforeVersion | $afterVersion")
            if (detail.before?.hasCode == false || detail.after?.hasCode == false) {
                out.println("  (one side has no Code attribute)")
            }
            for (line in displayOrder(detail.codeDiff)) {
                val prefix = when (line.kind) {
                    SymbolicDiffKind.UNCHANGED -> " "
                    SymbolicDiffKind.REMOVED -> "-"
                    SymbolicDiffKind.ADDED -> "+"
                }
                out.printf(
                    "%s %7s | %-7s %s%n",
                    prefix,
                    location(line.before),
                    location(line.after),
                    line.text,
                )
            }
            out.println()
        }
        out.flush()
    }

    private fun displayOrder(lines: List<SymbolicCodeDiffLine>): List<SymbolicCodeDiffLine> = buildList {
        var index = 0
        while (index < lines.size) {
            if (lines[index].kind == SymbolicDiffKind.UNCHANGED) {
                add(lines[index++])
                continue
            }
            val start = index
            while (index < lines.size && lines[index].kind != SymbolicDiffKind.UNCHANGED) index++
            val changed = lines.subList(start, index)
            addAll(changed.filter { it.kind == SymbolicDiffKind.REMOVED })
            addAll(changed.filter { it.kind == SymbolicDiffKind.ADDED })
        }
    }

    private fun location(element: SymbolicCodeElement?): String = when {
        element == null -> "-"
        element.kind == SymbolicCodeElementKind.EXCEPTION_HANDLER -> "handler"
        else -> requireNotNull(element.ordinal).toString()
    }

    private fun status(method: MethodChange): String = when (method.change()) {
        ChangeKind.ADDED -> "added"
        ChangeKind.REMOVED -> "removed"
        ChangeKind.CHANGED -> buildList {
            if (method.metadataChanged()) add("metadata changed")
            if (method.implementationChanged()) add("code changed")
            if (method.debugChanged()) add("debug changed")
        }.joinToString()
        ChangeKind.UNCHANGED -> "unchanged"
    }
}

object SymbolicMethodRenderer {
    fun render(className: String, method: SymbolicMethodCode, out: PrintWriter) {
        out.println("$className.${method.name}${method.descriptor}")
        if (!method.hasCode) {
            out.println("  no Code attribute")
        } else {
            for (element in method.elements) {
                val location = if (element.kind == SymbolicCodeElementKind.EXCEPTION_HANDLER) {
                    "handler"
                } else {
                    requireNotNull(element.ordinal).toString()
                }
                out.printf("  %7s  %s%n", location, element.text)
            }
        }
        out.flush()
    }
}
