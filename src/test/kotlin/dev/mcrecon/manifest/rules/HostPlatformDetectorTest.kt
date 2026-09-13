package dev.mcrecon.manifest.rules

import kotlin.test.Test
import kotlin.test.assertEquals

class HostPlatformDetectorTest {
    @Test
    fun `normalizes Windows amd64 properties`() {
        val values = mapOf("os.name" to "Windows 11", "os.arch" to "amd64", "os.version" to "10.0.26100")
        val platform = HostPlatformDetector(SystemPropertySource(values::get)).detect()
        assertEquals(OperatingSystem.WINDOWS, platform.operatingSystem)
        assertEquals(Architecture.X86_64, platform.architecture)
        assertEquals("10.0.26100", platform.version)
    }

    @Test
    fun `normalizes macOS arm64 properties`() {
        val values = mapOf("os.name" to "Mac OS X", "os.arch" to "aarch64", "os.version" to "15.6")
        val platform = HostPlatformDetector(SystemPropertySource(values::get)).detect()
        assertEquals(OperatingSystem.OSX, platform.operatingSystem)
        assertEquals(Architecture.ARM64, platform.architecture)
    }
}
