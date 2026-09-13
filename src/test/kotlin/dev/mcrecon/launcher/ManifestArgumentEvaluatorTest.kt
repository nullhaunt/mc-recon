package dev.mcrecon.launcher

import dev.mcrecon.manifest.ManifestArgument
import dev.mcrecon.manifest.rules.Architecture
import dev.mcrecon.manifest.rules.HostPlatform
import dev.mcrecon.manifest.rules.OperatingSystem
import dev.mcrecon.manifest.rules.OsCondition
import dev.mcrecon.manifest.rules.Rule
import dev.mcrecon.manifest.rules.RuleAction
import dev.mcrecon.manifest.rules.RuleContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ManifestArgumentEvaluatorTest {
    private val windows = HostPlatform(OperatingSystem.WINDOWS, Architecture.X86_64, "10.0")
    private val evaluator = ManifestArgumentEvaluator()

    @Test
    fun `plain strings and conditional string arrays preserve order`() {
        val arguments = listOf(
            ManifestArgument.Literal("plain"),
            ManifestArgument.Conditional(
                listOf("--width", "\${resolution_width}"),
                listOf(Rule(RuleAction.ALLOW, features = mapOf("has_custom_resolution" to true))),
            ),
            ManifestArgument.Conditional(
                listOf("osx-only"),
                listOf(Rule(RuleAction.ALLOW, OsCondition(name = OperatingSystem.OSX))),
            ),
        )

        val result = evaluator.evaluate(
            arguments,
            RuleContext(windows, mapOf("has_custom_resolution" to true)),
            mapOf("resolution_width" to PlaceholderValue("1280")),
        )

        assertEquals(listOf("plain", "--width", "1280"), result.values)
    }

    @Test
    fun `placeholder in disabled conditional argument is not required`() {
        val argument = ManifestArgument.Conditional(
            listOf("\${unused}"),
            listOf(Rule(RuleAction.ALLOW, features = mapOf("enabled" to true))),
        )
        assertEquals(emptyList(), evaluator.evaluate(listOf(argument), RuleContext(windows), emptyMap()).values)
    }

    @Test
    fun `missing required placeholder fails clearly`() {
        val failure = assertFailsWith<LaunchException> {
            evaluator.evaluate(listOf(ManifestArgument.Literal("\${missing}")), RuleContext(windows), emptyMap())
        }
        assertEquals("Missing required launch placeholder 'missing' in argument '\${missing}'", failure.message)
    }

    @Test
    fun `sensitive replacement remains real in specification data but is marked`() {
        val result = evaluator.expand("token=\${token}", mapOf("token" to PlaceholderValue("secret", sensitive = true)))
        assertEquals(listOf("token=secret"), result.values)
        assertEquals(setOf("secret"), result.sensitiveValues)
    }
}
