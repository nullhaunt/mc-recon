package dev.mcrecon.manifest.rules

enum class OperatingSystem(val manifestName: String) {
    WINDOWS("windows"),
    LINUX("linux"),
    OSX("osx");

    companion object {
        fun fromManifest(value: String): OperatingSystem =
            entries.firstOrNull { it.manifestName == value }
                ?: throw IllegalArgumentException("unsupported operating system '$value'")

        fun fromSystemName(value: String): OperatingSystem {
            val normalized = value.lowercase()
            return when {
                normalized.startsWith("windows") -> WINDOWS
                normalized.startsWith("linux") || normalized.startsWith("unix") -> LINUX
                normalized.startsWith("mac") || normalized.startsWith("darwin") -> OSX
                else -> throw UnsupportedPlatformException("Unsupported operating system: $value")
            }
        }
    }
}

enum class Architecture(val canonicalName: String) {
    X86("x86"),
    X86_64("x86_64"),
    ARM32("arm32"),
    ARM64("arm64");

    companion object {
        fun fromSystemName(value: String): Architecture = when (value.lowercase()) {
            "x86", "i386", "i486", "i586", "i686" -> X86
            "amd64", "x86_64", "x64" -> X86_64
            "arm", "arm32" -> ARM32
            "aarch64", "arm64" -> ARM64
            else -> throw UnsupportedPlatformException("Unsupported architecture: $value")
        }

        fun fromManifest(value: String): Architecture = try {
            fromSystemName(value)
        } catch (_: UnsupportedPlatformException) {
            throw IllegalArgumentException("unsupported architecture condition '$value'")
        }
    }
}

data class HostPlatform(
    val operatingSystem: OperatingSystem,
    val architecture: Architecture,
    val version: String,
)

fun interface SystemPropertySource {
    fun get(name: String): String?
}

class HostPlatformDetector(
    private val properties: SystemPropertySource = SystemPropertySource(System::getProperty),
) {
    fun detect(): HostPlatform {
        val osName = properties.get("os.name") ?: throw UnsupportedPlatformException("Missing os.name system property")
        val osArch = properties.get("os.arch") ?: throw UnsupportedPlatformException("Missing os.arch system property")
        val osVersion = properties.get("os.version") ?: throw UnsupportedPlatformException("Missing os.version system property")
        return HostPlatform(
            operatingSystem = OperatingSystem.fromSystemName(osName),
            architecture = Architecture.fromSystemName(osArch),
            version = osVersion,
        )
    }
}

class UnsupportedPlatformException(message: String) : RuntimeException(message)
