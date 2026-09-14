package dev.mcrecon.reconstruction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceTransformationsTest {
    @Test
    fun `unavailable annotation rule is selective and idempotent`() {
        val source = """
            import org.jetbrains.annotations.Contract;
            import javax.annotation.Nullable;

            class Example {
               @Contract(pure = true)
               String value() { return "x"; }
            }
        """.trimIndent() + "\n"

        val transformed = transformed(source, UnavailableCompileAnnotationRule)

        assertTrue("Contract" !in transformed)
        assertTrue("javax.annotation.Nullable" in transformed)
        assertIdempotent(transformed, UnavailableCompileAnnotationRule)
    }

    @Test
    fun `brain return cast rule requires a matching generic override shape and is idempotent`() {
        val source = """
            class Example {
               public Brain<Wolf> getBrain() {
                  return super.getBrain();
               }

               public Other<Wolf> getOther() {
                  return super.getOther();
               }
            }
        """.trimIndent() + "\n"

        val transformed = transformed(source, BrainReturnCastRule)

        assertTrue("return (Brain<Wolf>)(Brain<?>)super.getBrain();" in transformed)
        assertTrue("return super.getOther();" in transformed)
        assertIdempotent(transformed, BrainReturnCastRule)
    }

    @Test
    fun `qualified type annotation rule moves only nested qualified annotations and is idempotent`() {
        val source = """
            class Example {
               @Nullable RealmsPdpScreen.PdpHeaderEntry.Subtitle nested;
               @Nullable String simple;
            }
        """.trimIndent() + "\n"

        val transformed = transformed(source, QualifiedTypeAnnotationRule)

        assertTrue("RealmsPdpScreen.PdpHeaderEntry.@Nullable Subtitle nested" in transformed)
        assertTrue("@Nullable String simple" in transformed)
        assertIdempotent(transformed, QualifiedTypeAnnotationRule)
    }

    @Test
    fun `functional target rule repairs known declarations but leaves an existing cast alone`() {
        val source = """
            class Example {
               IntegerModifier<Integer> ADD = value -> value + 1;
               IntegerModifier<Integer> KEEP = (IntegerModifier.Simple)(value -> value);
               Unrelated<Integer> OTHER = value -> value;
            }
        """.trimIndent() + "\n"

        val transformed = transformed(source, FunctionalInterfaceTargetRule)

        assertTrue("ADD = (IntegerModifier.Simple)value -> value + 1" in transformed)
        assertTrue("KEEP = (IntegerModifier.Simple)(value -> value)" in transformed)
        assertTrue("Unrelated<Integer> OTHER = value -> value" in transformed)
        assertIdempotent(transformed, FunctionalInterfaceTargetRule)
    }

    @Test
    fun `exact patches apply once and reject unexpected input`() {
        val patch = ExactSourcePatch("PATCH-001", "pkg/Test.java", "replace literal", "value = 1", "value = 2")

        val (output, change) = patch.apply("class Test { int value = 1; }\n")

        assertEquals("class Test { int value = 2; }\n", output)
        assertEquals("PATCH-001", change.id)
        assertEquals("explicit-patch", change.kind)
        assertFailsWith<ReconstructionException> { patch.apply(output) }
        assertFailsWith<ReconstructionException> { patch.apply("class Test { int value = 1; int value = 1; }\n") }
    }

    private fun transformed(source: String, rule: ReconstructionRule): String = applyRule("pkg/Test.java", source, rule).first

    private fun assertIdempotent(source: String, rule: ReconstructionRule) {
        assertEquals(source, transformed(source, rule))
    }
}
