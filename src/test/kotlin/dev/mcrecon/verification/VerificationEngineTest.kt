package dev.mcrecon.verification

import dev.mcrecon.classfile.ClassfileIndexer
import dev.mcrecon.classfile.ClassfileTestFixtures
import dev.mcrecon.classfile.IndexModel
import dev.mcrecon.compiler.CompilationModel
import dev.mcrecon.verification.VerificationModel.CandidateIdentity
import dev.mcrecon.verification.VerificationModel.Status
import dev.mcrecon.verification.VerificationComparisonModel.Direction
import java.lang.classfile.ClassFile
import java.lang.classfile.constantpool.ConstantPoolBuilder
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.CD_int
import java.lang.constant.MethodTypeDesc
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class VerificationEngineTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val indexer = ClassfileIndexer()

    @Test
    fun `classification is derived from exact normalized code debug metadata and inventory evidence`() {
        val exact = compile("Exact", "public class Exact { int value() { return 1; } }")
        val debugSource = "public class DebugOnly { int value(int x) { int y = x + 1; return y; } }"
        val debugReference = compile("DebugOnly", debugSource, "-g")
        val debugCandidate = compile("DebugOnly", debugSource, "-g:none")
        val codeReference = compile("CodeChanged", "public class CodeChanged { int value() { return 1; } }")
        val codeCandidate = compile("CodeChanged", "public class CodeChanged { int value() { return 2; } }")
        val fieldReference = compile("FieldChanged", "public class FieldChanged { static final int VALUE = 1; }")
        val fieldCandidate = compile("FieldChanged", "public class FieldChanged { static final int VALUE = 2; }")
        val methodReference = compile("MethodChanged", "public class MethodChanged { public int value() { return 1; } }")
        val methodCandidate = compile("MethodChanged", "public class MethodChanged { protected int value() { return 1; } }")
        val structuralReference = constantPoolFixture(0)
        val structuralCandidate = constantPoolFixture(300)
        val missing = compile("Missing", "public class Missing { int gone() { return 0; } }")
        val extra = compile("Extra", "public class Extra { int added() { return 0; } }")

        val reference = jar(
            "Exact" to exact,
            "DebugOnly" to debugReference,
            "CodeChanged" to codeReference,
            "FieldChanged" to fieldReference,
            "MethodChanged" to methodReference,
            "PoolSample" to structuralReference,
            "Missing" to missing,
        )
        val candidate = jar(
            "Exact" to exact,
            "DebugOnly" to debugCandidate,
            "CodeChanged" to codeCandidate,
            "FieldChanged" to fieldCandidate,
            "MethodChanged" to methodCandidate,
            "PoolSample" to structuralCandidate,
            "Extra" to extra,
        )

        val report = VerificationEngine().compare("fixture", "client", reference, candidate, candidateIdentity())
        val byName = report.classes().associateBy { it.binaryName() }

        assertEquals(Status.EXACT, byName.getValue("Exact").status())
        assertEquals(Status.COMPILES, byName.getValue("DebugOnly").status())
        assertTrue(byName.getValue("DebugOnly").differences().debugChanged())
        assertEquals(Status.FUNCTIONAL, byName.getValue("CodeChanged").status())
        assertEquals(listOf("value()I"), byName.getValue("CodeChanged").methods().codeChangedMethods())
        assertEquals(Status.COMPILES, byName.getValue("FieldChanged").status())
        assertEquals(listOf("VALUEI"), byName.getValue("FieldChanged").fields().changedMetadataFields())
        assertEquals(Status.COMPILES, byName.getValue("MethodChanged").status())
        assertEquals(listOf("value()I"), byName.getValue("MethodChanged").methods().metadataChangedMethods())
        assertEquals(Status.STRUCTURAL, byName.getValue("PoolSample").status())
        assertEquals(Status.MISSING, byName.getValue("Missing").status())
        assertEquals(Status.EXTRA, byName.getValue("Extra").status())
        assertEquals(1, report.aggregate().classes().missing())
        assertEquals(1, report.aggregate().classes().extra())
    }

    @Test
    fun `report serialization and persisted data are byte deterministic`() {
        val bytes = compile("Stable", "public class Stable { int value() { return 1; } }")
        val report = VerificationEngine().compare("fixture", "client", jar("Stable" to bytes), jar("Stable" to bytes), candidateIdentity())
        val store = VerificationReportStore()
        val first = temporaryDirectory.resolve("first.json")
        val second = temporaryDirectory.resolve("second.json")

        store.write(first, report)
        store.write(second, report)

        assertContentEquals(Files.readAllBytes(first), Files.readAllBytes(second))
        assertEquals(report, store.read(first))
    }

    @Test
    fun `baseline comparison classifies promotions and regressions`() {
        val stableSource = "public class Stable { int value() { return 1; } }"
        val debugSource = "public class DebugTarget { int value(int x) { int y = x + 1; return y; } }"
        val stable = compile("Stable", stableSource)
        val stableChanged = compile("Stable", stableSource.replace("return 1", "return 2"))
        val debug = compile("DebugTarget", debugSource, "-g")
        val debugChanged = compile("DebugTarget", debugSource, "-g:none")
        val reference = jar("Stable" to stable, "DebugTarget" to debug)
        val before = VerificationEngine().compare(
            "fixture", "client", reference, jar("Stable" to stableChanged, "DebugTarget" to debug), candidateIdentity("1"),
        )
        val after = VerificationEngine().compare(
            "fixture", "client", reference, jar("Stable" to stable, "DebugTarget" to debugChanged), candidateIdentity("2"),
        )

        val comparison = VerificationComparisonEngine.compare(before, after)

        assertEquals(1, comparison.promoted())
        assertEquals(1, comparison.regressed())
        assertTrue(comparison.changedClasses().any {
            it.binaryName() == "Stable" && it.before() == Status.FUNCTIONAL && it.after() == Status.EXACT &&
                it.direction() == Direction.PROMOTED
        })
        assertTrue(comparison.changedClasses().any {
            it.binaryName() == "DebugTarget" && it.before() == Status.EXACT && it.after() == Status.COMPILES &&
                it.direction() == Direction.REGRESSED
        })
    }

    @Test
    fun `representation inspection surfaces normalized table ordering differences`() {
        val first = compile(
            "Outer",
            "public class Outer { public static class Alpha {} public static class Beta {} }",
        )
        val second = compile(
            "Outer",
            "public class Outer { public static class Beta {} public static class Alpha {} }",
        )

        val details = ClassfileRepresentationInspector().inspect(first, second)

        assertTrue(details.differences.any { it.startsWith("InnerClasses entry order:") })
    }

    private fun compile(className: String, source: String, debug: String = "-g"): ByteArray =
        ClassfileTestFixtures.compile(temporaryDirectory, className, source, debug)

    private fun jar(vararg values: Pair<String, ByteArray>): IndexModel.JarIndex {
        val classes = values.map { (name, bytes) -> indexer.index(bytes, "$name.class") }.sortedBy { it.entryPath() }
        return IndexModel.JarIndex(
            IndexModel.SCHEMA_VERSION,
            IndexModel.ArtifactIdentity("fixture", "client", "fixture", 1, "0".repeat(40), "a".repeat(64)),
            emptyList(),
            classes,
            emptyList(),
        )
    }

    private fun candidateIdentity(suffix: String = "") = CandidateIdentity(
        1,
        if (suffix.isEmpty()) "b".repeat(64) else suffix.repeat(64),
        emptyList(),
        "1",
        "c".repeat(64),
        1,
        CompilationModel.CompilerIdentity("fixture", 25, "fixture runtime"),
        listOf("--release", "25"),
        "d".repeat(64),
        "e".repeat(64),
    )

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
}
