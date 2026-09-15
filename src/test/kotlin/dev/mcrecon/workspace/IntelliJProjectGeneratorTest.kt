package dev.mcrecon.workspace

import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntelliJProjectGeneratorTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `application configuration is deterministic portable and uses the workspace module classpath`() {
        val plan = workspacePlan(temporaryDirectory.resolve("inputs"))
        val generator = IntelliJProjectGenerator()

        val first = generator.application(plan)
        val second = generator.application(plan)
        val configuration = configuration(first)
        val options = configuration.getElementsByTagName("option").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
                .filter { it.parentNode == configuration }
                .associate { it.getAttribute("name") to it.getAttribute("value") }
        }

        assertEquals(first, second)
        assertEquals("Minecraft Client", configuration.getAttribute("name"))
        assertEquals("Application", configuration.getAttribute("type"))
        assertEquals("example.Main", options.getValue("MAIN_CLASS_NAME"))
        assertEquals("${PROJECT_DIR}/run/client", options.getValue("WORKING_DIRECTORY"))
        assertEquals("minecraft-test-version.main", configuration.getElementsByTagName("module").item(0).attributes.getNamedItem("name").nodeValue)
        assertContains(options.getValue("VM_PARAMETERS"), "-Djava.library.path=\$PROJECT_DIR\$/.mc-recon/natives/java")
        assertContains(options.getValue("VM_PARAMETERS"), "-Dlog4j.configurationFile=\$PROJECT_DIR\$/.mc-recon/logging/client.xml")
        assertContains(options.getValue("PROGRAM_PARAMETERS"), "--assetsDir")
        assertContains(options.getValue("PROGRAM_PARAMETERS"), "--assetIndex test")
        assertContains(options.getValue("PROGRAM_PARAMETERS"), "--version test-version")
        assertFalse(options.getValue("VM_PARAMETERS").contains("-cp"))
        assertFalse(first.contains("client.jar"))
        assertFalse(first.contains(temporaryDirectory.toString()))
        assertFalse(first.contains(WorkspacePlaceholders.ACCESS_TOKEN))
        assertFalse(first.contains("mc-recon-unauthenticated-token"))
    }

    @Test
    fun `generation writes only shared configurations and gitignore keeps local idea state ignored`() {
        val plan = workspacePlan(temporaryDirectory.resolve("inputs"))
        val output = temporaryDirectory.resolve("project")

        GradleProjectGenerator().generate(plan, output)

        assertTrue(Files.isRegularFile(output.resolve(".idea/runConfigurations/Minecraft_Client.xml")))
        assertTrue(Files.isRegularFile(output.resolve(".idea/runConfigurations/Minecraft_Client_Gradle.xml")))
        assertFalse(Files.exists(output.resolve(".idea/workspace.xml")))
        val gradleConfiguration = configuration(Files.readString(output.resolve(".idea/runConfigurations/Minecraft_Client_Gradle.xml")))
        assertEquals("Minecraft Client (Gradle)", gradleConfiguration.getAttribute("name"))
        assertTrue(
            (0 until gradleConfiguration.getElementsByTagName("option").length)
                .map { gradleConfiguration.getElementsByTagName("option").item(it) as Element }
                .any { it.getAttribute("value") == "runClient" },
        )
        val ignore = Files.readString(output.resolve(".gitignore"))
        assertContains(ignore, ".idea/*")
        assertContains(ignore, "!.idea/runConfigurations/Minecraft_Client.xml")
        assertContains(ignore, "!.idea/runConfigurations/Minecraft_Client_Gradle.xml")
        assertFalse(ignore.lines().any { it == ".idea/" })
    }

    @Test
    fun `application configuration redacts every launch value marked sensitive`() {
        val original = workspacePlan(temporaryDirectory.resolve("secret-inputs"))
        val secret = "real-session-secret"
        val plan = original.copy(
            launch = original.launch.copy(
                gameArguments = original.launch.gameArguments + listOf("--futureSessionField", secret),
                sensitiveValues = original.launch.sensitiveValues + secret,
            ),
        )

        val application = IntelliJProjectGenerator().application(plan)

        assertFalse(application.contains(secret))
        assertContains(application, "--futureSessionField 0")
    }

    @Test
    fun `application module name follows IntelliJ Gradle dot normalization`() {
        val original = workspacePlan(temporaryDirectory.resolve("dotted-version-inputs"))
        val plan = original.copy(manifest = original.manifest.copy(id = "26.3-test"))

        val configuration = configuration(IntelliJProjectGenerator().application(plan))

        assertEquals(
            "minecraft-26_3-test.main",
            configuration.getElementsByTagName("module").item(0).attributes.getNamedItem("name").nodeValue,
        )
    }

    private fun configuration(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = factory.newDocumentBuilder().parse(xml.byteInputStream())
        return document.getElementsByTagName("configuration").item(0) as Element
    }

    private companion object {
        const val PROJECT_DIR = "\$PROJECT_DIR\$"
    }
}
