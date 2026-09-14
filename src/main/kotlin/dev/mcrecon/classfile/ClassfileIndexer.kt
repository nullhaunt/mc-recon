package dev.mcrecon.classfile

import dev.mcrecon.classfile.IndexModel.AttributeIndex
import dev.mcrecon.classfile.IndexModel.ClassIndex
import dev.mcrecon.classfile.IndexModel.EnclosingMethodIndex
import dev.mcrecon.classfile.IndexModel.FieldIndex
import dev.mcrecon.classfile.IndexModel.InnerClassIndex
import dev.mcrecon.classfile.IndexModel.MethodIndex
import dev.mcrecon.classfile.IndexModel.MethodParameterIndex
import dev.mcrecon.classfile.IndexModel.RecordComponentIndex
import java.lang.classfile.Annotation
import java.lang.classfile.AnnotationValue
import java.lang.classfile.Attribute
import java.lang.classfile.AttributedElement
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassModel
import java.lang.classfile.CodeModel
import java.lang.classfile.Instruction
import java.lang.classfile.Label
import java.lang.classfile.TypeAnnotation
import java.lang.classfile.attribute.AnnotationDefaultAttribute
import java.lang.classfile.attribute.ConstantValueAttribute
import java.lang.classfile.attribute.EnclosingMethodAttribute
import java.lang.classfile.attribute.ExceptionsAttribute
import java.lang.classfile.attribute.InnerClassesAttribute
import java.lang.classfile.attribute.MethodParametersAttribute
import java.lang.classfile.attribute.NestHostAttribute
import java.lang.classfile.attribute.NestMembersAttribute
import java.lang.classfile.attribute.PermittedSubclassesAttribute
import java.lang.classfile.attribute.RecordAttribute
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute
import java.lang.classfile.attribute.RuntimeInvisibleTypeAnnotationsAttribute
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute
import java.lang.classfile.attribute.SignatureAttribute
import java.lang.classfile.attribute.SourceDebugExtensionAttribute
import java.lang.classfile.attribute.SourceFileAttribute
import java.lang.classfile.attribute.CodeAttribute
import java.lang.classfile.attribute.StackMapFrameInfo
import java.lang.classfile.attribute.StackMapTableAttribute
import java.lang.classfile.attribute.UnknownAttribute
import java.lang.classfile.constantpool.ClassEntry
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
import java.lang.classfile.instruction.LineNumber
import java.lang.classfile.instruction.LoadInstruction
import java.lang.classfile.instruction.LocalVariable
import java.lang.classfile.instruction.LocalVariableType
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
import java.util.IdentityHashMap

class ClassfileIndexingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Parses one class exactly once and builds exact, structural, code, and debug identities. */
class ClassfileIndexer {
    private val classFile = ClassFile.of(
        ClassFile.AttributesProcessingOption.PASS_ALL_ATTRIBUTES,
        ClassFile.DebugElementsOption.PASS_DEBUG,
        ClassFile.LineNumbersOption.PASS_LINE_NUMBERS,
    )

    fun index(bytes: ByteArray, entryPath: String): ClassIndex {
        val model = try {
            classFile.parse(bytes)
        } catch (exception: Exception) {
            throw ClassfileIndexingException("cannot parse classfile: ${exception.message}", exception)
        }

        return try {
            buildIndex(model, bytes, entryPath)
        } catch (exception: ClassfileIndexingException) {
            throw exception
        } catch (exception: Exception) {
            val className = runCatching { binaryName(model.thisClass()) }.getOrDefault("<unreadable>")
            throw ClassfileIndexingException("cannot index class $className: ${exception.message}", exception)
        }
    }

