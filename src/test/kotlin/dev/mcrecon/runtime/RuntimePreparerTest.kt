package dev.mcrecon.runtime

import dev.mcrecon.launcher.ClientLaunchPreparation
import dev.mcrecon.launcher.LaunchException
import dev.mcrecon.launcher.LaunchSide
import dev.mcrecon.launcher.LaunchSpecification
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.resolver.assets.AssetAvailability
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimePreparerTest {
    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `missing assets produce an actionable cache-only instruction`() {
        val specification = LaunchSpecification(
            LaunchSide.CLIENT,
            temporaryDirectory.resolve("java"),
            temporaryDirectory,
            emptyList(),
        )
        val preparation = ClientLaunchPreparation(
            specification,
            PreparedNatives(temporaryDirectory.resolve("natives"), true, 0),
            AssetAvailability(10, 3, listOf("missing")),
        )

        val failure = assertFailsWith<LaunchException> {
            RuntimePreparer(CacheLayout(temporaryDirectory)).requireClientAssets(preparation, Path.of("version.json"))
        }

        assertTrue(failure.message!!.contains("missing 7 asset objects"))
        assertTrue(failure.message!!.contains("resolve version.json --assets"))
    }
}
