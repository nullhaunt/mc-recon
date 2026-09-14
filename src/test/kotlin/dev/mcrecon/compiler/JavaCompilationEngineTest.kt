package dev.mcrecon.compiler

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaCompilationEngineTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `Java 25 source compiles deterministically with explicit processor policy`() {
        val source = temporaryDirectory.resolve("source")
        Files.createDirectories(source.resolve("sample"))
        Files.writeString(
            source.resolve("sample/Shape.java"),
            """
                package sample;
                public sealed interface Shape permits Circle {
                    int area();
                }
                record Circle(int radius) implements Shape {
                    public int area() { return radius * radius; }
                }
            """.trimIndent() + "\n",
        )
        val first = temporaryDirectory.resolve("classes-a")
        val second = temporaryDirectory.resolve("classes-b")
        val engine = JavaCompilationEngine()

        val a = engine.compile(source, first, emptyList(), 25)
        val b = engine.compile(source, second, emptyList(), 25)

        assertTrue(a.success)
        assertEquals(0, a.diagnostics.count { it.severity() == "ERROR" })
        assertTrue("-proc:none" in JavaCompilationEngine.logicalOptions(25))
        assertEquals(
            DeterministicTreeHasher.hash(first, CompilationService.CLASS_TREE_DOMAIN),
            DeterministicTreeHasher.hash(second, CompilationService.CLASS_TREE_DOMAIN),
        )
    }

    @Test
    fun `compiler diagnostics are structured`() {
        val source = temporaryDirectory.resolve("broken")
        Files.createDirectories(source.resolve("sample"))
        Files.writeString(source.resolve("sample/Broken.java"), "package sample; class Broken { Missing value; }\n")

        val result = JavaCompilationEngine().compile(source, temporaryDirectory.resolve("broken-classes"), emptyList(), 25)

        assertFalse(result.success)
        val diagnostic = result.diagnostics.single { it.severity() == "ERROR" }
        assertEquals("sample/Broken.java", diagnostic.sourcePath())
        assertTrue(diagnostic.line() > 0)
        assertTrue(diagnostic.column() > 0)
        assertTrue(diagnostic.diagnosticCode().startsWith("compiler.err."))
        assertTrue(diagnostic.message().isNotBlank())
    }

    @Test
    fun `external binary classpath is honored without compiling dependencies with the source set`() {
        val dependencySource = temporaryDirectory.resolve("dependency-source")
        Files.createDirectories(dependencySource.resolve("external"))
        Files.writeString(dependencySource.resolve("external/Api.java"), "package external; public class Api {}\n")
        val dependencyClasses = temporaryDirectory.resolve("dependency-classes")
        assertTrue(JavaCompilationEngine().compile(dependencySource, dependencyClasses, emptyList(), 25).success)

        val applicationSource = temporaryDirectory.resolve("application-source")
        Files.createDirectories(applicationSource.resolve("sample"))
        Files.writeString(applicationSource.resolve("sample/UseApi.java"), "package sample; class UseApi { external.Api api; }\n")

        val result = JavaCompilationEngine().compile(
            applicationSource,
            temporaryDirectory.resolve("application-classes"),
            listOf(dependencyClasses),
            25,
        )

        assertTrue(result.success)
        assertEquals(1, result.sourceFiles)
    }

    @Test
    fun `annotation processors discovered on the classpath are not executed`() {
        val processorSource = temporaryDirectory.resolve("processor-source")
        Files.createDirectories(processorSource.resolve("external"))
        Files.writeString(
            processorSource.resolve("external/FailProcessor.java"),
            """
                package external;
                import java.util.Set;
                import javax.annotation.processing.*;
                import javax.lang.model.SourceVersion;
                import javax.lang.model.element.TypeElement;
                @SupportedAnnotationTypes("*")
                @SupportedSourceVersion(SourceVersion.RELEASE_25)
                public final class FailProcessor extends AbstractProcessor {
                    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        throw new AssertionError("processor must not execute");
                    }
                }
            """.trimIndent() + "\n",
        )
        val processorClasses = temporaryDirectory.resolve("processor-classes")
        assertTrue(JavaCompilationEngine().compile(processorSource, processorClasses, emptyList(), 25).success)
        val services = processorClasses.resolve("META-INF/services")
        Files.createDirectories(services)
        Files.writeString(services.resolve("javax.annotation.processing.Processor"), "external.FailProcessor\n")

        val source = temporaryDirectory.resolve("processor-test-source")
        Files.createDirectories(source)
        Files.writeString(source.resolve("Example.java"), "public class Example {}\n")

        val result = JavaCompilationEngine().compile(source, temporaryDirectory.resolve("processor-test-classes"), listOf(processorClasses), 25)

        assertTrue(result.success)
        assertEquals(0, result.diagnostics.count { it.severity() == "ERROR" })
    }
}
