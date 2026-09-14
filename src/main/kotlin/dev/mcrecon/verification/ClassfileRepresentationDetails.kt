package dev.mcrecon.verification

import java.io.PrintWriter
import java.lang.classfile.Attribute
import java.lang.classfile.AttributedElement
import java.lang.classfile.ClassFile
import java.lang.classfile.attribute.InnerClassesAttribute

data class ClassfileRepresentationDetails(val differences: List<String>)

/** On-demand ordering diagnostics over the same JDK classfile model used by the indexer. */
class ClassfileRepresentationInspector {
    private val classFile = ClassFile.of(
        ClassFile.AttributesProcessingOption.PASS_ALL_ATTRIBUTES,
        ClassFile.DebugElementsOption.PASS_DEBUG,
        ClassFile.LineNumbersOption.PASS_LINE_NUMBERS,
    )

    fun inspect(reference: ByteArray?, candidate: ByteArray?): ClassfileRepresentationDetails {
        if (reference == null || candidate == null) return ClassfileRepresentationDetails(emptyList())
        val old = snapshot(reference)
        val rebuilt = snapshot(candidate)
        val differences = buildList {
            compare("field table order", old.fields, rebuilt.fields)
            compare("method table order", old.methods, rebuilt.methods)
            compare("class attribute order", old.classAttributes, rebuilt.classAttributes)
            compare("InnerClasses entry order", old.innerClasses, rebuilt.innerClasses)
            (old.fieldAttributes.keys + rebuilt.fieldAttributes.keys).toSortedSet().forEach { identity ->
                compare("field attribute order at $identity", old.fieldAttributes[identity], rebuilt.fieldAttributes[identity])
            }
            (old.methodAttributes.keys + rebuilt.methodAttributes.keys).toSortedSet().forEach { identity ->
                compare("method attribute order at $identity", old.methodAttributes[identity], rebuilt.methodAttributes[identity])
            }
            (old.codeAttributes.keys + rebuilt.codeAttributes.keys).toSortedSet().forEach { identity ->
                compare("Code attribute order at $identity", old.codeAttributes[identity], rebuilt.codeAttributes[identity])
            }
        }
        return ClassfileRepresentationDetails(differences)
    }

    private fun snapshot(bytes: ByteArray): Snapshot {
        val model = classFile.parse(bytes)
        val fields = model.fields().map { it.fieldName().stringValue() + it.fieldType().stringValue() }
        val methods = model.methods().map { it.methodName().stringValue() + it.methodType().stringValue() }
        val innerClasses = model.attributes().filterIsInstance<InnerClassesAttribute>().flatMap { attribute ->
            attribute.classes().map { value ->
                listOf(
                    value.innerClass().name().stringValue(),
                    value.outerClass().map { it.name().stringValue() }.orElse("<none>"),
                    value.innerName().map { it.stringValue() }.orElse("<anonymous>"),
                    value.flagsMask().toString(),
                ).joinToString("|")
            }
        }
        return Snapshot(
            model.attributeNames(),
            fields,
            model.fields().associate { field ->
                field.fieldName().stringValue() + field.fieldType().stringValue() to field.attributeNames()
            }.toSortedMap(),
            innerClasses,
            methods,
            model.methods().associate { method ->
                method.methodName().stringValue() + method.methodType().stringValue() to method.attributeNames()
            }.toSortedMap(),
            model.methods().associate { method ->
                method.methodName().stringValue() + method.methodType().stringValue() to
                    method.code().map { it.attributeNames() }.orElse(emptyList())
            }.toSortedMap(),
        )
    }

    private fun MutableList<String>.compare(label: String, reference: List<String>?, candidate: List<String>?) {
        if (reference != candidate) add("$label: reference=${reference.orEmpty()} candidate=${candidate.orEmpty()}")
    }

    private fun AttributedElement.attributeNames(): List<String> =
        attributes().map(Attribute<*>::attributeName).map { it.stringValue() }

    private data class Snapshot(
        val classAttributes: List<String>,
        val fields: List<String>,
        val fieldAttributes: Map<String, List<String>>,
        val innerClasses: List<String>,
        val methods: List<String>,
        val methodAttributes: Map<String, List<String>>,
        val codeAttributes: Map<String, List<String>>,
    )
}

object ClassfileRepresentationDetailsRenderer {
    fun render(details: ClassfileRepresentationDetails, exact: Boolean, out: PrintWriter) {
        out.println("Classfile representation-order details")
        if (exact) {
            out.println("  exact bytes already match")
        } else if (details.differences.isEmpty()) {
            out.println("  no field, method, InnerClasses, or attribute-order difference located")
            out.println("  remaining bytes are in constant-pool assignment or unmodeled attribute content/serialization")
        } else {
            details.differences.forEach { out.println("  $it") }
        }
        out.flush()
    }
}
