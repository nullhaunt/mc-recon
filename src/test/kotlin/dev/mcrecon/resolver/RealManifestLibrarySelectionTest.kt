package dev.mcrecon.resolver

import dev.mcrecon.manifest.ManifestParser
import dev.mcrecon.manifest.rules.Architecture
import dev.mcrecon.manifest.rules.HostPlatform
import dev.mcrecon.manifest.rules.OperatingSystem
import dev.mcrecon.manifest.rules.RuleContext
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RealManifestLibrarySelectionTest {
    @TempDir
    lateinit var temporaryDirectory: java.nio.file.Path

    @Test
    fun `real manifest selects safe Windows artifacts without network access`() {
        val manifest = ManifestParser().read(Path("fixtures", "26.3-rc-2.json"))
        val context = RuleContext(HostPlatform(OperatingSystem.WINDOWS, Architecture.X86_64, "10.0.26100"))
        val selected = LibrarySelector().select(manifest.libraries, context)
        val layout = CacheLayout(temporaryDirectory.resolve("cache"))

        assertEquals(74, selected.size)
        assertEquals(21, selected.flatMap(SelectedLibrary::artifacts).count { it.role == LibraryArtifactRole.CLASSIFIER })
        selected.flatMap(SelectedLibrary::artifacts).forEach { artifact ->
            layout.libraryArtifact(assertNotNull(artifact.download.path))
        }
    }
}