    private fun buildIndex(model: ClassModel, bytes: ByteArray, entryPath: String): ClassIndex {
        val fields = model.fields().map(::indexField).sortedWith(compareBy(FieldIndex::name, FieldIndex::descriptor))
        val methods = model.methods().map(::indexMethod).sortedWith(compareBy(MethodIndex::name, MethodIndex::descriptor))
        requireUnique(fields.map(FieldIndex::identity), "field")
        requireUnique(methods.map(MethodIndex::identity), "method")

        val signature = model.signature()
        val sourceFile = model.find<SourceFileAttribute>()?.sourceFile()?.stringValue()
        val nestHost = model.find<NestHostAttribute>()?.nestHost()?.let(::binaryName)
        val nestMembers = model.find<NestMembersAttribute>()?.nestMembers()?.map(::binaryName).orEmpty().sorted()
        val innerClasses = model.find<InnerClassesAttribute>()?.classes()?.map {
            InnerClassIndex(
                binaryName(it.innerClass()),
                it.outerClass().map(::binaryName).orElse(null),
                it.innerName().map { name -> name.stringValue() }.orElse(null),
                it.flagsMask(),
            )
        }.orEmpty().sortedWith(compareBy(InnerClassIndex::innerClass, InnerClassIndex::outerClass, InnerClassIndex::innerName))
        val enclosingMethod = model.find<EnclosingMethodAttribute>()?.let {
            EnclosingMethodIndex(
                binaryName(it.enclosingClass()),
                it.enclosingMethodName().map { name -> name.stringValue() }.orElse(null),
                it.enclosingMethodType().map { type -> type.stringValue() }.orElse(null),
            )
        }
        val recordComponents = model.find<RecordAttribute>()?.components()?.map(::indexRecordComponent).orEmpty()
        val permitted = model.find<PermittedSubclassesAttribute>()?.permittedSubclasses()
            ?.map(::binaryName).orEmpty().sorted()
        val annotations = model.annotations()
        val typeAnnotations = model.typeAnnotations()
        val attributes = model.attributeInventory()
        val interfaces = model.interfaces().map(::binaryName)
        val superclass = model.superclass().map(::binaryName).orElse(null)

        val structure = CanonicalForm("class-structure-v1")
            .token("name", binaryName(model.thisClass()))
            .token("minor", model.minorVersion())
            .token("major", model.majorVersion())
            .token("flags", model.flags().flagsMask())
            .token("superclass", superclass)
            .tokens("interface", interfaces)
            .token("signature", signature)
            .tokens("annotation", annotations)
            .tokens("typeAnnotation", typeAnnotations)
            .tokens("nestMember", nestMembers)
            .token("nestHost", nestHost)
            .tokens("innerClass", innerClasses.map(::canonicalInnerClass))
            .token("enclosing", enclosingMethod?.let(::canonicalEnclosingMethod))
            .tokens("recordComponent", recordComponents.map(RecordComponentIndex::metadataHash))
            .tokens("permittedSubclass", permitted)
            .tokens("attribute", semanticAttributes(attributes, CLASS_DEBUG_ATTRIBUTES))
            .tokens("field", fields.map(FieldIndex::memberMetadataHash))
            .tokens("method", methods.map(MethodIndex::memberMetadataHash))

        val sourceDebugHash = model.find<SourceDebugExtensionAttribute>()?.contents()?.let(Sha256::bytes)
        val classDebug = CanonicalForm("class-debug-v1")
            .token("sourceFile", sourceFile)
            .token("sourceDebugExtensionHash", sourceDebugHash)

        return ClassIndex(
            entryPath,
            binaryName(model.thisClass()),
            bytes.size.toLong(),
            Sha256.bytes(bytes),
            model.minorVersion(),
            model.majorVersion(),
            model.flags().flagsMask(),
            superclass,
            interfaces,
            signature,
            sourceFile,
            nestHost,
            nestMembers,
            innerClasses,
            enclosingMethod,
            recordComponents,
            permitted,
            annotations,
            typeAnnotations,
            attributes,
            structure.hash(),
            classDebug.hash(),
            fields,
            methods,
        )
    }

