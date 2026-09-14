package dev.mcrecon.compiler

import dev.mcrecon.classfile.Sha256
import dev.mcrecon.compiler.CompilerProfileModel.CompilerProfile
import dev.mcrecon.compiler.CompilerProfileModel.ToolchainIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider

enum class LanguageTargetMode { RELEASE, SOURCE_TARGET }

data class CompilerOptionProfile(
    val id: String,
    val targetMode: LanguageTargetMode,
    val debugOption: String? = null,
    val parameters: Boolean = false,
) {
    init {
        require(SAFE_ID.matches(id)) { "Unsafe compiler option profile ID '$id'" }
        require(debugOption == null || debugOption == "-g" || debugOption == "-g:none" || DEBUG_OPTION.matches(debugOption)) {
            "Unsupported javac debug option '$debugOption'"
        }
    }

    fun logicalOptions(majorVersion: Int): List<String> = buildList {
        when (targetMode) {
            LanguageTargetMode.RELEASE -> addAll(listOf("--release", majorVersion.toString()))
            LanguageTargetMode.SOURCE_TARGET -> addAll(listOf("-source", majorVersion.toString(), "-target", majorVersion.toString()))
        }
        addAll(listOf("-encoding", "UTF-8", "-proc:none"))
        debugOption?.let(::add)
        if (parameters) add("-parameters")
        addAll(
            listOf(
                "-Xmaxerrs", "100000",
                "-Xmaxwarns", "100000",
                "-classpath", "<resolved-manifest-artifacts>",
                "-d", "<class-output>",
            ),
        )
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9._-]+")
        val DEBUG_OPTION = Regex("-g:(?:source|lines|vars)(?:,(?:source|lines|vars))*")
    }
}

object CompilerOptionProfiles {
    fun all(majorVersion: Int): List<CompilerOptionProfile> = listOf(
        profile(majorVersion, "default"),
        profile(majorVersion, "parameters", parameters = true),
        profile(majorVersion, "g", debug = "-g"),
        profile(majorVersion, "g-parameters", debug = "-g", parameters = true),
        profile(majorVersion, "g-none", debug = "-g:none"),
        profile(majorVersion, "g-source", debug = "-g:source"),
        profile(majorVersion, "g-lines", debug = "-g:lines"),
        profile(majorVersion, "g-vars", debug = "-g:vars"),
        profile(majorVersion, "g-source-lines", debug = "-g:source,lines"),
        profile(majorVersion, "g-source-vars", debug = "-g:source,vars"),
        profile(majorVersion, "g-lines-vars", debug = "-g:lines,vars"),
        profile(majorVersion, "g-source-lines-vars", debug = "-g:source,lines,vars"),
        profile(majorVersion, "g-source-lines-vars-parameters", debug = "-g:source,lines,vars", parameters = true),
        profile(majorVersion, "source-target-default", targetMode = LanguageTargetMode.SOURCE_TARGET),
        profile(
            majorVersion,
            "source-target-g-parameters",
            targetMode = LanguageTargetMode.SOURCE_TARGET,
            debug = "-g",
            parameters = true,
        ),
    )

    fun find(majorVersion: Int, id: String): CompilerOptionProfile = all(majorVersion).singleOrNull { it.id == id }
        ?: throw CompilationException(
            "Unknown compiler option profile '$id'. Available profiles: ${all(majorVersion).joinToString { it.id }}",
        )

    private fun profile(
        majorVersion: Int,
        suffix: String,
        targetMode: LanguageTargetMode = LanguageTargetMode.RELEASE,
        debug: String? = null,
        parameters: Boolean = false,
    ) = CompilerOptionProfile(
        if (targetMode == LanguageTargetMode.RELEASE) {
            "release$majorVersion-$suffix"
        } else {
            "source-target$majorVersion-${suffix.removePrefix("source-target-")}"
        },
        targetMode,
        debug,
        parameters,
    )
}

fun interface CompilerToolchainIdentityProvider {
    fun identify(javaHome: Path): ToolchainIdentity
}

