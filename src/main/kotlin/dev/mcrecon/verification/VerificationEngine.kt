package dev.mcrecon.verification

import dev.mcrecon.classfile.IndexModel
import dev.mcrecon.classfile.IndexModel.AttributeIndex
import dev.mcrecon.classfile.IndexModel.ClassIndex
import dev.mcrecon.classfile.IndexModel.FieldIndex
import dev.mcrecon.classfile.IndexModel.JarIndex
import dev.mcrecon.classfile.IndexModel.MethodIndex
import dev.mcrecon.verification.VerificationModel.Aggregate
import dev.mcrecon.verification.VerificationModel.AttributeChangeKind
import dev.mcrecon.verification.VerificationModel.AttributeDifference
import dev.mcrecon.verification.VerificationModel.CandidateIdentity
import dev.mcrecon.verification.VerificationModel.ClassCounts
import dev.mcrecon.verification.VerificationModel.ClassDimensions
import dev.mcrecon.verification.VerificationModel.ClassFacet
import dev.mcrecon.verification.VerificationModel.ClassPercentages
import dev.mcrecon.verification.VerificationModel.ClassVerification
import dev.mcrecon.verification.VerificationModel.DebugAndAttributeTotals
import dev.mcrecon.verification.VerificationModel.DifferenceDimensions
import dev.mcrecon.verification.VerificationModel.DifferencePattern
import dev.mcrecon.verification.VerificationModel.ExecutableRepresentationTotals
import dev.mcrecon.verification.VerificationModel.FieldSummary
import dev.mcrecon.verification.VerificationModel.FieldTotals
import dev.mcrecon.verification.VerificationModel.MethodSummary
import dev.mcrecon.verification.VerificationModel.MethodTotals
import dev.mcrecon.verification.VerificationModel.ReferenceIdentity
import dev.mcrecon.verification.VerificationModel.Report
import dev.mcrecon.verification.VerificationModel.RepresentationSummary
import dev.mcrecon.verification.VerificationModel.Status
import dev.mcrecon.verification.VerificationModel.ToolIdentity

class VerificationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Mechanically derives verification states and all aggregate counts from two classfile indexes. */
class VerificationEngine {
    fun compare(
        minecraftVersion: String,
        side: String,
        reference: JarIndex,
        candidate: JarIndex,
        candidateIdentity: CandidateIdentity,
    ): Report {
        if (reference.schemaVersion() != IndexModel.SCHEMA_VERSION || candidate.schemaVersion() != IndexModel.SCHEMA_VERSION) {
            throw VerificationException(
                "Verification requires classfile index schema ${IndexModel.SCHEMA_VERSION}; " +
                    "received ${reference.schemaVersion()} and ${candidate.schemaVersion()}",
            )
        }
        val referenceClasses = reference.classes().associateBy(ClassIndex::entryPath)
        val candidateClasses = candidate.classes().associateBy(ClassIndex::entryPath)
        val classes = (referenceClasses.keys + candidateClasses.keys).toSortedSet().map { entryPath ->
            compareClass(entryPath, referenceClasses[entryPath], candidateClasses[entryPath])
        }
        val aggregate = aggregate(classes, reference.classes(), candidate.classes())
        val artifact = reference.artifact()
        return Report(
            aggregate,
            candidateIdentity,
            classes,
            minecraftVersion,
            ReferenceIdentity(artifact.artifactKind(), artifact.artifactHash(), artifact.mojangSha1(), artifact.size()),
            VerificationModel.SCHEMA_VERSION,
            side,
            ToolIdentity(IndexModel.SCHEMA_VERSION, VerificationModel.FORMAT_VERSION),
            VerificationModel.FORMAT_VERSION,
        )
    }