    private fun indexField(field: java.lang.classfile.FieldModel): FieldIndex {
        val signature = field.signature()
        val constantValue = field.find<ConstantValueAttribute>()?.constant()?.constantValue()?.let(::canonicalConstant)
        val annotations = field.annotations()
        val typeAnnotations = field.typeAnnotations()
        val attributes = field.attributeInventory()
        val form = CanonicalForm("field-metadata-v1")
            .token("name", field.fieldName().stringValue())
            .token("descriptor", field.fieldType().stringValue())
            .token("flags", field.flags().flagsMask())
            .token("signature", signature)
            .token("constantValue", constantValue)
            .tokens("annotation", annotations)
            .tokens("typeAnnotation", typeAnnotations)
            .tokens("attribute", semanticAttributes(attributes))
        return FieldIndex(
            field.fieldName().stringValue(),
            field.fieldType().stringValue(),
            field.flags().flagsMask(),
            signature,
            constantValue,
            annotations,
            typeAnnotations,
            attributes,
            form.hash(),
        )
    }

    private fun indexMethod(method: java.lang.classfile.MethodModel): MethodIndex {
        val signature = method.signature()
        val exceptions = method.find<ExceptionsAttribute>()?.exceptions()?.map(::binaryName).orEmpty()
        val annotations = method.annotations()
        val typeAnnotations = method.typeAnnotations()
        val parameterAnnotations = method.parameterAnnotations()
        val annotationDefault = method.find<AnnotationDefaultAttribute>()?.defaultValue()?.let(::canonicalAnnotationValue)
        val parameters = method.find<MethodParametersAttribute>()?.parameters()?.map {
            MethodParameterIndex(it.name().map { name -> name.stringValue() }.orElse(null), it.flagsMask())
        }.orEmpty()
        val attributes = method.attributeInventory()
        val metadata = CanonicalForm("method-metadata-v1")
            .token("name", method.methodName().stringValue())
            .token("descriptor", method.methodType().stringValue())
            .token("flags", method.flags().flagsMask())
            .token("signature", signature)
            .tokens("exception", exceptions)
            .tokens("annotation", annotations)
            .tokens("typeAnnotation", typeAnnotations)
            .tokens("parameterAnnotation", parameterAnnotations)
            .token("annotationDefault", annotationDefault)
            .tokens("parameter", parameters.map { pack("parameter", it.name(), it.accessFlags().toString()) })
            .tokens("attribute", semanticAttributes(attributes, setOf("Code")))

        val code = method.code().orElse(null)
        val codeHashes = code?.let(::fingerprintCode)
        return MethodIndex(
            method.methodName().stringValue(),
            method.methodType().stringValue(),
            method.flags().flagsMask(),
            signature,
            exceptions,
            annotations,
            typeAnnotations,
            parameterAnnotations,
            annotationDefault,
            parameters,
            attributes,
            code?.attributeInventory().orEmpty(),
            code != null,
            metadata.hash(),
            codeHashes?.code,
            codeHashes?.debug,
            codeHashes?.lineNumbers,
            codeHashes?.localVariables,
            codeHashes?.localVariableTypes,
            codeHashes?.codeTypeAnnotations,
            codeHashes?.exactCodeArray,
            codeHashes?.stackMap,
            codeHashes?.maxStack,
            codeHashes?.maxLocals,
        )
    }

    private fun indexRecordComponent(component: java.lang.classfile.attribute.RecordComponentInfo): RecordComponentIndex {
        val signature = component.signature()
        val annotations = component.annotations()
        val typeAnnotations = component.typeAnnotations()
        val attributes = component.attributeInventory()
        val hash = CanonicalForm("record-component-metadata-v1")
            .token("name", component.name().stringValue())
            .token("descriptor", component.descriptor().stringValue())
            .token("signature", signature)
            .tokens("annotation", annotations)
            .tokens("typeAnnotation", typeAnnotations)
            .tokens("attribute", semanticAttributes(attributes))
            .hash()
        return RecordComponentIndex(
            component.name().stringValue(), component.descriptor().stringValue(), signature,
            annotations, typeAnnotations, attributes, hash,
        )
    }

