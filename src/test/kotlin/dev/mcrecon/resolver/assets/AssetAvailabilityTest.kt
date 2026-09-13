package dev.mcrecon.resolver.assets

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.resolver.VerifiedArtifactStore
import dev.mcrecon.sha1ForTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssetAvailabilityTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `preflight counts unique content-addressed objects and verifies cached content`() {
        val bytes = "asset".toByteArray()
        val hash = sha1ForTest(bytes)
        val index = AssetIndexDocument(
            mapOf(
                "one" to AssetObject("one", hash, bytes.size.toLong()),
                "alias" to AssetObject("alias", hash, bytes.size.toLong()),
            ),
            null,
            null,
            ObjectMapper().createObjectNode(),
        )
        val layout = CacheLayout(temporaryDirectory)
        val resolver = AssetObjectResolver(layout, VerifiedArtifactStore(layout.root))

        val missing = resolver.availability(index)
        Files.createDirectories(layout.assetObject(hash).parent)
        Files.write(layout.assetObject(hash), bytes)
        val complete = resolver.availability(index)

        assertEquals(1, missing.expectedObjects)
        assertEquals(1, missing.missingObjects)
        assertFalse(missing.complete)
        assertEquals(1, complete.availableObjects)
        assertTrue(complete.complete)
    }
}