class CompilerToolchainIdentityProbe : CompilerToolchainIdentityProvider {
    override fun identify(javaHome: Path): ToolchainIdentity {
        val home = javaHome.toAbsolutePath().normalize()
        val java = executable(home, "java")
        val javac = executable(home, "javac")
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: throw CompilationException("The selected runtime does not provide the JDK compiler API")
        val compilerModule = home.resolve("jmods/jdk.compiler.jmod").takeIf(Files::isRegularFile)
            ?: home.resolve("lib/modules").takeIf(Files::isRegularFile)
            ?: throw CompilationException("Cannot locate the selected JDK compiler module image beneath $home")
        val release = home.resolve("release")
        val raw = ToolchainIdentity(
            if (compilerModule.fileName.toString() == "jdk.compiler.jmod") "jdk.compiler.jmod" else "runtime-modules-image",
            Sha256.file(compilerModule),
            "",
            "<selected-java-home>",
            Sha256.file(java),
            System.getProperty("java.runtime.version", ""),
            System.getProperty("java.vendor", ""),
            System.getProperty("java.version", ""),
            System.getProperty("java.vm.name", ""),
            compiler.javaClass.name,
            compiler.javaClass.module.descriptor.rawVersion().orElse(""),
            commandVersion(javac),
            if (Files.isRegularFile(release)) Sha256.file(release) else null,
        )
        return ToolchainIdentity(
            raw.compilerFileKind(), raw.compilerFileSha256(), identityHash(raw), raw.javaHome(), raw.javaLauncherSha256(),
            raw.javaRuntimeVersion(), raw.javaVendor(), raw.javaVersion(), raw.javaVmName(), raw.javacImplementation(),
            raw.javacModuleVersion(), raw.javacVersion(), raw.releaseFileSha256(),
        )
    }

    private fun executable(home: Path, name: String): Path {
        val extension = if (System.getProperty("os.name").startsWith("Windows", true)) ".exe" else ""
        val path = home.resolve("bin/$name$extension")
        if (!Files.isRegularFile(path)) throw CompilationException("Selected JDK is missing $path")
        return path
    }

    private fun commandVersion(javac: Path): String {
        val process = try {
            ProcessBuilder(javac.toString(), "-version").redirectErrorStream(true).start()
        } catch (exception: Exception) {
            throw CompilationException("Could not execute javac at $javac", exception)
        }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw CompilationException("Timed out while identifying javac at $javac")
        }
        val output = process.inputStream.bufferedReader().readText().trim().replace("\r\n", "\n").replace('\r', '\n')
        if (process.exitValue() != 0 || output.isBlank()) {
            throw CompilationException("javac identity probe failed with exit ${process.exitValue()}: $output")
        }
        return output.lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString(" | ")
    }

    private fun identityHash(identity: ToolchainIdentity): String = Sha256.text(
        listOf(
            identity.compilerFileKind(), identity.compilerFileSha256(), identity.javaHome(), identity.javaLauncherSha256(),
            identity.javaRuntimeVersion(), identity.javaVendor(), identity.javaVersion(), identity.javaVmName(),
            identity.javacImplementation(), identity.javacModuleVersion(), identity.javacVersion(), identity.releaseFileSha256().orEmpty(),
        ).joinToString("\u0000"),
    )
}

object CompilerProfiles {
    fun materialize(optionProfile: CompilerOptionProfile, toolchain: ToolchainIdentity, majorVersion: Int): CompilerProfile {
        val vendor = when {
            toolchain.javaRuntimeVersion().contains("Temurin", true) -> "temurin"
            else -> slug(toolchain.javaVendor())
        }
        val version = slug(toolchain.javaVersion())
        val id = "$vendor-$version-${toolchain.identitySha256().take(12)}-${optionProfile.id}"
        return CompilerProfile(id, optionProfile.id, optionProfile.logicalOptions(majorVersion), toolchain)
    }

    private fun slug(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "unknown" }
}