    private fun fingerprintCode(code: CodeModel): CodeHashes {
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

        fun label(label: Label): String = labels[label]?.toString()
            ?: throw ClassfileIndexingException("code references a label that has no target")

        val codeForm = CanonicalForm("method-code-v1")
        elements.filterIsInstance<Instruction>().forEachIndexed { ordinal, instruction ->
            codeForm.token("instruction", canonicalInstruction(instruction, ::label, ordinal))
        }
        code.exceptionHandlers().forEach { handler ->
            codeForm.token(
                "exceptionHandler",
                pack(
                    "handler",
                    label(handler.tryStart()),
                    label(handler.tryEnd()),
                    label(handler.handler()),
                    handler.catchType().map(::binaryName).orElse(null),
                ),
            )
        }

        val debugForm = CanonicalForm("method-debug-v1")
        val lineNumberForm = CanonicalForm("method-line-numbers-v1")
        val localVariableForm = CanonicalForm("method-local-variables-v1")
        val localVariableTypeForm = CanonicalForm("method-local-variable-types-v1")
        val codeTypeAnnotationForm = CanonicalForm("method-code-type-annotations-v1")
        var hasDebugMetadata = false
        var hasLineNumbers = false
        var hasLocalVariables = false
        var hasLocalVariableTypes = false
        var hasCodeTypeAnnotations = false
        var currentOrdinal = 0
        for (element in elements) {
            when (element) {
                is Instruction -> currentOrdinal++
                is LineNumber -> {
                    hasDebugMetadata = true
                    hasLineNumbers = true
                    val token = pack("line", currentOrdinal.toString(), element.line().toString())
                    debugForm.token("line", token)
                    lineNumberForm.token("line", token)
                }
                is LocalVariable -> {
                    hasDebugMetadata = true
                    hasLocalVariables = true
                    val token = pack(
                        "local", element.slot().toString(), element.name().stringValue(), element.type().stringValue(),
                        label(element.startScope()), label(element.endScope()),
                    )
                    debugForm.token("local", token)
                    localVariableForm.token("local", token)
                }
                is LocalVariableType -> {
                    hasDebugMetadata = true
                    hasLocalVariableTypes = true
                    val token = pack(
                        "localType", element.slot().toString(), element.name().stringValue(), element.signature().stringValue(),
                        label(element.startScope()), label(element.endScope()),
                    )
                    debugForm.token("localType", token)
                    localVariableTypeForm.token("localType", token)
                }
                else -> Unit
            }
        }
        code.attributes().forEach { attribute ->
            when (attribute) {
                is RuntimeVisibleTypeAnnotationsAttribute -> attribute.annotations().map {
                    canonicalTypeAnnotation(it, ::label)
                }.sorted().forEach {
                    hasDebugMetadata = true
                    hasCodeTypeAnnotations = true
                    debugForm.token("visibleCodeTypeAnnotation", it)
                    codeTypeAnnotationForm.token("visibleCodeTypeAnnotation", it)
                }
                is RuntimeInvisibleTypeAnnotationsAttribute -> attribute.annotations().map {
                    canonicalTypeAnnotation(it, ::label)
                }.sorted().forEach {
                    hasDebugMetadata = true
                    hasCodeTypeAnnotations = true
                    debugForm.token("invisibleCodeTypeAnnotation", it)
                    codeTypeAnnotationForm.token("invisibleCodeTypeAnnotation", it)
                }
                else -> Unit
            }
        }
        val codeAttribute = code as? CodeAttribute
            ?: throw ClassfileIndexingException("Code model is not a Code attribute")
        val stackMap = code.find<StackMapTableAttribute>()?.let { attribute ->
            val form = CanonicalForm("stack-map-v1")
            attribute.entries().forEach { frame ->
                form.token(
                    "frame",
                    pack(
                        "frame",
                        frame.frameType().toString(),
                        label(frame.target()),
                        pack("locals", *frame.locals().map { canonicalVerificationType(it, ::label) }.toTypedArray()),
                        pack("stack", *frame.stack().map { canonicalVerificationType(it, ::label) }.toTypedArray()),
                    ),
                )
            }
            form.hash()
        }
        return CodeHashes(
            codeForm.hash(),
            if (hasDebugMetadata) debugForm.hash() else null,
            if (hasLineNumbers) lineNumberForm.hash() else null,
            if (hasLocalVariables) localVariableForm.hash() else null,
            if (hasLocalVariableTypes) localVariableTypeForm.hash() else null,
            if (hasCodeTypeAnnotations) codeTypeAnnotationForm.hash() else null,
            Sha256.bytes(codeAttribute.codeArray()),
            stackMap,
            codeAttribute.maxStack(),
            codeAttribute.maxLocals(),
        )
    }

