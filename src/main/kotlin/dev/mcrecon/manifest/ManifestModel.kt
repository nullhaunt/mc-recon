package dev.mcrecon.manifest

import com.fasterxml.jackson.databind.JsonNode
import dev.mcrecon.manifest.rules.Rule
import java.net.URI
import java.time.OffsetDateTime

data class VersionManifest(
    val id: String,
    val type: VersionType,
    val time: OffsetDateTime,
    val releaseTime: OffsetDateTime,
    val downloads: Map<String, Download>,
    val assetIndex: AssetIndex,
    val assets: String?,
    val javaVersion: JavaVersion,
    val libraries: List<Library>,
    val arguments: Map<String, List<ManifestArgument>>,
    val logging: Map<String, LoggingConfiguration>,
    val mainClass: String,
    val minimumLauncherVersion: Int?,
    val complianceLevel: Int?,
    val inheritsFrom: String?,
    val jar: String?,
    /** The complete input document, retained so unmodelled metadata is never lost. */
    val raw: JsonNode,
) {
    val clientDownload: Download
        get() = downloads.getValue("client")

    val serverDownload: Download
        get() = downloads.getValue("server")
}

enum class VersionType(val manifestName: String) {
    RELEASE("release"),
    SNAPSHOT("snapshot"),
    OLD_ALPHA("old_alpha"),
    OLD_BETA("old_beta");

    companion object {
        fun fromManifest(value: String): VersionType =
            entries.firstOrNull { it.manifestName == value }
                ?: throw IllegalArgumentException("unsupported version type '$value'")
    }
}

data class Download(
    val sha1: String,
    val size: Long,
    val url: URI,
    val path: String? = null,
    val id: String? = null,
)

data class AssetIndex(
    val id: String,
    val sha1: String,
    val size: Long,
    val totalSize: Long,
    val url: URI,
)

data class JavaVersion(
    val component: String,
    val majorVersion: Int,
)

data class Library(
    val name: String,
    val downloads: LibraryDownloads?,
    /** `null` means no rules (allowed); an empty list means rules were present but matched nothing. */
    val rules: List<Rule>?,
    val natives: Map<String, String>,
    val extract: LibraryExtract?,
    val url: URI?,
)

data class LibraryDownloads(
    val artifact: Download?,
    val classifiers: Map<String, Download>,
)

data class LibraryExtract(val exclude: List<String>)

sealed interface ManifestArgument {
    val values: List<String>

    data class Literal(val value: String) : ManifestArgument {
        override val values: List<String> = listOf(value)
    }

    data class Conditional(
        override val values: List<String>,
        val rules: List<Rule>?,
    ) : ManifestArgument
}

data class LoggingConfiguration(
    val argument: String,
    val file: Download,
    val type: String,
)
