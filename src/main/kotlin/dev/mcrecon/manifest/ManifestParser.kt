package dev.mcrecon.manifest

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.mcrecon.manifest.rules.OperatingSystem
import dev.mcrecon.manifest.rules.OsCondition
import dev.mcrecon.manifest.rules.Rule
import dev.mcrecon.manifest.rules.RuleAction
import dev.mcrecon.manifest.rules.VersionRange
import dev.mcrecon.manifest.rules.Architecture
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

class ManifestParser(private val objectMapper: ObjectMapper = ObjectMapper()) {
    fun read(path: Path): VersionManifest {
        if (!Files.exists(path)) throw ManifestException("Manifest does not exist: $path")
        if (!Files.isRegularFile(path)) throw ManifestException("Manifest is not a file: $path")

        val text = try {
            Files.readString(path)
        } catch (exception: IOException) {
            throw ManifestException("Could not read manifest '$path': ${exception.message}", exception)
        }
        return parse(text, path.toString())
    }

    fun parse(json: String, source: String = "<memory>"): VersionManifest {
        val root = try {
            objectMapper.readTree(json)
        } catch (exception: JsonProcessingException) {
            throw ManifestException(
                "Invalid JSON in '$source' at line ${exception.location.lineNr}, column ${exception.location.columnNr}: ${exception.originalMessage}",
                exception,
            )
        }

        try {
            val document = root.requireObject("manifest")
            val downloads = document.requiredObject("downloads").properties().associate { (name, node) ->
                name to parseDownload(node, "downloads.$name")
            }
            require(downloads.containsKey("client")) { "downloads.client is required" }
            require(downloads.containsKey("server")) { "downloads.server is required" }

            return VersionManifest(
                id = document.requiredText("id"),
                type = VersionType.fromManifest(document.requiredText("type")),
                time = document.requiredDateTime("time"),
                releaseTime = document.requiredDateTime("releaseTime"),
                downloads = downloads,
                assetIndex = parseAssetIndex(document.requiredObject("assetIndex")),
                assets = document.optionalText("assets"),
                javaVersion = parseJavaVersion(document.requiredObject("javaVersion")),
                libraries = document.requiredArray("libraries").mapIndexed { index, node ->
                    parseLibrary(node, "libraries[$index]")
                },
                arguments = parseArguments(document.requiredObject("arguments")),
                logging = parseLogging(document.optionalObject("logging")),
                mainClass = document.requiredText("mainClass"),
                minimumLauncherVersion = document.optionalInt("minimumLauncherVersion"),
                complianceLevel = document.optionalInt("complianceLevel"),
                inheritsFrom = document.optionalText("inheritsFrom"),
                jar = document.optionalText("jar"),
                raw = root.deepCopy(),
            )
        } catch (exception: ManifestException) {
            throw exception
        } catch (exception: IllegalArgumentException) {
            throw ManifestException("Invalid manifest '$source': ${exception.message}", exception)
        }
    }

    private fun parseDownload(node: JsonNode, path: String): Download {
        val value = node.requireObject(path)
        val sha1 = value.requiredText("sha1")
        require(SHA1.matches(sha1)) { "$path.sha1 must be a 40-character hexadecimal SHA-1" }
        val size = value.requiredLong("size")
        require(size >= 0) { "$path.size must not be negative" }
        return Download(
            sha1 = sha1.lowercase(),
            size = size,
            url = value.requiredUri("url"),
            path = value.optionalText("path"),
            id = value.optionalText("id"),
        )
    }

    private fun parseAssetIndex(value: ObjectNode): AssetIndex {
        val sha1 = value.requiredText("sha1")
        require(SHA1.matches(sha1)) { "assetIndex.sha1 must be a 40-character hexadecimal SHA-1" }
        val size = value.requiredLong("size")
        val totalSize = value.requiredLong("totalSize")
        require(size >= 0) { "assetIndex.size must not be negative" }
        require(totalSize >= 0) { "assetIndex.totalSize must not be negative" }
        return AssetIndex(
            id = value.requiredText("id"),
            sha1 = sha1.lowercase(),
            size = size,
            totalSize = totalSize,
            url = value.requiredUri("url"),
        )
    }

    private fun parseJavaVersion(value: ObjectNode): JavaVersion {
        val majorVersion = value.requiredInt("majorVersion")
        require(majorVersion > 0) { "javaVersion.majorVersion must be positive" }
        return JavaVersion(
            component = value.requiredText("component"),
            majorVersion = majorVersion,
        )
    }