    private fun canonicalVerificationType(
        value: StackMapFrameInfo.VerificationTypeInfo,
        label: (Label) -> String,
    ): String = when (value) {
        is StackMapFrameInfo.ObjectVerificationTypeInfo -> pack(
            "verificationType", value.tag().toString(), binaryName(value.className()),
        )
        is StackMapFrameInfo.UninitializedVerificationTypeInfo -> pack(
            "verificationType", value.tag().toString(), label(value.newTarget()),
        )
        else -> pack("verificationType", value.tag().toString())
    }

    @Suppress("REDUNDANT_ELSE_IN_WHEN") // Retain the explicit failure when a future JDK adds an instruction model.
    private fun canonicalInstruction(
        instruction: Instruction,
        label: (Label) -> String,
        ordinal: Int,
    ): String = when (instruction) {
        is ConstantInstruction -> pack("constant", instruction.typeKind().name, canonicalConstant(instruction.constantValue()))
        is LoadInstruction -> pack("load", instruction.typeKind().name, instruction.slot().toString())
        is StoreInstruction -> pack("store", instruction.typeKind().name, instruction.slot().toString())
        is IncrementInstruction -> pack("increment", instruction.slot().toString(), instruction.constant().toString())
        is ArrayLoadInstruction -> pack("arrayLoad", instruction.typeKind().name)
        is ArrayStoreInstruction -> pack("arrayStore", instruction.typeKind().name)
        is BranchInstruction -> pack("branch", normalizedOpcode(instruction.opcode().name), label(instruction.target()))
        is TableSwitchInstruction -> pack(
            "tableSwitch", instruction.lowValue().toString(), instruction.highValue().toString(),
            label(instruction.defaultTarget()),
            *instruction.cases().sortedBy { it.caseValue() }.map {
                pack("case", it.caseValue().toString(), label(it.target()))
            }.toTypedArray(),
        )
        is LookupSwitchInstruction -> pack(
            "lookupSwitch", label(instruction.defaultTarget()),
            *instruction.cases().sortedBy { it.caseValue() }.map {
                pack("case", it.caseValue().toString(), label(it.target()))
            }.toTypedArray(),
        )
        is FieldInstruction -> pack(
            "field", instruction.opcode().name, binaryName(instruction.owner()),
            instruction.name().stringValue(), instruction.type().stringValue(),
        )
        is InvokeInstruction -> pack(
            "invoke", instruction.opcode().name, binaryName(instruction.owner()),
            instruction.name().stringValue(), instruction.type().stringValue(),
            instruction.isInterface().toString(), instruction.count().toString(),
        )
        is InvokeDynamicInstruction -> pack(
            "invokeDynamic", instruction.name().stringValue(), instruction.type().stringValue(),
            canonicalDirectMethodHandle(instruction.bootstrapMethod()),
            *instruction.bootstrapArgs().map(::canonicalConstant).toTypedArray(),
        )
        is ConvertInstruction -> pack("convert", instruction.fromType().name, instruction.toType().name)
        is NewMultiArrayInstruction -> pack(
            "newMultiArray", binaryName(instruction.arrayType()), instruction.dimensions().toString(),
        )
        is NewObjectInstruction -> pack("newObject", binaryName(instruction.className()))
        is NewPrimitiveArrayInstruction -> pack("newPrimitiveArray", instruction.typeKind().name)
        is NewReferenceArrayInstruction -> pack("newReferenceArray", binaryName(instruction.componentType()))
        is TypeCheckInstruction -> pack("typeCheck", instruction.opcode().name, binaryName(instruction.type()))
        is OperatorInstruction -> pack("operator", instruction.opcode().name, instruction.typeKind().name)
        is ReturnInstruction -> pack("return", instruction.typeKind().name)
        is StackInstruction -> pack("stack", instruction.opcode().name)
        is MonitorInstruction -> pack("monitor", instruction.opcode().name)
        is ThrowInstruction -> pack("throw")
        is NopInstruction -> pack("nop")
        is DiscontinuedInstruction.JsrInstruction -> pack("jsr", label(instruction.target()))
        is DiscontinuedInstruction.RetInstruction -> pack("ret", instruction.slot().toString())
        else -> throw ClassfileIndexingException(
            "unsupported instruction ${instruction::class.java.name} at ordinal $ordinal",
        )
    }

