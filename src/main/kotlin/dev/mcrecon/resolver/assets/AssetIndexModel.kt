package dev.mcrecon.resolver.assets

import com.fasterxml.jackson.databind.JsonNode

data class AssetIndexDocument(
    val objects: Map<String, AssetObject>,
    val virtual: Boolean?,
    val mapToResources: Boolean?,
    val raw: JsonNode,
)

data class AssetObject(
    val logicalName: String,
    val hash: String,
    val size: Long,
)
