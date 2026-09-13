package dev.mcrecon.launcher

import java.nio.file.Path

enum class LaunchSide { CLIENT, SERVER }

data class LaunchSpecification(
    val side: LaunchSide,
    val executable: Path,
    val workingDirectory: Path,
    val jvmArguments: List<String>,
    val classpath: List<Path> = emptyList(),
    val mainClass: String? = null,
    val gameArguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val sensitiveValues: Set<String> = emptySet(),
) {
    fun command(): List<String> = buildList {
        add(executable.toString())
        addAll(jvmArguments)
        mainClass?.let(::add)
        addAll(gameArguments)
    }
}

data class LaunchFeatures(
    val isDemoUser: Boolean = false,
    val hasCustomResolution: Boolean = false,
    val hasQuickPlaysSupport: Boolean = false,
    val isQuickPlaySingleplayer: Boolean = false,
    val isQuickPlayMultiplayer: Boolean = false,
    val isQuickPlayRealms: Boolean = false,
) {
    fun toRuleValues(): Map<String, Boolean> = mapOf(
        "is_demo_user" to isDemoUser,
        "has_custom_resolution" to hasCustomResolution,
        "has_quick_plays_support" to hasQuickPlaysSupport,
        "is_quick_play_singleplayer" to isQuickPlaySingleplayer,
        "is_quick_play_multiplayer" to isQuickPlayMultiplayer,
        "is_quick_play_realms" to isQuickPlayRealms,
    )
}

class LaunchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object LaunchCommandRenderer {
    fun render(specification: LaunchSpecification): String {
        val redacted = specification.command().map { argument -> redact(argument, specification.sensitiveValues) }
        return buildString {
            appendLine("Working directory: ${specification.workingDirectory}")
            appendLine("Executable: ${redacted.first()}")
            appendLine("Arguments:")
            redacted.drop(1).forEachIndexed { index, argument ->
                appendLine("  [$index] ${quote(argument)}")
            }
        }.trimEnd()
    }

    private fun redact(value: String, secrets: Set<String>): String =
        secrets.filter(String::isNotEmpty).fold(value) { result, secret -> result.replace(secret, "<redacted>") }

    private fun quote(value: String): String = if (value.any(Char::isWhitespace) || value.isEmpty()) {
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    } else {
        value
    }
}

class ProcessLauncher {
    fun launch(specification: LaunchSpecification): Int {
        val processBuilder = ProcessBuilder(specification.command())
            .directory(specification.workingDirectory.toFile())
            .inheritIO()
        processBuilder.environment().putAll(specification.environment)
        return try {
            processBuilder.start().waitFor()
        } catch (exception: Exception) {
            throw LaunchException("Could not launch ${specification.side.name.lowercase()} process", exception)
        }
    }
}
