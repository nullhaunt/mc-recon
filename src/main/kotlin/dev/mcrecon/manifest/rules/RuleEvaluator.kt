package dev.mcrecon.manifest.rules

enum class RuleAction { ALLOW, DISALLOW }

data class Rule(
    val action: RuleAction,
    val os: OsCondition? = null,
    val features: Map<String, Boolean> = emptyMap(),
)

data class OsCondition(
    val name: OperatingSystem? = null,
    val architecture: Architecture? = null,
    val versionPattern: String? = null,
    val versionRange: VersionRange? = null,
)

data class VersionRange(
    val minInclusive: String? = null,
    val maxExclusive: String? = null,
)

data class RuleContext(
    val platform: HostPlatform,
    val features: Map<String, Boolean> = emptyMap(),
)

object RuleEvaluator {
    /**
     * Mojang rules are ordered. With rules present the default is denied; every matching
     * rule replaces the prior result, so the final matching rule wins.
     */
    fun isAllowed(rules: List<Rule>?, context: RuleContext): Boolean {
        if (rules == null) return true

        var allowed = false
        for (rule in rules) {
            if (matches(rule, context)) {
                allowed = rule.action == RuleAction.ALLOW
            }
        }
        return allowed
    }

    private fun matches(rule: Rule, context: RuleContext): Boolean =
        matchesOs(rule.os, context.platform) &&
            rule.features.all { (name, expected) -> (context.features[name] ?: false) == expected }

    private fun matchesOs(condition: OsCondition?, platform: HostPlatform): Boolean {
        if (condition == null) return true
        if (condition.name != null && condition.name != platform.operatingSystem) return false
        if (condition.architecture != null && condition.architecture != platform.architecture) return false
        if (condition.versionPattern != null && !Regex(condition.versionPattern).matches(platform.version)) return false
        if (condition.versionRange != null && !condition.versionRange.contains(platform.version)) return false
        return true
    }
}

private fun VersionRange.contains(version: String): Boolean {
    if (minInclusive != null && compareVersions(version, minInclusive) < 0) return false
    if (maxExclusive != null && compareVersions(version, maxExclusive) >= 0) return false
    return true
}

private fun compareVersions(left: String, right: String): Int {
    val leftParts = left.split('.', '-', '_')
    val rightParts = right.split('.', '-', '_')
    val length = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until length) {
        val leftPart = leftParts.getOrElse(index) { "0" }
        val rightPart = rightParts.getOrElse(index) { "0" }
        val comparison = compareVersionPart(leftPart, rightPart)
        if (comparison != 0) return comparison
    }
    return 0
}

private fun compareVersionPart(left: String, right: String): Int {
    val leftNumber = left.toBigIntegerOrNull()
    val rightNumber = right.toBigIntegerOrNull()
    return if (leftNumber != null && rightNumber != null) {
        leftNumber.compareTo(rightNumber)
    } else {
        left.compareTo(right, ignoreCase = true)
    }
}