    private fun compareClass(entryPath: String, reference: ClassIndex?, candidate: ClassIndex?): ClassVerification {
        if (reference == null) return inventoryOnly(entryPath, requireNotNull(candidate), Status.EXTRA)
        if (candidate == null) return inventoryOnly(entryPath, reference, Status.MISSING)

        val fields = compareFields(reference.fields(), candidate.fields())
        val methods = compareMethods(reference.methods(), candidate.methods())
        val structureChanged = reference.classStructureHash() != candidate.classStructureHash()
        val classDebugChanged = reference.classDebugHash() != candidate.classDebugHash()
        val implementationChanged = methods.codeChanged() > 0
        val debugChanged = classDebugChanged || methods.debugChanged() > 0
        val exact = reference.classExactHash() == candidate.classExactHash()
        val allNormalizedEqual = !structureChanged && !implementationChanged && !debugChanged
        val memberIdentitiesEqual = fields.missing() == 0 && fields.extra() == 0 &&
            methods.missing() == 0 && methods.extra() == 0
        val status = when {
            exact -> Status.EXACT
            allNormalizedEqual -> Status.STRUCTURAL
            memberIdentitiesEqual && implementationChanged -> Status.FUNCTIONAL
            else -> Status.COMPILES
        }
        val attributeDifferences = compareAllAttributes(reference, candidate)
        val representation = representation(reference, candidate, methods, status, attributeDifferences)
        return ClassVerification(
            attributeDifferences,
            reference.binaryName(),
            candidate.binaryName(),
            candidate.classExactHash(),
            candidate.exactByteSize(),
            ClassDimensions(
                structureChanged,
                debugChanged,
                status == Status.STRUCTURAL,
                fields.changedMetadata() > 0,
                implementationChanged,
                methods.metadataChanged() > 0,
            ),
            entryPath,
            exact,
            fields,
            methods,
            reference.classExactHash(),
            reference.exactByteSize(),
            representation,
            status,
        )
    }

    private fun inventoryOnly(entryPath: String, clazz: ClassIndex, status: Status): ClassVerification {
        val missing = status == Status.MISSING
        val fields = FieldSummary(
            0, emptyList(), emptyList(), 0,
            if (missing) 0 else clazz.fields().size,
            if (missing) emptyList() else clazz.fields().map(FieldIndex::identity),
            0,
            if (missing) clazz.fields().size else 0,
            if (missing) clazz.fields().map(FieldIndex::identity) else emptyList(),
        )
        val methods = MethodSummary(
            0, emptyList(), 0, 0, 0, emptyList(), 0,
            if (missing) 0 else clazz.methods().size,
            if (missing) emptyList() else clazz.methods().map(MethodIndex::identity),
            0, emptyList(), 0,
            if (missing) clazz.methods().size else 0,
            if (missing) clazz.methods().map(MethodIndex::identity) else emptyList(),
        )
        return ClassVerification(
            emptyList(),
            clazz.binaryName(),
            if (missing) null else clazz.binaryName(),
            if (missing) null else clazz.classExactHash(),
            if (missing) null else clazz.exactByteSize(),
            ClassDimensions(false, false, false, false, false, false),
            entryPath,
            false,
            fields,
            methods,
            if (missing) clazz.classExactHash() else null,
            if (missing) clazz.exactByteSize() else null,
            emptyRepresentation(),
            status,
        )
    }

    private fun compareFields(reference: List<FieldIndex>, candidate: List<FieldIndex>): FieldSummary {
        val old = reference.associateBy(FieldIndex::identity)
        val rebuilt = candidate.associateBy(FieldIndex::identity)
        val corresponding = old.keys.intersect(rebuilt.keys).sorted()
        val changed = corresponding.filter { old.getValue(it).memberMetadataHash() != rebuilt.getValue(it).memberMetadataHash() }
        val constants = changed.filter { old.getValue(it).constantValue() != rebuilt.getValue(it).constantValue() }
        val missing = (old.keys - rebuilt.keys).sorted()
        val extra = (rebuilt.keys - old.keys).sorted()
        return FieldSummary(
            changed.size,
            changed,
            constants,
            corresponding.size,
            extra.size,
            extra,
            corresponding.size - changed.size,
            missing.size,
            missing,
        )
    }

