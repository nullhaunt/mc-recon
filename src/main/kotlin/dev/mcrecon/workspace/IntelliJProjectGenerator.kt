package dev.mcrecon.workspace

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Generates only IntelliJ's supported shared project-file run configurations. */
class IntelliJProjectGenerator {
    fun generate(plan: WorkspacePlan, root: Path) {
        val directory = root.resolve(RUN_CONFIGURATION_DIRECTORY)
        write(directory.resolve(APPLICATION_FILE), application(plan))
        write(directory.resolve(GRADLE_FILE), gradle())
    }

    fun application(plan: WorkspacePlan): String {
        val mainClass = plan.launch.mainClass
            ?: throw WorkspaceException("Client launch specification has no main class")
        val replacements = mapOf(
            WorkspacePlaceholders.GAME_DIRECTORY to PROJECT_GAME_DIRECTORY,
            WorkspacePlaceholders.ASSETS_ROOT to PROJECT_ASSETS_ROOT,
            WorkspacePlaceholders.NATIVES_DIRECTORY to PROJECT_NATIVES_DIRECTORY,
            WorkspacePlaceholders.LOGGING_FILE to PROJECT_LOGGING_FILE,
            WorkspacePlaceholders.USERNAME to "mc-recon-demo",
            WorkspacePlaceholders.UUID to "00000000000000000000000000000000",
            // This is an intentionally inert demo value, not a persisted account credential.
            WorkspacePlaceholders.ACCESS_TOKEN to "0",
            WorkspacePlaceholders.CLIENT_ID to "",
            WorkspacePlaceholders.XUID to "",
        )
        fun expand(value: String): String = plan.launch.sensitiveValues
            .filter(String::isNotEmpty)
            .fold(replacements.entries.fold(value) { current, entry -> current.replace(entry.key, entry.value) }) { current, secret ->
                current.replace(secret, "0")
            }.also { expanded ->
            if ("@MC_RECON_" in expanded) throw WorkspaceException("Unexpanded workspace launch placeholder in IntelliJ configuration")
        }

        val vmParameters = commandLine(plan.launch.jvmArguments.map(::expand))
        val programParameters = commandLine(plan.launch.gameArguments.map(::expand))
        // IntelliJ's Gradle importer replaces dots in Gradle project names with underscores.
        val module = "minecraft-${plan.manifest.id.replace('.', '_')}.main"
        return """
            <component name="ProjectRunConfigurationManager">
              <configuration default="false" name="Minecraft Client" type="Application" factoryName="Application">
                <option name="MAIN_CLASS_NAME" value="${xml(mainClass)}" />
                <module name="${xml(module)}" />
                <option name="PROGRAM_PARAMETERS" value="${xml(programParameters)}" />
                <option name="VM_PARAMETERS" value="${xml(vmParameters)}" />
                <option name="WORKING_DIRECTORY" value="${xml(PROJECT_GAME_DIRECTORY)}" />
                <option name="ALTERNATIVE_JRE_PATH_ENABLED" value="false" />
                <method v="2">
                  <option name="Make" enabled="true" />
                </method>
              </configuration>
            </component>
        """.trimIndent() + "\n"
    }

    fun gradle(): String = """
        <component name="ProjectRunConfigurationManager">
          <configuration default="false" name="Minecraft Client (Gradle)" type="GradleRunConfiguration" factoryName="Gradle">
            <ExternalSystemSettings>
              <option name="executionName" />
              <option name="externalProjectPath" value="${xml(PROJECT_DIRECTORY)}" />
              <option name="externalSystemIdString" value="GRADLE" />
              <option name="scriptParameters" value="" />
              <option name="taskDescriptions">
                <list />
              </option>
              <option name="taskNames">
                <list>
                  <option value="runClient" />
                </list>
              </option>
              <option name="vmOptions" />
            </ExternalSystemSettings>
            <ExternalSystemDebugServerProcess>true</ExternalSystemDebugServerProcess>
            <ExternalSystemReattachDebugProcess>true</ExternalSystemReattachDebugProcess>
            <DebugAllEnabled>false</DebugAllEnabled>
            <RunAsTest>false</RunAsTest>
            <method v="2" />
          </configuration>
        </component>
    """.trimIndent() + "\n"

    private fun commandLine(values: List<String>): String = values.joinToString(" ") { value ->
        if (value.isEmpty() || value.any(Char::isWhitespace) || '"' in value || PROJECT_DIRECTORY in value) {
            "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        } else {
            value
        }
    }

    private fun xml(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> character
                },
            )
        }
    }

    private fun write(path: Path, value: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, value, StandardCharsets.UTF_8)
    }

    companion object {
        const val RUN_CONFIGURATION_DIRECTORY = ".idea/runConfigurations"
        const val APPLICATION_FILE = "Minecraft_Client.xml"
        const val GRADLE_FILE = "Minecraft_Client_Gradle.xml"
        const val PROJECT_DIRECTORY = "\$PROJECT_DIR\$"
        const val PROJECT_GAME_DIRECTORY = "\$PROJECT_DIR\$/run/client"
        const val PROJECT_ASSETS_ROOT = "\$PROJECT_DIR\$/.mc-recon/assets"
        const val PROJECT_NATIVES_DIRECTORY = "\$PROJECT_DIR\$/.mc-recon/natives"
        const val PROJECT_LOGGING_FILE = "\$PROJECT_DIR\$/.mc-recon/logging/client.xml"
    }
}