    private fun normalizedOpcode(opcode: String): String = when (opcode) {
        "GOTO_W" -> "GOTO"
        "JSR_W" -> "JSR"
        else -> opcode
    }

    private fun canonicalConstant(constant: Any): String = when (constant) {
        is Int -> pack("int", constant.toString())
        is Long -> pack("long", constant.toString())
        is Float -> pack("floatBits", constant.toRawBits().toUInt().toString(16))
        is Double -> pack("doubleBits", constant.toRawBits().toULong().toString(16))
        is String -> pack("string", constant)
        is ClassDesc -> pack("class", constant.descriptorString())
        is MethodTypeDesc -> pack("methodType", constant.descriptorString())
        is DirectMethodHandleDesc -> canonicalDirectMethodHandle(constant)
        is DynamicConstantDesc<*> -> pack(
            "dynamicConstant", constant.constantName(), constant.constantType().descriptorString(),
            canonicalDirectMethodHandle(constant.bootstrapMethod()),
            *constant.bootstrapArgsList().map(::canonicalConstant).toTypedArray(),
        )
        else -> throw ClassfileIndexingException("unsupported symbolic constant ${constant::class.java.name}")
    }

    private fun canonicalDirectMethodHandle(handle: DirectMethodHandleDesc): String = pack(
        "methodHandle", handle.kind().name, handle.refKind().toString(), handle.isOwnerInterface().toString(),
        handle.owner().descriptorString(), handle.methodName(), handle.lookupDescriptor(),
    )

    private fun AttributedElement.signature(): String? = find<SignatureAttribute>()?.signature()?.stringValue()

    private inline fun <reified T : Attribute<*>> AttributedElement.find(): T? =
        attributes().firstOrNull { it is T } as T?

    private fun AttributedElement.annotations(): List<String> = buildList {
        attributes().forEach { attribute ->
            when (attribute) {
                is RuntimeVisibleAnnotationsAttribute -> attribute.annotations().forEach {
                    add(pack("visible", canonicalAnnotation(it)))
                }
                is RuntimeInvisibleAnnotationsAttribute -> attribute.annotations().forEach {
                    add(pack("invisible", canonicalAnnotation(it)))
                }
                else -> Unit
            }
        }
    }.sorted()

