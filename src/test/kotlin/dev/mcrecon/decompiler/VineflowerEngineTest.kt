package dev.mcrecon.decompiler

import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VineflowerEngineTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `synthetic modern Java jar decompiles deterministically without emitting dependencies`() {
        val fixtureJar = compileFixtureJar()
        val libraryJar = compileLibraryJar()
        val first = temporaryDirectory.resolve("first")
        val second = temporaryDirectory.resolve("second")
        val javaHome = Path.of(System.getProperty("java.home"))
        val engine = VineflowerEngine()

        val firstRun = engine.decompile(DecompilerInvocation(listOf(libraryJar), javaHome, first, fixtureJar))
        val secondRun = engine.decompile(DecompilerInvocation(listOf(libraryJar), javaHome, second, fixtureJar))

        assertTrue(firstRun.failedClasses.isEmpty())
        assertTrue(secondRun.failedClasses.isEmpty())
        assertFalse(firstRun.diagnostics.any { it.severity() == "ERROR" })
        assertEquals(SourceTreeHasher.hash(first), SourceTreeHasher.hash(second))
        val source = Files.readString(first.resolve("fixture/Features.java"), StandardCharsets.UTF_8)
        assertContains(source, "record Features")
        assertContains(source, "enum Kind")
        assertContains(source, "class Nested")
        assertContains(source, "() ->")
        assertFalse(Files.exists(first.resolve("dependency/Context.java")))
    }

    private fun compileFixtureJar(): Path = compileJar(
        "fixture.Features",
        """
            package fixture;

            import dependency.Context;
            import java.util.List;

            public record Features<T>(T value) {
                public enum Kind { FIRST, SECOND }
                public static final class Nested<U> {
                    private final List<U> values;
                    public Nested(List<U> values) { this.values = values; }
                    public List<U> values() { return values; }
                }
                public Runnable task(Context context) {
                    return () -> context.accept(String.valueOf(value));
                }
            }
        """,
        classpath = compileLibraryClasses(),
        name = "fixture.jar",
    )

    private fun compileLibraryJar(): Path {
        val classes = compileLibraryClasses()
        return jar(classes, temporaryDirectory.resolve("library.jar"))
    }

    private fun compileLibraryClasses(): Path = compile(
        "dependency.Context",
        """
            package dependency;
            public final class Context {
                public void accept(String value) {}
            }
        """,
        null,
        "library-classes",
    )

    private fun compileJar(className: String, source: String, classpath: Path, name: String): Path =
        jar(compile(className, source, classpath, "fixture-classes"), temporaryDirectory.resolve(name))

    private fun compile(className: String, source: String, classpath: Path?, directory: String): Path {
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "tests require a full JDK" }
        val sourceRoot = temporaryDirectory.resolve("$directory-src")
        val outputRoot = temporaryDirectory.resolve(directory)
        val sourceFile = sourceRoot.resolve(className.replace('.', '/') + ".java")
        Files.createDirectories(sourceFile.parent)
        Files.createDirectories(outputRoot)
        Files.writeString(sourceFile, source.trimIndent(), StandardCharsets.UTF_8)
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8).use { manager ->
            val options = buildList {
                addAll(listOf("-g", "--release", "25", "-d", outputRoot.toString()))
                classpath?.let { addAll(listOf("-classpath", it.toString())) }
            }
            val success = compiler.getTask(null, manager, diagnostics, options, null, manager.getJavaFileObjects(sourceFile.toFile())).call()
            check(success) { diagnostics.diagnostics.joinToString("\n") }
        }
        return outputRoot
    }

    private fun jar(classes: Path, output: Path): Path {
        JarOutputStream(Files.newOutputStream(output)).use { jar ->
            Files.walk(classes).use { paths ->
                paths.filter(Files::isRegularFile).sorted().forEach { file ->
                    val name = classes.relativize(file).joinToString("/") { it.toString() }
                    jar.putNextEntry(JarEntry(name).apply { time = 0 })
                    Files.copy(file, jar)
                    jar.closeEntry()
                }
            }
        }
        return output
    }
}
