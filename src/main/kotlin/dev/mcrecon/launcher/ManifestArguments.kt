package dev.mcrecon.launcher

import dev.mcrecon.manifest.ManifestArgument
import dev.mcrecon.manifest.rules.RuleContext
import dev.mcrecon.manifest.rules.RuleEvaluator

data class PlaceholderValue(val value: String, val sensitive: Boolean = false)

data class ExpandedArguments(
    val values: List<String>,
    val sensitiveValues: Set<String>,
)

class ManifestArgumentEvaluator {
    fun evaluate(
        arguments: List<ManifestArgument>,
        context: RuleContext,
        placeholders: Map<String, PlaceholderValue>,
    ): ExpandedArguments {
        val expanded = mutableListOf<String>()
        val secrets = linkedSetOf<String>()
        for (argument in arguments) {
            if (argument is ManifestArgument.Conditional && !RuleEvaluator.isAllowed(argument.rules, context)) continue
            for (value in argument.values) {
                expanded += expand(value, placeholders, secrets)
            }
        }
        return ExpandedArguments(expanded, secrets)
    }

    fun expand(value: String, placeholders: Map<String, PlaceholderValue>): ExpandedArguments {
        val secrets = linkedSetOf<String>()
        return ExpandedArguments(listOf(expand(value, placeholders, secrets)), secrets)
    }

    private fun expand(
        value: String,
        placeholders: Map<String, PlaceholderValue>,
        secrets: MutableSet<String>,
    ): String {
        val result = PLACEHOLDER.replace(value) { match ->
            val name = match.groupValues[1]
            val replacement = placeholders[name]
                ?: throw LaunchException("Missing required launch placeholder '$name' in argument '$value'")
            if (replacement.sensitive && replacement.value.isNotEmpty()) secrets += replacement.value
            replacement.value
        }
        if ("\${" in result) {
            throw LaunchException("Malformed or unsupported launch placeholder in argument '$value'")
        }
        return result
    }

    private companion object {
        val PLACEHOLDER = Regex("\\$\\{([A-Za-z0-9_]+)}")
    }
}
