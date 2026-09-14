package dev.mcrecon.workspace

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class GradleProjectGenerator {
    fun generate(plan: WorkspacePlan, root: Path) {
        write(root.resolve("settings.gradle.kts"), settings(plan))
        write(root.resolve("build.gradle.kts"), build(plan))
        write(root.resolve("gradle.properties"), gradleProperties())
        write(root.resolve("README.md"), readme(plan))
        write(root.resolve(".gitignore"), gitignore())
    }

    fun settings(plan: WorkspacePlan): String =
        "rootProject.name = ${stringLiteral("minecraft-${plan.manifest.id}")}\n"

    fun build(plan: WorkspacePlan): String {
        val compilePaths = plan.dependencies.filter { it.compileOrder != null }.sortedBy { it.compileOrder }.map { it.workspacePath }
        val runtimePaths = plan.dependencies.filter { it.runtimeOrder != null }.sortedBy { it.runtimeOrder }.map { it.workspacePath }
        val jvmArguments = list(plan.launch.jvmArguments)
        val gameArguments = list(plan.launch.gameArguments)
        val java = plan.manifest.javaVersion.majorVersion
        return """
            import org.gradle.api.tasks.compile.JavaCompile

            plugins {
                java
            }

            group = "dev.mcrecon.workspace"
            version = ${stringLiteral(plan.manifest.id + "-dev")}

            java {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of($java))
                }
                sourceCompatibility = JavaVersion.VERSION_25
                targetCompatibility = JavaVersion.VERSION_25
            }

            sourceSets {
                main {
                    java.setSrcDirs(listOf("src/main/java"))
                    resources.setSrcDirs(listOf("src/main/resources"))
                }
            }

            val exactCompileArtifacts = files(
            ${fileArguments(compilePaths)}
            )
            val exactRuntimeArtifacts = files(
            ${fileArguments(runtimePaths)}
            )

            dependencies {
                compileOnly(exactCompileArtifacts)
                runtimeOnly(exactRuntimeArtifacts)
            }

            tasks.withType<JavaCompile>().configureEach {
                options.encoding = "UTF-8"
                options.isDebug = true
                options.compilerArgs.addAll(listOf("-proc:none", "-parameters"))
            }

            tasks.named<Jar>("jar") {
                manifest {
                    attributes["Main-Class"] = ${stringLiteral(plan.manifest.mainClass)}
                }
            }

            val workspaceGameDirectory = layout.projectDirectory.dir("run/client")
            val workspaceAssetsRoot = layout.projectDirectory.dir(".mc-recon/assets")
            val workspaceNativesDirectory = layout.projectDirectory.dir(".mc-recon/natives")
            val workspaceLoggingFile = layout.projectDirectory.file(".mc-recon/logging/client.xml")

            fun launchSetting(propertyName: String, environmentName: String, fallback: String): String =
                providers.gradleProperty(propertyName)
                    .orElse(providers.environmentVariable(environmentName))
                    .getOrElse(fallback)

            val launchUsername = launchSetting("mcReconUsername", "MC_RECON_USERNAME", "mc-recon-demo")
            val launchUuid = launchSetting("mcReconUuid", "MC_RECON_UUID", "00000000000000000000000000000000").replace("-", "")
            val launchAccessToken = launchSetting("mcReconAccessToken", "MC_RECON_ACCESS_TOKEN", "mc-recon-unauthenticated-token")
            val launchClientId = launchSetting("mcReconClientId", "MC_RECON_CLIENT_ID", "")
            val launchXuid = launchSetting("mcReconXuid", "MC_RECON_XUID", "")

            fun expandLaunchValue(value: String): String = value
                .replace(${stringLiteral(WorkspacePlaceholders.GAME_DIRECTORY)}, workspaceGameDirectory.asFile.absolutePath)
                .replace(${stringLiteral(WorkspacePlaceholders.ASSETS_ROOT)}, workspaceAssetsRoot.asFile.absolutePath)
                .replace(${stringLiteral(WorkspacePlaceholders.NATIVES_DIRECTORY)}, workspaceNativesDirectory.asFile.absolutePath)
                .replace(${stringLiteral(WorkspacePlaceholders.LOGGING_FILE)}, workspaceLoggingFile.asFile.absolutePath)
                .replace(${stringLiteral(WorkspacePlaceholders.USERNAME)}, launchUsername)
                .replace(${stringLiteral(WorkspacePlaceholders.UUID)}, launchUuid)
                .replace(${stringLiteral(WorkspacePlaceholders.ACCESS_TOKEN)}, launchAccessToken)
                .replace(${stringLiteral(WorkspacePlaceholders.CLIENT_ID)}, launchClientId)
                .replace(${stringLiteral(WorkspacePlaceholders.XUID)}, launchXuid)

            val manifestJvmArguments = $jvmArguments
            val manifestGameArguments = $gameArguments
            val extraJvmArguments = providers.gradleProperty("mcReconJvmArgs")
                .orElse(providers.environmentVariable("MC_RECON_JVM_ARGS"))
                .orNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.split(Regex("\\s+"))
                .orEmpty()

            tasks.register<JavaExec>("runClient") {
                group = "application"
                description = "Runs the editable reconstructed Minecraft ${plan.manifest.id} client in demo mode."
                dependsOn(tasks.named("classes"))
                javaLauncher.set(javaToolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of($java))
                })
                mainClass.set(${stringLiteral(plan.manifest.mainClass)})
                classpath = sourceSets.main.get().runtimeClasspath
                workingDir(workspaceGameDirectory)
                jvmArgs(manifestJvmArguments.map(::expandLaunchValue) + extraJvmArguments)
                args(manifestGameArguments.map(::expandLaunchValue))
                doFirst {
                    workspaceGameDirectory.asFile.mkdirs()
                }
            }
        """.trimIndent() + "\n"
    }

    fun readme(plan: WorkspacePlan): String = """
        # Minecraft ${plan.manifest.id} reconstructed workspace

        This is an editable development copy generated by mc-recon from Mojang's published
        `${plan.manifest.id}` client and the attributable reconstructed-source baseline. It is
        not Mojang's original source repository. The canonical reconstruction remains outside
        this project in mc-recon's cache; edits under `src/main/java` affect only this workspace.

        ## Build and run

        Java 25 is required. The pinned Gradle 9.1.0 wrapper is the first Gradle release with
        supported Java 25 execution and toolchains.

        Windows:

        ```powershell
        .\\gradlew.bat classes
        .\\gradlew.bat build
        .\\gradlew.bat runClient
        ```

        Unix-like systems:

        ```bash
        ./gradlew classes
        ./gradlew build
        ./gradlew runClient
        ```

        Open this directory as a Gradle project in IntelliJ IDEA. Editable Java is in
        `src/main/java`, client JAR resources are in `src/main/resources`, and the isolated game
        directory is `run/client`.

        `runClient` defaults to the same synthetic unauthenticated demo mode as mc-recon's proven
        launcher. Optional session values can be supplied ephemerally with `MC_RECON_USERNAME`,
        `MC_RECON_UUID`, `MC_RECON_ACCESS_TOKEN`, `MC_RECON_CLIENT_ID`, and `MC_RECON_XUID`, or the
        corresponding Gradle project properties (`mcReconUsername`, etc.). Never commit tokens.
        Additional simple whitespace-separated JVM options may be supplied through
        `MC_RECON_JVM_ARGS` or `-PmcReconJvmArgs=...`.

        ## Runtime and provenance

        The workspace is self-contained for the host platform on which it was generated. Exact
        resolver-verified dependencies, logging configuration, launcher assets, and prepared
        natives are copied beneath `.mc-recon/`. Assets remain external launcher assets and are
        intentionally not classpath resources. The payload directory is ignored by Git to avoid
        accidentally committing Mojang artifacts. Regenerate on another operating system or
        architecture to select that platform's libraries, natives, and JVM rules.

        `reconstruction.lock.json` records the initial source and artifact provenance.
        `workspace-manifest.json` fingerprints the initial generated tree but is not an ongoing
        restriction after you edit it. Generation refuses any pre-existing output path.

        Client resources are copied without transformation except that `.class` entries and
        `META-INF/MANIFEST.MF`, `META-INF/INDEX.LIST`, `META-INF/*.SF`, `META-INF/*.RSA`,
        `META-INF/*.DSA`, `META-INF/*.EC`, and `META-INF/SIG-*` are excluded. Those manifest/index
        and signature artifacts describe Mojang's original JAR and become invalid in a modified
        development JAR. `jar` builds a development archive; it does not reproduce Mojang's JAR.
    """.trimIndent() + "\n"

    fun gitignore(): String = """
        .gradle/
        build/
        out/
        .idea/
        *.iml
        run/
        .mc-recon/
        local.properties
        local.gradle.properties
        .env
        *.session.json
    """.trimIndent() + "\n"

    fun gradleProperties(): String = """
        # The complete reconstructed client is a large single Java compilation unit set.
        org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8
    """.trimIndent() + "\n"

    private fun fileArguments(paths: List<String>): String = paths.joinToString(",\n") { "                file(${stringLiteral(it)})" }

    private fun list(values: List<String>): String =
        if (values.isEmpty()) "emptyList<String>()" else values.joinToString(",\n", "listOf(\n", "\n            )") {
            "                ${stringLiteral(it)}"
        }

    private fun stringLiteral(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append("\\$")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text, StandardCharsets.UTF_8)
    }
}