    private fun parseLibrary(node: JsonNode, path: String): Library {
        val value = node.requireObject(path)
        val downloads = value.optionalObject("downloads")?.let { downloadNode ->
            LibraryDownloads(
                artifact = downloadNode.get("artifact")?.let { parseDownload(it, "$path.downloads.artifact") },
                classifiers = downloadNode.optionalObject("classifiers")?.properties()?.associate { (name, artifact) ->
                    name to parseDownload(artifact, "$path.downloads.classifiers.$name")
                }.orEmpty(),
            )
        }
        val rules = if (value.has("rules")) parseRules(value.get("rules"), "$path.rules") else null
        val natives = value.optionalObject("natives")?.properties()?.associate { (os, classifier) ->
            require(classifier.isTextual) { "$path.natives.$os must be a string" }
            os to classifier.textValue()
        }.orEmpty()
        val extract = value.optionalObject("extract")?.let { extractNode ->
            LibraryExtract(extractNode.optionalStringArray("exclude", "$path.extract.exclude"))
        }
        return Library(
            name = value.requiredText("name"),
            downloads = downloads,
            rules = rules,
            natives = natives,
            extract = extract,
            url = value.optionalUri("url"),
        )
    }

    private fun parseArguments(value: ObjectNode): Map<String, List<ManifestArgument>> =
        value.properties().associate { (groupName, groupNode) ->
            require(groupNode.isArray) { "arguments.$groupName must be an array" }
            groupName to groupNode.mapIndexed { index, argumentNode ->
                val path = "arguments.$groupName[$index]"
                when {
                    argumentNode.isTextual -> ManifestArgument.Literal(argumentNode.textValue())
                    argumentNode.isObject -> {
                        val argument = argumentNode as ObjectNode
                        val values = when (val argumentValue = argument.get("value")) {
                            null -> throw IllegalArgumentException("$path.value is required")
                            else -> argumentValue.asStringList("$path.value")
                        }
                        val rules = if (argument.has("rules")) parseRules(argument.get("rules"), "$path.rules") else null
                        ManifestArgument.Conditional(values, rules)
                    }
                    else -> throw IllegalArgumentException("$path must be a string or an object")
                }
            }
        }

    private fun parseLogging(value: ObjectNode?): Map<String, LoggingConfiguration> =
        value?.properties()?.associate { (name, node) ->
            val configuration = node.requireObject("logging.$name")
            name to LoggingConfiguration(
                argument = configuration.requiredText("argument"),
                file = parseDownload(configuration.get("file") ?: throw IllegalArgumentException("logging.$name.file is required"), "logging.$name.file"),
                type = configuration.requiredText("type"),
            )
        }.orEmpty()

    private fun parseRules(node: JsonNode, path: String): List<Rule> {
        require(node.isArray) { "$path must be an array" }
        return node.mapIndexed { index, ruleNode ->
            val rulePath = "$path[$index]"
            val value = ruleNode.requireObject(rulePath)
            val unknownKeys = value.fieldNames().asSequence().filterNot { it in RULE_KEYS }.toList()
            require(unknownKeys.isEmpty()) { "$rulePath contains unsupported fields: ${unknownKeys.joinToString()}" }

            val action = when (value.requiredText("action")) {
                "allow" -> RuleAction.ALLOW
                "disallow" -> RuleAction.DISALLOW
                else -> throw IllegalArgumentException("$rulePath.action must be 'allow' or 'disallow'")
            }
            val os = value.optionalObject("os")?.let { parseOsCondition(it, "$rulePath.os") }
            val features = value.optionalObject("features")?.properties()?.associate { (name, enabled) ->
                require(enabled.isBoolean) { "$rulePath.features.$name must be a boolean" }
                name to enabled.booleanValue()
            }.orEmpty()
            Rule(action, os, features)
        }
    }