    private fun AttributedElement.typeAnnotations(): List<String> = buildList {
        attributes().forEach { attribute ->
            when (attribute) {
                is RuntimeVisibleTypeAnnotationsAttribute -> attribute.annotations().forEach {
                    add(pack("visible", canonicalTypeAnnotation(it)))
                }
                is RuntimeInvisibleTypeAnnotationsAttribute -> attribute.annotations().forEach {
                    add(pack("invisible", canonicalTypeAnnotation(it)))
                }
                else -> Unit
            }
        }
    }.sorted()

    private fun AttributedElement.parameterAnnotations(): List<String> = buildList {
        attributes().forEach { attribute ->
            val visibility: String
            val parameters: List<List<Annotation>>
            when (attribute) {
                is RuntimeVisibleParameterAnnotationsAttribute -> {
                    visibility = "visible"
                    parameters = attribute.parameterAnnotations()
                }
                is RuntimeInvisibleParameterAnnotationsAttribute -> {
                    visibility = "invisible"
                    parameters = attribute.parameterAnnotations()
                }
                else -> return@forEach
            }
            parameters.forEachIndexed { index, annotations ->
                annotations.map(::canonicalAnnotation).sorted().forEach {
                    add(pack("parameterAnnotation", visibility, index.toString(), it))
                }
            }
        }
    }.sorted()

    private fun canonicalAnnotation(annotation: Annotation): String = pack(
        "annotation",
        annotation.className().stringValue(),
        *annotation.elements().sortedBy { it.name().stringValue() }.map {
            pack("element", it.name().stringValue(), canonicalAnnotationValue(it.value()))
        }.toTypedArray(),
    )

    @Suppress("REDUNDANT_ELSE_IN_WHEN") // Retain the explicit failure when a future JDK adds a value model.
    private fun canonicalAnnotationValue(value: AnnotationValue): String = when (value) {
        is AnnotationValue.OfString -> pack("string", value.stringValue())
        is AnnotationValue.OfDouble -> pack("doubleBits", value.doubleValue().toRawBits().toULong().toString(16))
        is AnnotationValue.OfFloat -> pack("floatBits", value.floatValue().toRawBits().toUInt().toString(16))
        is AnnotationValue.OfLong -> pack("long", value.longValue().toString())
        is AnnotationValue.OfInt -> pack("int", value.intValue().toString())
        is AnnotationValue.OfShort -> pack("short", value.shortValue().toString())
        is AnnotationValue.OfChar -> pack("char", value.charValue().code.toString())
        is AnnotationValue.OfByte -> pack("byte", value.byteValue().toString())
        is AnnotationValue.OfBoolean -> pack("boolean", value.booleanValue().toString())
        is AnnotationValue.OfClass -> pack("class", value.className().stringValue())
        is AnnotationValue.OfEnum -> pack("enum", value.className().stringValue(), value.constantName().stringValue())
        is AnnotationValue.OfAnnotation -> pack("nested", canonicalAnnotation(value.annotation()))
        is AnnotationValue.OfArray -> pack("array", *value.values().map(::canonicalAnnotationValue).toTypedArray())
        else -> throw ClassfileIndexingException("unsupported annotation value ${value::class.java.name}")
    }

