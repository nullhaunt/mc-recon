package dev.mcrecon.manifest.rules

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleEvaluatorTest {
    private val windows = HostPlatform(OperatingSystem.WINDOWS, Architecture.X86_64, "10.0.26100")
    private val linux = HostPlatform(OperatingSystem.LINUX, Architecture.X86_64, "6.12.1")

    @Test
    fun `a library without rules is allowed`() {
        assertTrue(RuleEvaluator.isAllowed(null, RuleContext(windows)))
    }

    @Test
    fun `rules being present changes the default to denied`() {
        val rules = listOf(Rule(RuleAction.ALLOW, OsCondition(name = OperatingSystem.WINDOWS)))
        assertFalse(RuleEvaluator.isAllowed(rules, RuleContext(linux)))
        assertTrue(RuleEvaluator.isAllowed(rules, RuleContext(windows)))
    }

    @Test
    fun `the last matching rule wins`() {
        val rules = listOf(
            Rule(RuleAction.ALLOW),
            Rule(RuleAction.DISALLOW, OsCondition(name = OperatingSystem.WINDOWS)),
        )
        assertFalse(RuleEvaluator.isAllowed(rules, RuleContext(windows)))
        assertTrue(RuleEvaluator.isAllowed(rules, RuleContext(linux)))
    }

    @Test
    fun `a later allow can override a deny`() {
        val rules = listOf(
            Rule(RuleAction.DISALLOW),
            Rule(RuleAction.ALLOW, OsCondition(name = OperatingSystem.LINUX)),
        )
        assertTrue(RuleEvaluator.isAllowed(rules, RuleContext(linux)))
        assertFalse(RuleEvaluator.isAllowed(rules, RuleContext(windows)))
    }

    @Test
    fun `OS and architecture conditions must both match`() {
        val rules = listOf(
            Rule(RuleAction.ALLOW, OsCondition(name = OperatingSystem.WINDOWS, architecture = Architecture.ARM64)),
        )
        val armWindows = windows.copy(architecture = Architecture.ARM64)
        assertTrue(RuleEvaluator.isAllowed(rules, RuleContext(armWindows)))
        assertFalse(RuleEvaluator.isAllowed(rules, RuleContext(windows)))
        assertFalse(RuleEvaluator.isAllowed(rules, RuleContext(linux.copy(architecture = Architecture.ARM64))))
    }

    @Test
    fun `feature conditions use false for absent feature flags`() {
        val rule = Rule(RuleAction.ALLOW, features = mapOf("is_demo_user" to true))
        assertFalse(RuleEvaluator.isAllowed(listOf(rule), RuleContext(windows)))
        assertTrue(RuleEvaluator.isAllowed(listOf(rule), RuleContext(windows, mapOf("is_demo_user" to true))))

        val nonDemoRule = Rule(RuleAction.ALLOW, features = mapOf("is_demo_user" to false))
        assertTrue(RuleEvaluator.isAllowed(listOf(nonDemoRule), RuleContext(windows)))
    }

    @Test
    fun `version ranges use an inclusive minimum and exclusive maximum`() {
        val minimum = Rule(RuleAction.ALLOW, OsCondition(versionRange = VersionRange(minInclusive = "10.0.17134")))
        val maximum = Rule(RuleAction.ALLOW, OsCondition(versionRange = VersionRange(maxExclusive = "10.0.17134")))
        assertTrue(RuleEvaluator.isAllowed(listOf(minimum), RuleContext(windows.copy(version = "10.0.17134"))))
        assertFalse(RuleEvaluator.isAllowed(listOf(maximum), RuleContext(windows.copy(version = "10.0.17134"))))
        assertTrue(RuleEvaluator.isAllowed(listOf(maximum), RuleContext(windows.copy(version = "10.0.15000"))))
    }
}
