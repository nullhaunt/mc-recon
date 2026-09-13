package dev.mcrecon.classfile

import dev.mcrecon.classfile.DiffModel.ChangeKind
import java.lang.classfile.ClassFile
import java.lang.classfile.constantpool.ConstantPoolBuilder
import java.lang.classfile.instruction.SwitchCase
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.CD_boolean
import java.lang.constant.ConstantDescs.CD_int
import java.lang.constant.MethodTypeDesc
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ClassfileIndexerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val indexer = ClassfileIndexer()

    @Test
    fun `exact structure metadata and code fingerprints have separate semantics`() {
        val sourceOne = """
            public class Sample {
                public static int value() { return 1; }
            }
        """
        val sourceTwo = sourceOne.replace("return 1", "return 2")
        val first = indexer.index(ClassfileTestFixtures.compile(temporaryDirectory, "Sample", sourceOne), "Sample.class")
        val repeated = indexer.index(ClassfileTestFixtures.compile(temporaryDirectory, "Sample", sourceOne), "Sample.class")
        val changed = indexer.index(ClassfileTestFixtures.compile(temporaryDirectory, "Sample", sourceTwo), "Sample.class")

        assertEquals(first.classExactHash(), repeated.classExactHash())
        assertEquals(first.classStructureHash(), repeated.classStructureHash())
        assertEquals(method(first, "value", "()I").methodCodeHash(), method(repeated, "value", "()I").methodCodeHash())

        assertNotEquals(first.classExactHash(), changed.classExactHash())
        assertEquals(first.classStructureHash(), changed.classStructureHash())
        assertEquals(method(first, "value", "()I").memberMetadataHash(), method(changed, "value", "()I").memberMetadataHash())
        assertNotEquals(method(first, "value", "()I").methodCodeHash(), method(changed, "value", "()I").methodCodeHash())
    }

    @Test
    fun `field and method identities and metadata changes diff precisely`() {
        val base = index("Members", """
            public class Members {
                public static final int VALUE = 42;
                public int work(String value) { return value.length(); }
            }
        """)
        val constantChanged = index("Members", """
            public class Members {
                public static final int VALUE = 43;
                public int work(String value) { return value.length(); }
            }
        """)
        val fieldAdded = index("Members", """
            public class Members {
                public static final int VALUE = 42;
                private long added;
                public int work(String value) { return value.length(); }
            }
        """)
        val signatureChanged = index("Members", """
            public class Members {
                public static final int VALUE = 42;
                public int work(Object value) { return value.hashCode(); }
                public int extra() { return 7; }
            }
        """)

        val constantDiff = classDiff(base, constantChanged)
        assertTrue(constantDiff.structureChanged())
        assertTrue(constantDiff.fields().single().metadataChanged())
        assertTrue(constantDiff.fields().single().constantChanged())
        assertEquals(
            method(base, "work", "(Ljava/lang/String;)I").methodCodeHash(),
            method(constantChanged, "work", "(Ljava/lang/String;)I").methodCodeHash(),
        )

        val fieldDiff = classDiff(base, fieldAdded)
        assertEquals(ChangeKind.ADDED, fieldDiff.fields().single().change())
        assertEquals(
            method(base, "work", "(Ljava/lang/String;)I").methodCodeHash(),
            method(fieldAdded, "work", "(Ljava/lang/String;)I").methodCodeHash(),
        )

        val methodDiff = classDiff(base, signatureChanged)
        assertTrue(methodDiff.methods().any { it.change() == ChangeKind.REMOVED && it.descriptor() == "(Ljava/lang/String;)I" })
        assertTrue(methodDiff.methods().any { it.change() == ChangeKind.ADDED && it.descriptor() == "(Ljava/lang/Object;)I" })
        assertTrue(methodDiff.methods().any { it.change() == ChangeKind.ADDED && it.name() == "extra" })
    }

    @Test
    fun `debug tables do not affect structure or executable code`() {
        val source = """
            public class DebugSample {
                public int calculate(int input) {
                    int doubled = input * 2;
                    return doubled + 1;
                }
            }
        """
        val withDebug = indexer.index(
            ClassfileTestFixtures.compile(temporaryDirectory, "DebugSample", source, "-g"), "DebugSample.class",
        )
        val withoutDebug = indexer.index(
            ClassfileTestFixtures.compile(temporaryDirectory, "DebugSample", source, "-g:none"), "DebugSample.class",
        )
        val withMethod = method(withDebug, "calculate", "(I)I")
        val withoutMethod = method(withoutDebug, "calculate", "(I)I")

        assertNotEquals(withDebug.classExactHash(), withoutDebug.classExactHash())
        assertEquals(withDebug.classStructureHash(), withoutDebug.classStructureHash())
        assertEquals(withMethod.memberMetadataHash(), withoutMethod.memberMetadataHash())
        assertEquals(withMethod.methodCodeHash(), withoutMethod.methodCodeHash())
        assertNotEquals(withMethod.debugHash(), withoutMethod.debugHash())
        assertTrue(withMethod.codeAttributes().any { it.name() == "LineNumberTable" })
        assertTrue(withMethod.codeAttributes().any { it.name() == "LocalVariableTable" })
        assertTrue(classDiff(withDebug, withoutDebug).debugOnly())
    }

    @Test
    fun `constant pool indexes and instruction width do not affect semantic code`() {
        val compact = indexer.index(constantPoolFixture(0), "PoolSample.class")
        val shifted = indexer.index(constantPoolFixture(300), "PoolSample.class")

        assertNotEquals(compact.classExactHash(), shifted.classExactHash())
        assertEquals(compact.classStructureHash(), shifted.classStructureHash())
        assertEquals(method(compact, "value", "()I").methodCodeHash(), method(shifted, "value", "()I").methodCodeHash())
        assertTrue(classDiff(compact, shifted).otherExactChange())
    }

    @Test
    fun `branches switches exception handlers and invokedynamic participate in code hashes`() {
        assertMethodCodeChanges(
            "BranchSample", "choose", "(Z)I",
            "public class BranchSample { int choose(boolean x) { if (x) return 1; return 2; } }",
            "public class BranchSample { int choose(boolean x) { if (x) return 2; return 1; } }",
        )
        assertMethodCodeChanges(
            "TableSample", "choose", "(I)I",
            "public class TableSample { int choose(int x) { return switch (x) { case 0 -> 1; case 1 -> 2; case 2 -> 3; default -> 4; }; } }",
            "public class TableSample { int choose(int x) { return switch (x) { case 0 -> 1; case 1 -> 9; case 2 -> 3; default -> 4; }; } }",
        )
        assertMethodCodeChanges(
            "LookupSample", "choose", "(I)I",
            "public class LookupSample { int choose(int x) { return switch (x) { case 1 -> 1; case 100 -> 2; case 1000 -> 3; default -> 4; }; } }",
            "public class LookupSample { int choose(int x) { return switch (x) { case 1 -> 1; case 100 -> 2; case 2000 -> 3; default -> 4; }; } }",
        )
        assertMethodCodeChanges(
            "CatchSample", "read", "(Ljava/lang/String;)I",
            "public class CatchSample { int read(String x) { try { return Integer.parseInt(x); } catch (IllegalArgumentException e) { return -1; } } }",
            "public class CatchSample { int read(String x) { try { return Integer.parseInt(x); } catch (RuntimeException e) { return -1; } } }",
        )
        assertMethodCodeChanges(
            "IndySample", "join", "(I)Ljava/lang/String;",
            "public class IndySample { String join(int x) { return \"alpha=\" + x; } }",
            "public class IndySample { String join(int x) { return \"beta=\" + x; } }",
        )

        assertControlFlowTargetChanges(branchTargetFixture(false), branchTargetFixture(true), "branchValue", "(Z)I")
        assertControlFlowTargetChanges(switchTargetFixture(false), switchTargetFixture(true), "switchValue", "(I)I")
        assertControlFlowTargetChanges(handlerTargetFixture(false), handlerTargetFixture(true), "handlerValue", "()I")
    }

    private fun constantPoolFixture(padding: Int): ByteArray {
        val pool = ConstantPoolBuilder.of()
        repeat(padding) { pool.utf8Entry("unused-padding-$it") }
        val thisClass = pool.classEntry(ClassDesc.of("PoolSample"))
        return ClassFile.of().build(thisClass, pool) { builder ->
            builder.withFlags(ClassFile.ACC_PUBLIC or ClassFile.ACC_SUPER)
            builder.withMethodBody(
                "value", MethodTypeDesc.of(CD_int), ClassFile.ACC_PUBLIC or ClassFile.ACC_STATIC,
            ) { code ->
                code.ldc(pool.intEntry(123456))
                code.ireturn()
            }
        }
    }

    private fun branchTargetFixture(secondTarget: Boolean): ByteArray = buildClass("BranchTarget") { code ->
        val first = code.newLabel()
        val second = code.newLabel()
        code.iload(0)
        code.ifeq(if (secondTarget) second else first)
        code.labelBinding(first).iconst_1().ireturn()
        code.labelBinding(second).iconst_2().ireturn()
    }

    private fun switchTargetFixture(swapped: Boolean): ByteArray = buildClass("SwitchTarget") { code ->
        val first = code.newLabel()
        val second = code.newLabel()
        val defaultTarget = code.newLabel()
        code.iload(0)
        code.lookupswitch(
            defaultTarget,
            listOf(
                SwitchCase.of(1, if (swapped) second else first),
                SwitchCase.of(100, if (swapped) first else second),
            ),
        )
        code.labelBinding(first).iconst_1().ireturn()
        code.labelBinding(second).iconst_2().ireturn()
        code.labelBinding(defaultTarget).iconst_m1().ireturn()
    }

    private fun handlerTargetFixture(secondTarget: Boolean): ByteArray {
        val pool = ConstantPoolBuilder.of()
        val thisClass = pool.classEntry(ClassDesc.of("HandlerTarget"))
        return ClassFile.of().build(thisClass, pool) { builder ->
            builder.withFlags(ClassFile.ACC_PUBLIC or ClassFile.ACC_SUPER)
            builder.withMethodBody("handlerValue", MethodTypeDesc.of(CD_int), ClassFile.ACC_PUBLIC or ClassFile.ACC_STATIC) { code ->
                val start = code.newLabel()
                val end = code.newLabel()
                val firstHandler = code.newLabel()
                val secondHandler = code.newLabel()
                code.labelBinding(start).iconst_0().pop()
                code.labelBinding(end).iconst_1().ireturn()
                code.labelBinding(firstHandler).pop().iconst_2().ireturn()
                code.labelBinding(secondHandler).pop().iconst_3().ireturn()
                code.exceptionCatch(
                    start, end, if (secondTarget) secondHandler else firstHandler,
                    ClassDesc.of("java.lang.RuntimeException"),
                )
            }
        }
    }

    private fun buildClass(
        className: String,
        body: (java.lang.classfile.CodeBuilder) -> Unit,
    ): ByteArray {
        val pool = ConstantPoolBuilder.of()
        val thisClass = pool.classEntry(ClassDesc.of(className))
        val methodType = if (className == "BranchTarget") MethodTypeDesc.of(CD_int, CD_boolean)
        else MethodTypeDesc.of(CD_int, CD_int)
        val methodName = if (className == "BranchTarget") "branchValue" else "switchValue"
        return ClassFile.of().build(thisClass, pool) { builder ->
            builder.withFlags(ClassFile.ACC_PUBLIC or ClassFile.ACC_SUPER)
            builder.withMethodBody(methodName, methodType, ClassFile.ACC_PUBLIC or ClassFile.ACC_STATIC, body)
        }
    }

    private fun assertControlFlowTargetChanges(
        beforeBytes: ByteArray,
        afterBytes: ByteArray,
        methodName: String,
        descriptor: String,
    ) {
        val before = indexer.index(beforeBytes, "target.class")
        val after = indexer.index(afterBytes, "target.class")
        assertNotEquals(method(before, methodName, descriptor).methodCodeHash(), method(after, methodName, descriptor).methodCodeHash())
    }

    private fun assertMethodCodeChanges(
        className: String,
        methodName: String,
        descriptor: String,
        beforeSource: String,
        afterSource: String,
    ) {
        val before = index(className, beforeSource)
        val after = index(className, afterSource)
        assertNotEquals(method(before, methodName, descriptor).methodCodeHash(), method(after, methodName, descriptor).methodCodeHash())
        val diff = classDiff(before, after)
        assertTrue(diff.methods().single { it.name() == methodName }.implementationChanged())
        assertFalse(diff.methods().single { it.name() == methodName }.metadataChanged())
    }

    private fun index(className: String, source: String) = indexer.index(
        ClassfileTestFixtures.compile(temporaryDirectory, className, source), "$className.class",
    )

    private fun method(clazz: IndexModel.ClassIndex, name: String, descriptor: String) =
        clazz.methods().single { it.name() == name && it.descriptor() == descriptor }

    private fun classDiff(before: IndexModel.ClassIndex, after: IndexModel.ClassIndex): DiffModel.ClassChange {
        val source = JarSource("test", "client", "test", "0".repeat(40))
        fun jar(clazz: IndexModel.ClassIndex, hash: String) = IndexModel.JarIndex(
            IndexModel.SCHEMA_VERSION,
            IndexModel.ArtifactIdentity("test", "client", "test", 1, source.mojangSha1, hash),
            emptyList(), listOf(clazz), emptyList(),
        )
        return BinaryDiffEngine().compare(jar(before, "a".repeat(64)), jar(after, "b".repeat(64))).classes().single()
    }
}