    private fun compareMethods(reference: List<MethodIndex>, candidate: List<MethodIndex>): MethodSummary {
        val old = reference.associateBy(MethodIndex::identity)
        val rebuilt = candidate.associateBy(MethodIndex::identity)
        val corresponding = old.keys.intersect(rebuilt.keys).sorted()
        val metadata = corresponding.filter { old.getValue(it).memberMetadataHash() != rebuilt.getValue(it).memberMetadataHash() }
        val executable = corresponding.filter { old.getValue(it).hasCode() || rebuilt.getValue(it).hasCode() }
        val codeChanged = executable.filter { old.getValue(it).methodCodeHash() != rebuilt.getValue(it).methodCodeHash() }
        val codeMatching = executable.filter {
            old.getValue(it).hasCode() && rebuilt.getValue(it).hasCode() &&
                old.getValue(it).methodCodeHash() == rebuilt.getValue(it).methodCodeHash()
        }
        val debug = corresponding.filter { old.getValue(it).debugHash() != rebuilt.getValue(it).debugHash() }
        val missing = (old.keys - rebuilt.keys).sorted()
        val extra = (rebuilt.keys - old.keys).sorted()
        return MethodSummary(
            codeChanged.size,
            codeChanged,
            codeMatching.size,
            corresponding.size,
            debug.size,
            debug,
            executable.size,
            extra.size,
            extra,
            metadata.size,
            metadata,
            corresponding.size - metadata.size,
            missing.size,
            missing,
        )
    }

    private fun representation(
        reference: ClassIndex,
        candidate: ClassIndex,
        methods: MethodSummary,
        status: Status,
        attributeDifferences: List<AttributeDifference>,
    ): RepresentationSummary {
        val old = reference.methods().associateBy(MethodIndex::identity)
        val rebuilt = candidate.methods().associateBy(MethodIndex::identity)
        val corresponding = old.keys.intersect(rebuilt.keys)
        fun changed(selector: (MethodIndex) -> Any?): Int = corresponding.count {
            selector(old.getValue(it)) != selector(rebuilt.getValue(it))
        }
        val lineNumbers = changed(MethodIndex::lineNumberHash)
        val locals = changed(MethodIndex::localVariableHash)
        val localTypes = changed(MethodIndex::localVariableTypeHash)
        val codeTypeAnnotations = changed(MethodIndex::codeTypeAnnotationHash)
        val parameters = corresponding.count { old.getValue(it).parameters() != rebuilt.getValue(it).parameters() }
        val codeArrays = changed(MethodIndex::exactCodeArrayHash)
        val stackMaps = changed(MethodIndex::stackMapHash)
        val maxStack = changed(MethodIndex::maxStack)
        val maxLocals = changed(MethodIndex::maxLocals)
        val sourceFile = reference.sourceFile() != candidate.sourceFile()
        val noLocatedDifference = !sourceFile && lineNumbers == 0 && locals == 0 && localTypes == 0 &&
            codeTypeAnnotations == 0 && parameters == 0 && codeArrays == 0 && stackMaps == 0 &&
            maxStack == 0 && maxLocals == 0 && attributeDifferences.isEmpty()
        return RepresentationSummary(
            codeTypeAnnotations,
            status == Status.STRUCTURAL && noLocatedDifference,
            codeArrays,
            lineNumbers,
            locals,
            localTypes,
            maxLocals,
            maxStack,
            parameters,
            sourceFile,
            stackMaps,
            attributeDifferences.size,
        )
    }

    private fun compareAllAttributes(reference: ClassIndex, candidate: ClassIndex): List<AttributeDifference> = buildList {
        addAll(compareAttributes(reference.attributes(), candidate.attributes(), "class"))
        val oldFields = reference.fields().associateBy(FieldIndex::identity)
        val newFields = candidate.fields().associateBy(FieldIndex::identity)
        oldFields.keys.intersect(newFields.keys).sorted().forEach { identity ->
            addAll(compareAttributes(oldFields.getValue(identity).attributes(), newFields.getValue(identity).attributes(), "field:$identity"))
        }
        val oldMethods = reference.methods().associateBy(MethodIndex::identity)
        val newMethods = candidate.methods().associateBy(MethodIndex::identity)
        oldMethods.keys.intersect(newMethods.keys).sorted().forEach { identity ->
            addAll(compareAttributes(oldMethods.getValue(identity).attributes(), newMethods.getValue(identity).attributes(), "method:$identity"))
            addAll(compareAttributes(oldMethods.getValue(identity).codeAttributes(), newMethods.getValue(identity).codeAttributes(), "code:$identity"))
        }
    }.sortedWith(compareBy(AttributeDifference::location, AttributeDifference::name, AttributeDifference::change))

