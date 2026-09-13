package dev.mcrecon.resolver

import dev.mcrecon.manifest.Download
import dev.mcrecon.manifest.Library
import dev.mcrecon.manifest.rules.Architecture
import dev.mcrecon.manifest.rules.RuleContext
import dev.mcrecon.manifest.rules.RuleEvaluator

enum class LibraryArtifactRole {
    STANDARD,
    CLASSIFIER,
    NATIVE,
}

data class SelectedLibraryArtifact(
    val role: LibraryArtifactRole,
    val classifier: String?,
    val download: Download,
)

data class SelectedLibrary(
    val library: Library,
    val artifacts: List<SelectedLibraryArtifact>,
)

class LibrarySelector {
    fun select(libraries: List<Library>, context: RuleContext): List<SelectedLibrary> =
        libraries.asSequence()
            .filter { RuleEvaluator.isAllowed(it.rules, context) }
            .map { selectArtifacts(it, context) }
            .toList()

    private fun selectArtifacts(library: Library, context: RuleContext): SelectedLibrary {
        val downloads = library.downloads
            ?: throw ResolutionException("Applicable library '${library.name}' has no structured downloads metadata")
        val coordinateClassifier = classifierFromCoordinate(library.name)
        val selected = mutableListOf<SelectedLibraryArtifact>()
        downloads.artifact?.let { artifact ->
            selected += SelectedLibraryArtifact(
                role = if (coordinateClassifier == null) LibraryArtifactRole.STANDARD else LibraryArtifactRole.CLASSIFIER,
                classifier = coordinateClassifier,
                download = artifact,
            )
        }

        if (library.natives.isNotEmpty()) {
            val classifierTemplate = library.natives[context.platform.operatingSystem.manifestName]
                ?: throw ResolutionException(
                    "Applicable native library '${library.name}' has no classifier for ${context.platform.operatingSystem.manifestName}",
                )
            val classifier = classifierTemplate.replace("\${arch}", context.platform.architecture.classifierBits())
            val nativeDownload = downloads.classifiers[classifier]
                ?: throw ResolutionException("Native library '${library.name}' references missing classifier '$classifier'")
            selected += SelectedLibraryArtifact(LibraryArtifactRole.NATIVE, classifier, nativeDownload)
        }

        if (selected.isEmpty()) {
            throw ResolutionException("Applicable library '${library.name}' has no resolvable artifact")
        }
        return SelectedLibrary(library, selected)
    }

    private fun classifierFromCoordinate(coordinate: String): String? {
        val parts = coordinate.split(':')
        if (parts.size !in 3..4 || parts.any(String::isBlank)) {
            throw ResolutionException("Malformed library coordinate '$coordinate'")
        }
        return parts.getOrNull(3)
    }
}

private fun Architecture.classifierBits(): String = when (this) {
    Architecture.X86, Architecture.ARM32 -> "32"
    Architecture.X86_64, Architecture.ARM64 -> "64"
}
