package dev.mcrecon.workspace

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GradleProjectGeneratorTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `templates are deterministic and model Java 25 and reconstructed runtime`() {
        val plan = workspacePlan(temporaryDirectory)
        val generator = GradleProjectGenerator()

        val first = generator.build(plan)
        val second = generator.build(plan)

        assertEquals(first, second)
        assertContains(first, "languageVersion.set(JavaLanguageVersion.of(25))")
        assertContains(first, "sourceCompatibility = JavaVersion.VERSION_25")
        assertContains(first, "resources.setSrcDirs(listOf(\"src/main/resources\"))")
        assertContains(first, "options.compilerArgs.addAll(listOf(\"-proc:none\", \"-parameters\"))")
        assertContains(first, "tasks.register<JavaExec>(\"runClient\")")
        assertContains(first, "classpath = sourceSets.main.get().runtimeClasspath")
        assertContains(first, "mainClass.set(\"example.Main\")")
        assertFalse(first.contains("client.jar"))
        assertFalse(first.contains(temporaryDirectory.toString()))
    }
}
