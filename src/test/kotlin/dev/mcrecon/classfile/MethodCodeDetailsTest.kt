package dev.mcrecon.classfile

import java.lang.classfile.ClassFile
import java.lang.classfile.constantpool.ConstantPoolBuilder
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.CD_boolean
import java.lang.constant.ConstantDescs.CD_int
import java.lang.constant.MethodTypeDesc
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class MethodCodeDetailsTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val inspector = ClassfileMethodInspector()

    @Test
    fun `symbolic diff exposes constants and call targets`() {
        val constantDiff = compareCompiled(
            "ConstantDetails",
            "value",
            "()I",
            "public class ConstantDetails { static int value() { return 1; } }",
            "public class ConstantDetails { static int value() { return 2; } }",
        )
        assertRemoved(constantDiff, "constant INT 1")
        assertAdded(constantDiff, "constant INT 2")

        val callDiff = compareCompiled(
            "CallDetails",
            "value",
            "(I)Ljava/lang/String;",
            "public class CallDetails { static String value(int x) { return Integer.toString(x); } }",
            "public class CallDetails { static String value(int x) { return String.valueOf(x); } }",
        )
        assertRemoved(callDiff, "invokestatic java.lang.Integer.toString(I)Ljava/lang/String;")
        assertAdded(callDiff, "invokestatic java.lang.String.valueOf(I)Ljava/lang/String;")
    }

    @Test
    fun `symbolic diff exposes branch targets`() {
        val before = method(branchTargetFixture(false), "branchValue", "(Z)I")
        val after = method(branchTargetFixture(true), "branchValue", "(Z)I")
        val diff = SymbolicCodeDiff.compare(before.elements, after.elements)

        assertRemoved(diff, "ifeq L2")
        assertAdded(diff, "ifeq L4")
    }

    @Test
    fun `symbolic diff follows invokedynamic bootstrap method handles`() {
        val diff = compareCompiled(
            "IndyDetails",
            "make",
            "()Ljava/lang/Runnable;",
            """
                public class IndyDetails {
                    void first() {}
                    void second() {}
                    Runnable make() { return this::first; }
                }
            """,
            """
                public class IndyDetails {
                    void first() {}
                    void second() {}
                    Runnable make() { return this::second; }
                }
            """,
        )

        val removed = diff.single { it.kind == SymbolicDiffKind.REMOVED }.text
        val added = diff.single { it.kind == SymbolicDiffKind.ADDED }.text
        assertTrue(removed.startsWith("invokedynamic run(LIndyDetails;)Ljava/lang/Runnable; bootstrap="))
        assertTrue(removed.contains("IndyDetails.first()V"))
        assertTrue(added.contains("IndyDetails.second()V"))
    }

    @Test
    fun `symbolic diff identifies instruction insertion and removal`() {
        val forward = compareCompiled(
            "InsertionDetails",
            "work",
            "()V",
            "public class InsertionDetails { static void work() {} }",
            "public class InsertionDetails { static void work() { System.nanoTime(); } }",
        )
        assertAdded(forward, "invokestatic java.lang.System.nanoTime()J")
        assertEquals(1, forward.count { it.kind == SymbolicDiffKind.UNCHANGED && it.text == "return VOID" })

        val reverse = forward.map {
            SymbolicCodeDiffLine(
                when (it.kind) {
                    SymbolicDiffKind.ADDED -> SymbolicDiffKind.REMOVED
                    SymbolicDiffKind.REMOVED -> SymbolicDiffKind.ADDED
                    SymbolicDiffKind.UNCHANGED -> SymbolicDiffKind.UNCHANGED
                },
                it.after,
                it.before,
            )
        }
        assertRemoved(reverse, "invokestatic java.lang.System.nanoTime()J")
    }

    private fun compareCompiled(
        className: String,
        methodName: String,
        descriptor: String,
        beforeSource: String,
        afterSource: String,
    ): List<SymbolicCodeDiffLine> {
        val before = method(ClassfileTestFixtures.compile(temporaryDirectory, className, beforeSource), methodName, descriptor)
        val after = method(ClassfileTestFixtures.compile(temporaryDirectory, className, afterSource), methodName, descriptor)
        return SymbolicCodeDiff.compare(before.elements, after.elements)
    }

    private fun method(bytes: ByteArray, name: String, descriptor: String): SymbolicMethodCode =
        inspector.inspect(bytes).single { it.name == name && it.descriptor == descriptor }

    private fun assertRemoved(diff: List<SymbolicCodeDiffLine>, text: String) {
        assertTrue(diff.any { it.kind == SymbolicDiffKind.REMOVED && it.text == text }, diff.joinToString("\n"))
    }

    private fun assertAdded(diff: List<SymbolicCodeDiffLine>, text: String) {
        assertTrue(diff.any { it.kind == SymbolicDiffKind.ADDED && it.text == text }, diff.joinToString("\n"))
    }

    private fun branchTargetFixture(secondTarget: Boolean): ByteArray {
        val pool = ConstantPoolBuilder.of()
        val thisClass = pool.classEntry(ClassDesc.of("BranchDetails"))
        return ClassFile.of().build(thisClass, pool) { builder ->
            builder.withFlags(ClassFile.ACC_PUBLIC or ClassFile.ACC_SUPER)
            builder.withMethodBody(
                "branchValue",
                MethodTypeDesc.of(CD_int, CD_boolean),
                ClassFile.ACC_PUBLIC or ClassFile.ACC_STATIC,
            ) { code ->
                val first = code.newLabel()
                val second = code.newLabel()
                code.iload(0)
                code.ifeq(if (secondTarget) second else first)
                code.labelBinding(first).iconst_1().ireturn()
                code.labelBinding(second).iconst_2().ireturn()
            }
        }
    }
}
