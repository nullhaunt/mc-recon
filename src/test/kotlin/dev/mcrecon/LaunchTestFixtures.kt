package dev.mcrecon

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mcrecon.manifest.AssetIndex
import dev.mcrecon.manifest.Download
import dev.mcrecon.manifest.JavaVersion
import dev.mcrecon.manifest.Library
import dev.mcrecon.manifest.LibraryExtract
import dev.mcrecon.manifest.VersionManifest
import dev.mcrecon.manifest.VersionType
import dev.mcrecon.manifest.rules.Architecture
import dev.mcrecon.manifest.rules.HostPlatform
import dev.mcrecon.manifest.rules.OperatingSystem
import dev.mcrecon.resolver.ArtifactOrigin
import dev.mcrecon.resolver.ArtifactRequest
import dev.mcrecon.resolver.LibraryArtifactRole
import dev.mcrecon.resolver.ResolvedArtifact
import dev.mcrecon.resolver.ResolvedAssetIndex
import dev.mcrecon.resolver.ResolvedLibrary
import dev.mcrecon.resolver.ResolvedLibraryArtifact
import dev.mcrecon.resolver.ResolvedVersion
import dev.mcrecon.resolver.assets.AssetIndexDocument
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime

internal fun resolvedVersion(
    root: Path,
    libraries: List<TestLibrary> = emptyList(),
    arguments: Map<String, List<dev.mcrecon.manifest.ManifestArgument>> = mapOf("jvm" to emptyList(), "game" to emptyList()),
): ResolvedVersion {
    val client = artifact(root.resolve("client.jar"), "client".toByteArray(), "client")
    val server = artifact(root.resolve("server.jar"), "server".toByteArray(), "server")
    val indexBytes = "{\"objects\":{}}".toByteArray()
    val indexFile = artifact(root.resolve("assets/indexes/test.json"), indexBytes, "asset index")
    val manifestLibraries = libraries.map { it.metadata }
    val manifest = VersionManifest(
        id = "test-version",
        type = VersionType.SNAPSHOT,
        time = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        releaseTime = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        downloads = mapOf("client" to download(client), "server" to download(server)),
        assetIndex = AssetIndex("test", indexFile.request.expectedSha1, indexFile.request.expectedSize, 0, indexFile.request.uri),
        assets = "test",
        javaVersion = JavaVersion("java-runtime-test", 25),
        libraries = manifestLibraries,
        arguments = arguments,
        logging = emptyMap(),
        mainClass = "example.Main",
        minimumLauncherVersion = null,
        complianceLevel = null,
        inheritsFrom = null,
        jar = null,
        raw = ObjectMapper().createObjectNode(),
    )
    return ResolvedVersion(
        manifest,
        HostPlatform(OperatingSystem.WINDOWS, Architecture.X86_64, "10.0"),
        root,
        client,
        server,
        libraries.map { ResolvedLibrary(it.metadata, it.artifacts) },
        ResolvedAssetIndex(
            manifest.assetIndex,
            indexFile,
            AssetIndexDocument(emptyMap(), null, null, ObjectMapper().createObjectNode()),
            root.resolve("assets/runtime/test"),
            root.resolve("assets/objects"),
        ),
        emptyMap(),
        emptyMap(),
    )
}

internal data class TestLibrary(
    val metadata: Library,
    val artifacts: List<ResolvedLibraryArtifact>,
)

internal fun testLibrary(
    name: String,
    artifacts: List<Pair<LibraryArtifactRole, ResolvedArtifact>>,
    excludes: List<String> = emptyList(),
): TestLibrary = TestLibrary(
    Library(name, null, null, emptyMap(), LibraryExtract(excludes), null),
    artifacts.map { (role, file) -> ResolvedLibraryArtifact(role, if (role == LibraryArtifactRole.NATIVE) "natives-windows-64" else null, file) },
)

internal fun artifact(path: Path, bytes: ByteArray, name: String = path.fileName.toString()): ResolvedArtifact {
    Files.createDirectories(path.parent)
    Files.write(path, bytes)
    val request = ArtifactRequest(name, path.toUri(), sha1ForTest(bytes), bytes.size.toLong(), path)
    return ResolvedArtifact(request, path, ArtifactOrigin.CACHE)
}

private fun download(artifact: ResolvedArtifact) = Download(
    artifact.request.expectedSha1,
    artifact.request.expectedSize,
    artifact.request.uri,
)

internal fun sha1ForTest(bytes: ByteArray): String =
    java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(bytes))