    private fun compareAttributes(
        reference: List<AttributeIndex>,
        candidate: List<AttributeIndex>,
        location: String,
    ): List<AttributeDifference> = buildList {
        val names = (reference.map(AttributeIndex::name) + candidate.map(AttributeIndex::name)).toSortedSet()
        for (name in names) {
            val old = reference.filter { it.name() == name }.toMutableList()
            val rebuilt = candidate.filter { it.name() == name }.toMutableList()
            old.toList().forEach { value ->
                val match = rebuilt.indexOf(value)
                if (match >= 0) {
                    old.remove(value)
                    rebuilt.removeAt(match)
                }
            }
            while (old.firstOrNull()?.unknown() == true && rebuilt.firstOrNull()?.unknown() == true) {
                val oldValue = old.removeAt(0)
                val newValue = rebuilt.removeAt(0)
                add(
                    AttributeDifference(
                        AttributeChangeKind.OPAQUE_CONTENT_CHANGED,
                        newValue.unknownContentHash(),
                        location,
                        name,
                        oldValue.unknownContentHash(),
                    ),
                )
            }
            old.forEach {
                add(AttributeDifference(AttributeChangeKind.REMOVED, null, location, name, it.unknownContentHash()))
            }
            rebuilt.forEach {
                add(AttributeDifference(AttributeChangeKind.ADDED, it.unknownContentHash(), location, name, null))
            }
        }
    }

    private fun aggregate(
        classes: List<ClassVerification>,
        referenceClasses: List<ClassIndex>,
        candidateClasses: List<ClassIndex>,
    ): Aggregate {
        fun status(value: Status) = classes.count { it.status() == value }
        val classCounts = ClassCounts(
            status(Status.COMPILES), status(Status.EXACT), status(Status.EXTRA), status(Status.FUNCTIONAL),
            status(Status.MISSING), classes.count { it.status() != Status.MISSING }, referenceClasses.size, status(Status.STRUCTURAL),
        )
        val fields = FieldTotals(
            classes.sumOf { it.fields().changedMetadata() },
            classes.sumOf { it.fields().constantChangedFields().size },
            classes.sumOf { it.fields().corresponding() },
            classes.sumOf { it.fields().extra() },
            classes.sumOf { it.fields().matchingMetadata() },
            classes.sumOf { it.fields().missing() },
        )
        val methods = MethodTotals(
            classes.sumOf { it.methods().codeChanged() },
            classes.sumOf { it.methods().codeMatching() },
            classes.sumOf { it.methods().corresponding() },
            classes.sumOf { it.methods().debugChanged() },
            classes.sumOf { it.methods().executableCorresponding() },
            classes.sumOf { it.methods().extra() },
            classes.sumOf { it.methods().metadataChanged() },
            classes.sumOf { it.methods().metadataMatching() },
            classes.sumOf { it.methods().missing() },
        )
        val dimensions = DifferenceDimensions(
            classes.count { it.differences().classStructureChanged() },
            classes.count { it.differences().debugChanged() },
            classes.count { it.differences().exactBytesOutsideNormalizedCoverage() },
            classes.count { it.differences().fieldMetadataChanged() },
            classes.count { it.differences().methodImplementationChanged() },
            classes.count { it.differences().methodMetadataChanged() },
        )
        val debug = DebugAndAttributeTotals(
            classes.sumOf { it.representation().codeTypeAnnotationsChanged() },
            classes.count { it.representation().constantPoolOrUnmodeledSerialization() },
            classes.sumOf { it.representation().lineNumberTablesChanged() },
            classes.sumOf { it.representation().localVariableTablesChanged() },
            classes.sumOf { it.representation().localVariableTypeTablesChanged() },
            classes.sumOf { it.representation().methodParametersChanged() },
            classes.count { it.representation().sourceFileChanged() },
            classes.sumOf { it.representation().attributeInventoryOrOpaqueDifferences() },
        )
        val byPath = classes.associateBy(ClassVerification::entryPath)
        val representation = ExecutableRepresentationTotals(
            representationAmongSemanticMatches(referenceClasses, candidateClasses, MethodIndex::exactCodeArrayHash),
            representationAmongSemanticMatches(referenceClasses, candidateClasses, MethodIndex::maxLocals),
            representationAmongSemanticMatches(referenceClasses, candidateClasses, MethodIndex::maxStack),
            methods.codeMatching(),
            representationAmongSemanticMatches(referenceClasses, candidateClasses, MethodIndex::stackMapHash),
        )
        return Aggregate(
            classCounts,
            ClassPercentages(
                percentage(classCounts.compiles(), classCounts.reference()),
                percentage(classCounts.exact(), classCounts.reference()),
                percentage(classCounts.extra(), classCounts.reference()),
                percentage(classCounts.functional(), classCounts.reference()),
                percentage(classCounts.missing(), classCounts.reference()),
                percentage(classCounts.structural(), classCounts.reference()),
            ),
            debug,
            dimensions,
            representation,
            facets(referenceClasses, byPath),
            fields,
            patterns(classes),
            methods,
        )
    }