    @Suppress("REDUNDANT_ELSE_IN_WHEN") // Retain the explicit failure when a future JDK adds a target model.
    private fun canonicalTypeAnnotation(
        annotation: TypeAnnotation,
        label: ((Label) -> String)? = null,
    ): String {
        val target = annotation.targetInfo()
        val targetContent = when (target) {
            is TypeAnnotation.TypeParameterTarget -> pack(
                target.targetType().name, target.typeParameterIndex().toString(),
            )
            is TypeAnnotation.SupertypeTarget -> pack(target.targetType().name, target.supertypeIndex().toString())
            is TypeAnnotation.TypeParameterBoundTarget -> pack(
                target.targetType().name, target.typeParameterIndex().toString(), target.boundIndex().toString(),
            )
            is TypeAnnotation.EmptyTarget -> pack(target.targetType().name)
            is TypeAnnotation.FormalParameterTarget -> pack(
                target.targetType().name, target.formalParameterIndex().toString(),
            )
            is TypeAnnotation.ThrowsTarget -> pack(target.targetType().name, target.throwsTargetIndex().toString())
            is TypeAnnotation.LocalVarTarget -> pack(
                target.targetType().name,
                *target.table().map {
                    pack(
                        "range", requireNotNull(label) { "code type annotation requires label mapping" }(it.startLabel()),
                        label(it.endLabel()), it.index().toString(),
                    )
                }.toTypedArray(),
            )
            is TypeAnnotation.CatchTarget -> pack(target.targetType().name, target.exceptionTableIndex().toString())
            is TypeAnnotation.OffsetTarget -> pack(
                target.targetType().name,
                requireNotNull(label) { "code type annotation requires label mapping" }(target.target()),
            )
            is TypeAnnotation.TypeArgumentTarget -> pack(
                target.targetType().name,
                requireNotNull(label) { "code type annotation requires label mapping" }(target.target()),
                target.typeArgumentIndex().toString(),
            )
            else -> throw ClassfileIndexingException("unsupported type annotation target ${target::class.java.name}")
        }
        val path = annotation.targetPath().joinToString(separator = "") {
            pack("path", it.typePathKind().name, it.typeArgumentIndex().toString())
        }
        return pack("typeAnnotation", targetContent, path, canonicalAnnotation(annotation.annotation()))
    }

    private fun AttributedElement.attributeInventory(): List<AttributeIndex> = attributes().map { attribute ->
        if (attribute is UnknownAttribute) {
            AttributeIndex(attribute.attributeName().stringValue(), true, Sha256.bytes(attribute.contents()))
        } else {
            AttributeIndex(attribute.attributeName().stringValue(), false, null)
        }
    }.sortedWith(compareBy(AttributeIndex::name, AttributeIndex::unknown, AttributeIndex::unknownContentHash))

    private fun semanticAttributes(
        attributes: List<AttributeIndex>,
        excludedNames: Set<String> = emptySet(),
    ): List<String> = attributes.asSequence()
        .filterNot { it.name() in excludedNames }
        .map { pack("attribute", it.name(), it.unknown().toString()) }
        .sorted()
        .toList()

    private fun canonicalInnerClass(value: InnerClassIndex): String = pack(
        "innerClass", value.innerClass(), value.outerClass(), value.innerName(), value.accessFlags().toString(),
    )

    private fun canonicalEnclosingMethod(value: EnclosingMethodIndex): String = pack(
        "enclosingMethod", value.owner(), value.name(), value.descriptor(),
    )

    private fun binaryName(entry: ClassEntry): String = entry.name().stringValue().replace('/', '.')

    private fun requireUnique(identities: List<String>, kind: String) {
        val duplicate = identities.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicate != null) throw ClassfileIndexingException("duplicate $kind identity '${duplicate.key}'")
    }

    private data class CodeHashes(
        val code: String,
        val debug: String?,
        val lineNumbers: String?,
        val localVariables: String?,
        val localVariableTypes: String?,
        val codeTypeAnnotations: String?,
        val exactCodeArray: String,
        val stackMap: String?,
        val maxStack: Int,
        val maxLocals: Int,
    )

    private companion object {
        val CLASS_DEBUG_ATTRIBUTES = setOf("SourceFile", "SourceDebugExtension", "SourceID", "CompilationID")

        fun pack(tag: String, vararg values: String?): String = buildString {
            append(tag.length).append(':').append(tag).append(';')
            for (value in values) {
                if (value == null) append("-;")
                else append(value.length).append(':').append(value).append(';')
            }
        }
    }
}

private fun CanonicalForm.tokens(name: String, values: Iterable<String>): CanonicalForm = apply {
    values.forEach { token(name, it) }
}
