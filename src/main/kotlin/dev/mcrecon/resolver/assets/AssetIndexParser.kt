package dev.mcrecon.resolver.assets

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.mcrecon.resolver.ResolutionException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class AssetIndexParser(private val objectMapper: ObjectMapper = ObjectMapper()) {
    fun read(path: Path): AssetIndexDocument {
        val json = try {
            Files.readString(path)
        } catch (exception: IOException) {
            throw ResolutionException("Could not read asset index '$path': ${exception.message}", exception)
        }
        return parse(json, path.toString())
    }

    fun parse(json: String, source: String = "<memory>"): AssetIndexDocument {
        val root = try {
            objectMapper.readTree(json)
        } catch (exception: JsonProcessingException) {
            throw ResolutionException("Invalid asset-index JSON in '$source': ${exception.originalMessage}", exception)
        }
        try {
            require(root.isObject) { "asset index must be an object" }
            val document = root as ObjectNode
            val objectsNode = document.get("objects")
            require(objectsNode != null && objectsNode.isObject) { "asset index objects must be an object" }
            val sizesByHash = mutableMapOf<String, Long>()
            val objects = (objectsNode as ObjectNode).properties().associate { (logicalName, node) ->
                require(logicalName.isNotBlank()) { "asset object logical name must not be blank" }
                require(node.isObject) { "asset object '$logicalName' must be an object" }
                val value = node as ObjectNode
                val hashNode = value.get("hash")
                require(hashNode != null && hashNode.isTextual && SHA1.matches(hashNode.textValue())) {
                    "asset object '$logicalName' has an invalid SHA-1"
                }
                val sizeNode = value.get("size")
                require(sizeNode != null && sizeNode.isIntegralNumber && sizeNode.canConvertToLong()) {
                    "asset object '$logicalName' has an invalid size"
                }
                val size = sizeNode.longValue()
                require(size >= 0) { "asset object '$logicalName' has a negative size" }
                val hash = hashNode.textValue().lowercase()
                val previousSize = sizesByHash.putIfAbsent(hash, size)
                require(previousSize == null || previousSize == size) {
                    "asset object '$logicalName' gives content hash $hash conflicting sizes $previousSize and $size"
                }
                logicalName to AssetObject(logicalName, hash, size)
            }
            return AssetIndexDocument(
                objects = objects,
                virtual = document.optionalBoolean("virtual"),
                mapToResources = document.optionalBoolean("map_to_resources"),
                raw = root.deepCopy(),
            )
        } catch (exception: IllegalArgumentException) {
            throw ResolutionException("Invalid asset index '$source': ${exception.message}", exception)
        }
    }

    private companion object {
        val SHA1 = Regex("[0-9a-fA-F]{40}")
    }
}

private fun ObjectNode.optionalBoolean(name: String): Boolean? {
    val value = get(name) ?: return null
    require(value.isBoolean) { "$name must be a boolean" }
    return value.booleanValue()
}