    private fun parseOsCondition(value: ObjectNode, path: String): OsCondition {
        val unknownKeys = value.fieldNames().asSequence().filterNot { it in OS_KEYS }.toList()
        require(unknownKeys.isEmpty()) { "$path contains unsupported fields: ${unknownKeys.joinToString()}" }
        val range = value.optionalObject("versionRange")?.let { rangeNode ->
            val unknownRangeKeys = rangeNode.fieldNames().asSequence().filterNot { it in VERSION_RANGE_KEYS }.toList()
            require(unknownRangeKeys.isEmpty()) { "$path.versionRange contains unsupported fields: ${unknownRangeKeys.joinToString()}" }
            val min = rangeNode.optionalText("min")
            val max = rangeNode.optionalText("max")
            require(min != null || max != null) { "$path.versionRange must contain min or max" }
            VersionRange(min, max)
        }
        return OsCondition(
            name = value.optionalText("name")?.let(OperatingSystem::fromManifest),
            architecture = value.optionalText("arch")?.let(Architecture::fromManifest),
            versionPattern = value.optionalText("version")?.also {
                try {
                    Regex(it)
                } catch (exception: IllegalArgumentException) {
                    throw IllegalArgumentException("$path.version is not a valid regular expression", exception)
                }
            },
            versionRange = range,
        )
    }

    private companion object {
        val SHA1 = Regex("[0-9a-fA-F]{40}")
        val RULE_KEYS = setOf("action", "os", "features")
        val OS_KEYS = setOf("name", "arch", "version", "versionRange")
        val VERSION_RANGE_KEYS = setOf("min", "max")
    }
}

class ManifestException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private fun JsonNode.requireObject(path: String): ObjectNode {
    require(isObject) { "$path must be an object" }
    return this as ObjectNode
}

private fun ObjectNode.requiredObject(name: String): ObjectNode =
    (get(name) ?: throw IllegalArgumentException("$name is required")).requireObject(name)

private fun ObjectNode.optionalObject(name: String): ObjectNode? {
    val value = get(name) ?: return null
    require(value.isObject) { "$name must be an object" }
    return value as ObjectNode
}

private fun ObjectNode.requiredArray(name: String): List<JsonNode> {
    val value = get(name) ?: throw IllegalArgumentException("$name is required")
    require(value.isArray) { "$name must be an array" }
    return value.toList()
}

private fun ObjectNode.requiredText(name: String): String {
    val value = get(name) ?: throw IllegalArgumentException("$name is required")
    require(value.isTextual && value.textValue().isNotBlank()) { "$name must be a non-empty string" }
    return value.textValue()
}

private fun ObjectNode.optionalText(name: String): String? {
    val value = get(name) ?: return null
    require(value.isTextual) { "$name must be a string" }
    return value.textValue()
}

private fun ObjectNode.requiredLong(name: String): Long {
    val value = get(name) ?: throw IllegalArgumentException("$name is required")
    require(value.isIntegralNumber && value.canConvertToLong()) { "$name must be an integer" }
    return value.longValue()
}

private fun ObjectNode.requiredInt(name: String): Int {
    val value = get(name) ?: throw IllegalArgumentException("$name is required")
    require(value.isIntegralNumber && value.canConvertToInt()) { "$name must be an integer" }
    return value.intValue()
}

private fun ObjectNode.optionalInt(name: String): Int? {
    val value = get(name) ?: return null
    require(value.isIntegralNumber && value.canConvertToInt()) { "$name must be an integer" }
    return value.intValue()
}

private fun ObjectNode.requiredUri(name: String): URI {
    val value = requiredText(name)
    return parseUri(value, name)
}

private fun ObjectNode.optionalUri(name: String): URI? = optionalText(name)?.let { parseUri(it, name) }

private fun parseUri(value: String, path: String): URI = try {
    URI(value).also { require(it.isAbsolute) { "$path must be an absolute URI" } }
} catch (exception: IllegalArgumentException) {
    throw IllegalArgumentException("$path is not a valid absolute URI", exception)
}

private fun ObjectNode.requiredDateTime(name: String): OffsetDateTime {
    val value = requiredText(name)
    return try {
        OffsetDateTime.parse(value)
    } catch (exception: DateTimeParseException) {
        throw IllegalArgumentException("$name must be an ISO-8601 offset date-time", exception)
    }
}

private fun ObjectNode.optionalStringArray(name: String, path: String): List<String> {
    val value = get(name) ?: return emptyList()
    require(value.isArray) { "$path must be an array" }
    return value.mapIndexed { index, item ->
        require(item.isTextual) { "$path[$index] must be a string" }
        item.textValue()
    }
}

private fun JsonNode.asStringList(path: String): List<String> = when {
    isTextual -> listOf(textValue())
    isArray -> mapIndexed { index, item ->
        require(item.isTextual) { "$path[$index] must be a string" }
        item.textValue()
    }
    else -> throw IllegalArgumentException("$path must be a string or an array of strings")
}
