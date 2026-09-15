package dev.mcrecon.manifest

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ManifestParserIntegrationTest {
    private val manifest = ManifestParser().read(Path("fixtures", "26.3-rc-2.json"))
    private val rc3 = ManifestParser().read(Path("fixtures", "26.3-rc-3.json"))

    @Test
    fun `parses supplied manifest identity and release metadata`() {
        assertEquals("26.3-rc-2", manifest.id)
        assertEquals(VersionType.SNAPSHOT, manifest.type)
        assertEquals("2026-09-11T10:34:40Z", manifest.releaseTime.toString())
    }

    @Test
    fun `extracts required Java and client entry point`() {
        assertEquals(25, manifest.javaVersion.majorVersion)
        assertEquals("java-runtime-epsilon", manifest.javaVersion.component)
        assertEquals("net.minecraft.client.main.Main", manifest.mainClass)
    }

    @Test
    fun `extracts client and server downloads`() {
        assertEquals("5aec5ff7200d8f5778c0ad80209f4f7a84a460cb", manifest.clientDownload.sha1)
        assertEquals(41_483_614, manifest.clientDownload.size)
        assertEquals("26191a9be8f6bc3490dc368f94700a28508e04f1", manifest.serverDownload.sha1)
        assertEquals(62_294_751, manifest.serverDownload.size)
        assertTrue(manifest.clientDownload.url.toString().endsWith("/client.jar"))
        assertTrue(manifest.serverDownload.url.toString().endsWith("/server.jar"))
    }

    @Test
    fun `extracts asset index`() {
        assertEquals("34", manifest.assetIndex.id)
        assertEquals("79d5be4c55814468ef702e91ffaf71136a38c31c", manifest.assetIndex.sha1)
        assertEquals(597_035, manifest.assetIndex.size)
        assertEquals(483_689_563, manifest.assetIndex.totalSize)
    }

    @Test
    fun `retains structures needed by resolver and launcher`() {
        assertEquals(114, manifest.libraries.size)
        assertNotNull(manifest.arguments["jvm"])
        assertNotNull(manifest.arguments["game"])
        assertNotNull(manifest.arguments["default-user-jvm"])
        assertEquals("log4j2-xml", manifest.logging.getValue("client").type)
        assertNotNull(manifest.raw.get("complianceLevel"))

        val zGcArguments = manifest.arguments.getValue("default-user-jvm")[1] as ManifestArgument.Conditional
        assertEquals(listOf("-XX:+UseZGC"), zGcArguments.values)
        assertEquals(3, zGcArguments.rules?.size)
        assertNotNull(zGcArguments.rules?.last()?.os?.versionRange?.minInclusive)
    }

    @Test
    fun `RC3 preserves version and artifact identities independently of reused asset id`() {
        assertEquals("26.3-rc-3", rc3.id)
        assertEquals(VersionType.SNAPSHOT, rc3.type)
        assertEquals("2026-09-14T12:58:38Z", rc3.releaseTime.toString())
        assertEquals(25, rc3.javaVersion.majorVersion)
        assertEquals("net.minecraft.client.main.Main", rc3.mainClass)
        assertEquals("e8f3ef4e54f0eac2e724fb6397bf6d261b9a1af5", rc3.clientDownload.sha1)
        assertEquals("6daddb59a36dfad698169693ed5c2326611f4fb7", rc3.serverDownload.sha1)
        assertEquals("34", rc3.assetIndex.id)
        assertEquals("9e34cb790f8b813d46f147992af0d35d3bac0b9a", rc3.assetIndex.sha1)
        assertEquals(manifest.assetIndex.id, rc3.assetIndex.id)
        assertTrue(manifest.assetIndex.sha1 != rc3.assetIndex.sha1)
    }
}
