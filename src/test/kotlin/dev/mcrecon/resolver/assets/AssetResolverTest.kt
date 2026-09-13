package dev.mcrecon.resolver.assets

import dev.mcrecon.resolver.ArtifactOrigin
import dev.mcrecon.resolver.ArtifactRequest
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.resolver.ResolutionException
import dev.mcrecon.resolver.TestHttpServer
import dev.mcrecon.resolver.VerifiedArtifactStore
import dev.mcrecon.resolver.sha1
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AssetResolverTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `downloads and parses an asset index then resolves content-addressed objects`() {
        val objectBytes = "asset contents".toByteArray()
        val objectHash = sha1(objectBytes)
        val indexBytes = """
            {"objects":{
              "minecraft/example.txt":{"hash":"$objectHash","size":${objectBytes.size}},
              "minecraft/alias.txt":{"hash":"$objectHash","size":${objectBytes.size}}
            }}
        """.trimIndent().toByteArray()

        TestHttpServer().use { server ->
            server.serve("index.json", indexBytes)
            server.serve("${objectHash.take(2)}/$objectHash", objectBytes)
            val layout = CacheLayout(temporaryDirectory.resolve("cache"))
            val store = VerifiedArtifactStore(layout.root)
            val indexArtifact = store.resolve(
                ArtifactRequest(
                    "test asset index",
                    server.baseUri.resolve("index.json"),
                    sha1(indexBytes),
                    indexBytes.size.toLong(),
                    layout.assetIndex("test"),
                ),
            )
            val index = AssetIndexParser().read(indexArtifact.path)

            val assets = AssetObjectResolver(layout, store, server.baseUri).resolveAll(index)

            assertEquals(2, assets.size)
            assertSame(assets.getValue("minecraft/example.txt"), assets.getValue("minecraft/alias.txt"))
            val resolved = assets.getValue("minecraft/example.txt")
            assertEquals(ArtifactOrigin.DOWNLOAD, resolved.origin)
            assertEquals(layout.root.resolve("assets/objects/${objectHash.take(2)}/$objectHash"), resolved.path)
            assertTrue(Files.exists(resolved.path))
            assertEquals(1, server.hits("${objectHash.take(2)}/$objectHash"))
        }
    }

    @Test
    fun `invalid asset index JSON is actionable`() {
        val failure = assertFailsWith<ResolutionException> {
            AssetIndexParser().parse("{\"objects\":{\"bad\":{\"hash\":\"nope\",\"size\":1}}}", "bad-index.json")
        }
        assertTrue(failure.message.orEmpty().contains("bad-index.json"))
        assertTrue(failure.message.orEmpty().contains("invalid SHA-1"))
    }
}