    private fun representationAmongSemanticMatches(
        referenceClasses: List<ClassIndex>,
        candidateClasses: List<ClassIndex>,
        selector: (MethodIndex) -> Any?,
    ): Int {
        val rebuiltClasses = candidateClasses.associateBy(ClassIndex::entryPath)
        return referenceClasses.sumOf { oldClass ->
            val rebuiltClass = rebuiltClasses[oldClass.entryPath()] ?: return@sumOf 0
            val rebuiltMethods = rebuiltClass.methods().associateBy(MethodIndex::identity)
            oldClass.methods().count { oldMethod ->
                val rebuilt = rebuiltMethods[oldMethod.identity()] ?: return@count false
                oldMethod.hasCode() && rebuilt.hasCode() && oldMethod.methodCodeHash() == rebuilt.methodCodeHash() &&
                    selector(oldMethod) != selector(rebuilt)
            }
        }
    }

    private fun facets(reference: List<ClassIndex>, results: Map<String, ClassVerification>): List<ClassFacet> {
        fun facet(name: String, predicate: (ClassIndex) -> Boolean): ClassFacet {
            val selected = reference.filter(predicate)
            return ClassFacet(name, selected.count { results.getValue(it.entryPath()).status() == Status.EXACT }, selected.size)
        }
        return listOf(
            facet("all") { true },
            facet("package-info classes") { it.binaryName().endsWith(".package-info") },
            facet("non-package-info classes") { !it.binaryName().endsWith(".package-info") },
            facet("interfaces") { it.accessFlags() and ACC_INTERFACE != 0 },
            facet("records") { clazz -> clazz.attributes().any { it.name() == "Record" } },
            facet("enums") { it.accessFlags() and ACC_ENUM != 0 },
            facet("synthetic classes") { it.accessFlags() and ACC_SYNTHETIC != 0 },
            facet("anonymous or local classes") { clazz ->
                clazz.enclosingMethod() != null || clazz.innerClasses().any {
                    it.innerClass() == clazz.binaryName() && it.innerName() == null
                }
            },
            facet("small implementations (1-5 executable methods)") { clazz ->
                clazz.accessFlags() and ACC_INTERFACE == 0 && clazz.methods().count(MethodIndex::hasCode) in 1..5
            },
            facet("large implementations (20+ executable methods)") { clazz -> clazz.methods().count(MethodIndex::hasCode) >= 20 },
        )
    }

    private fun patterns(classes: List<ClassVerification>): List<DifferencePattern> = classes.asSequence()
        .filter { it.status() != Status.EXACT }
        .map { result ->
            val dimensions = buildList {
                if (result.status() == Status.MISSING) add("missing")
                if (result.status() == Status.EXTRA) add("extra")
                if (result.differences().classStructureChanged()) add("structure")
                if (result.differences().fieldMetadataChanged()) add("field-metadata")
                if (result.differences().methodMetadataChanged()) add("method-metadata")
                if (result.differences().methodImplementationChanged()) add("method-code")
                if (result.differences().debugChanged()) add("debug")
                if (result.differences().exactBytesOutsideNormalizedCoverage()) add("outside-normalized")
                if (isEmpty()) add("inventory-only")
            }
            "${result.status().name}:${dimensions.joinToString("+")}"
        }.groupingBy { it }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { DifferencePattern(it.value, it.key) }
        .toList()

    private fun emptyRepresentation() = RepresentationSummary(0, false, 0, 0, 0, 0, 0, 0, 0, false, 0, 0)

    private fun percentage(count: Int, reference: Int): Double =
        if (reference == 0) 0.0 else count.toDouble() * 100.0 / reference

    private companion object {
        const val ACC_INTERFACE = 0x0200
        const val ACC_SYNTHETIC = 0x1000
        const val ACC_ENUM = 0x4000
    }
}
