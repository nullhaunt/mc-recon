package dev.mcrecon.cli

import dev.mcrecon.classfile.BinaryDiffEngine
import dev.mcrecon.classfile.BinaryDiffException
import dev.mcrecon.classfile.BinaryDiffDetailsRenderer
import dev.mcrecon.classfile.BinaryDiffDetailsService
import dev.mcrecon.classfile.BinaryDiffRenderer
import dev.mcrecon.classfile.BinaryIndexService
import dev.mcrecon.classfile.BinarySide
import dev.mcrecon.classfile.JarIndexStore
import dev.mcrecon.classfile.JarIndexingException
import dev.mcrecon.classfile.IndexModel.EntryKind
import dev.mcrecon.classfile.ClassfileMethodInspector
import dev.mcrecon.classfile.StoredJarIndex
import dev.mcrecon.classfile.SymbolicMethodRenderer
import dev.mcrecon.compiler.CompilationException
import dev.mcrecon.compiler.CompilationResult
import dev.mcrecon.compiler.CompilationService
import dev.mcrecon.decompiler.DecompilationException
import dev.mcrecon.decompiler.DecompilationResult
import dev.mcrecon.decompiler.DecompilationService
import dev.mcrecon.decompiler.DecompilationSide
import dev.mcrecon.manifest.Download
import dev.mcrecon.manifest.ManifestException
import dev.mcrecon.manifest.ManifestParser
import dev.mcrecon.manifest.VersionManifest
import dev.mcrecon.manifest.rules.HostPlatformDetector
import dev.mcrecon.manifest.rules.RuleContext
import dev.mcrecon.manifest.rules.RuleEvaluator
import dev.mcrecon.manifest.rules.UnsupportedPlatformException
import dev.mcrecon.launcher.ClientLaunchBuilder
import dev.mcrecon.launcher.ClientLaunchOptions
import dev.mcrecon.launcher.ClientSession
import dev.mcrecon.launcher.LaunchCommandRenderer
import dev.mcrecon.launcher.LaunchException
import dev.mcrecon.launcher.LaunchSide
import dev.mcrecon.launcher.ProcessLauncher
import dev.mcrecon.launcher.ServerLaunchOptions
import dev.mcrecon.resolver.ArtifactOrigin
import dev.mcrecon.resolver.CacheLayout
import dev.mcrecon.resolver.LibraryArtifactRole
import dev.mcrecon.resolver.ResolutionException
import dev.mcrecon.resolver.ResolvedArtifact
import dev.mcrecon.resolver.ResolvedVersion
import dev.mcrecon.resolver.VersionResolver
import dev.mcrecon.reconstruction.ReconstructionException
import dev.mcrecon.reconstruction.ReconstructionResult
import dev.mcrecon.reconstruction.ReconstructionService
import dev.mcrecon.runtime.RuntimePreparationException
import dev.mcrecon.runtime.RuntimePreparer
import dev.mcrecon.runtime.RuntimeSelectionOptions
import dev.mcrecon.verification.VerificationDetailsService
import dev.mcrecon.verification.ClassfileRepresentationDetailsRenderer
import dev.mcrecon.verification.VerificationComparisonEngine
import dev.mcrecon.verification.VerificationComparisonRenderer
import dev.mcrecon.verification.VerificationException
import dev.mcrecon.verification.VerificationReportStore
import dev.mcrecon.verification.VerificationRenderer
import dev.mcrecon.verification.VerificationService
import java.io.PrintWriter
import java.nio.file.InvalidPathException
import java.nio.file.Path

class McReconCli(
    private val parser: ManifestParser = ManifestParser(),
    private val platformDetector: HostPlatformDetector = HostPlatformDetector(),
    private val processLauncher: ProcessLauncher = ProcessLauncher(),
) {
    fun run(arguments: Array<String>, out: PrintWriter, err: PrintWriter): Int {
        if (arguments.contentEquals(arrayOf("--help")) || arguments.contentEquals(arrayOf("-h"))) {
            printUsage(out)
            return 0
        }

        return try {
            when (arguments.firstOrNull()) {
                "inspect" -> runInspect(arguments, out)
                "resolve" -> runResolve(arguments, out)
                "index" -> runIndex(arguments, out)
                "diff-bin" -> runBinaryDiff(arguments, out)
                "inspect-method" -> runInspectMethod(arguments, out)
                "decompile" -> runDecompile(arguments, out)
                "reconstruct" -> runReconstruct(arguments, out)
                "compile" -> runCompile(arguments, out)
                "verify" -> runVerify(arguments, out)
                "verify-compare" -> runVerificationComparison(arguments, out)
                "launch-command" -> runLaunch(arguments, out, execute = false)
                "run" -> runLaunch(arguments, out, execute = true)
                null -> throw CliUsageException("a command is required")
                else -> throw CliUsageException("unknown command '${arguments[0]}'")
            }
        } catch (exception: CliUsageException) {
            err.println("error: ${exception.message}")
            printUsage(err)
            2
        } catch (exception: InvalidPathException) {
            fail(err, 2, "error: invalid manifest path: ${exception.input}")
        } catch (exception: ManifestException) {
            fail(err, 2, "error: ${exception.message}")
        } catch (exception: ResolutionException) {
            fail(err, 2, "error: ${exception.message}")
        } catch (exception: JarIndexingException) {
            fail(err, 2, "error: ${exception.message}")
        } catch (exception: BinaryDiffException) {
            fail(err, 2, "error: ${exception.message}")
        } catch (exception: DecompilationException) {
            fail(err, 2, "error: ${exception.message}")
        } catch (exception: ReconstructionException) {
            fail(err, 2, "error: ${exception.message}")
        } catch (exception: CompilationException) {
            fail(err, 2, "error: ${exception.message}")
        } catch (exception: VerificationException) {
            fail(err, 2, "error: ${exception.message}")
        } catch (exception: LaunchException) {
            fail(err, 2, "error: ${exception.message}")
        } catch (exception: RuntimePreparationException) {
            fail(err, 2, "error: ${exception.message}")
        } catch (exception: UnsupportedPlatformException) {
            fail(err, 2, "error: ${exception.message}")
        } catch (exception: Exception) {
            fail(err, 1, "internal error: ${exception.message ?: exception::class.simpleName}")
        }
    }

    private fun runVerify(arguments: Array<String>, out: PrintWriter): Int {
        if (arguments.size < 2) throw CliUsageException("verify requires a manifest path")
        val options = parseVerificationOptions(arguments)
        val manifest = parser.read(Path.of(arguments[1]))
        val result = VerificationService(CacheLayout(options.cacheDirectory)).verify(manifest, options.side)
        val selected = options.className?.let { className ->
            result.report.classes().singleOrNull { it.binaryName() == className }
                ?: throw VerificationException("Verification report has no class '$className'")
        }
        if (options.format == "json") {
            val value = selected ?: result.report
            JarIndexStore.deterministicObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out, value)
            out.println()
            out.flush()
        } else if (selected == null) {
            VerificationRenderer.render(result.report, result.reportPath, out)
        } else {
            VerificationRenderer.renderClass(result.report, selected, result.reportPath, out)
            if (options.details) {
                val detailsService = VerificationDetailsService()
                val details = detailsService.inspect(result.referenceJar, result.candidateClasses, selected)
                out.println()
                BinaryDiffDetailsRenderer.render(details, "reference", "rebuilt", out)
                ClassfileRepresentationDetailsRenderer.render(
                    detailsService.inspectRepresentation(result.referenceJar, result.candidateClasses, selected),
                    selected.exact(),
                    out,
                )
            }
        }
        return 0
    }

    private fun parseVerificationOptions(arguments: Array<String>): VerificationOptions {
        var options = VerificationOptions()
        var sideSpecified = false
        val seen = mutableSetOf<String>()
        var index = 2
        while (index < arguments.size) {
            val option = arguments[index]
            if (!seen.add(option)) throw CliUsageException("$option was specified more than once")
            fun value(): String {
                if (index + 1 >= arguments.size) throw CliUsageException("$option requires a value")
                return arguments[index + 1]
            }
            when (option) {
                "--side" -> {
                    options = options.copy(side = when (value()) {
                        "client" -> BinarySide.CLIENT
                        "server" -> BinarySide.SERVER
                        else -> throw CliUsageException("--side must be 'client' or 'server'")
                    })
                    sideSpecified = true
                    index += 2
                }
                "--cache-dir" -> {
                    options = options.copy(cacheDirectory = Path.of(value()))
                    index += 2
                }
                "--format" -> {
                    val format = value()
                    if (format != "text" && format != "json") throw CliUsageException("--format must be 'text' or 'json'")
                    options = options.copy(format = format)
                    index += 2
                }
                "--class" -> {
                    options = options.copy(className = value())
                    index += 2
                }
                "--details" -> {
                    options = options.copy(details = true)
                    index++
                }
                else -> throw CliUsageException("unknown verify option '$option'")
            }
        }
        if (!sideSpecified) throw CliUsageException("verify requires --side <client|server>")
        if (options.details && options.className == null) throw CliUsageException("verify --details requires --class <binary-name>")
        if (options.details && options.format == "json") throw CliUsageException("verify --details is available only with text output")
        return options
    }

    private fun runVerificationComparison(arguments: Array<String>, out: PrintWriter): Int {
        if (arguments.size < 3) throw CliUsageException("verify-compare requires two verification report paths")
        var format = "text"
        if (arguments.size > 3) {
            if (arguments.size != 5 || arguments[3] != "--format") {
                throw CliUsageException("verify-compare accepts only --format <text|json>")
            }
            format = arguments[4]
            if (format != "text" && format != "json") throw CliUsageException("--format must be 'text' or 'json'")
        }
        val store = VerificationReportStore()
        val comparison = VerificationComparisonEngine.compare(
            store.read(Path.of(arguments[1])),
            store.read(Path.of(arguments[2])),
        )
        if (format == "json") {
            JarIndexStore.deterministicObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out, comparison)
            out.println()
            out.flush()
        } else {
            VerificationComparisonRenderer.render(comparison, out)
        }
        return 0
    }

    private fun runReconstruct(arguments: Array<String>, out: PrintWriter): Int {
        if (arguments.size < 2) throw CliUsageException("reconstruct requires a manifest path")
        val options = parseSourceBuildOptions(arguments, allowRaw = false, allowDiagnostics = false)
        val manifest = parser.read(Path.of(arguments[1]))
        val result = ReconstructionService(CacheLayout(options.cacheDirectory)).reconstruct(manifest, options.side, options.force)
        printReconstruction(result, out)
        return 0
    }

    private fun runCompile(arguments: Array<String>, out: PrintWriter): Int {
        if (arguments.size < 2) throw CliUsageException("compile requires a manifest path")
        val options = parseSourceBuildOptions(arguments, allowRaw = true, allowDiagnostics = true)
        val manifest = parser.read(Path.of(arguments[1]))
        val layout = CacheLayout(options.cacheDirectory)
        if (!options.raw) {
            ReconstructionService(layout).reconstruct(manifest, options.side, force = false)
        }
        val result = CompilationService(layout).compile(manifest, options.side, options.force, options.javaExecutable, options.raw)
        printCompilation(result, options.diagnostics, out)
        return if (result.report.output().errors() == 0) 0 else 1
    }

    private fun parseSourceBuildOptions(arguments: Array<String>, allowRaw: Boolean, allowDiagnostics: Boolean): SourceBuildOptions {
        var options = SourceBuildOptions()
        var sideSpecified = false
        val seen = mutableSetOf<String>()
        var index = 2
        while (index < arguments.size) {
            val option = arguments[index]
            if (!seen.add(option)) throw CliUsageException("$option was specified more than once")
            fun value(): String {
                if (index + 1 >= arguments.size) throw CliUsageException("$option requires a value")
                return arguments[index + 1]
            }
            when (option) {
                "--side" -> {
                    options = options.copy(side = when (value()) {
                        "client" -> DecompilationSide.CLIENT
                        "server" -> DecompilationSide.SERVER
                        else -> throw CliUsageException("--side must be 'client' or 'server'")
                    })
                    sideSpecified = true
                    index += 2
                }
                "--cache-dir" -> {
                    options = options.copy(cacheDirectory = Path.of(value()))
                    index += 2
                }
                "--java" -> {
                    if (!allowDiagnostics) throw CliUsageException("--java is only valid for compile")
                    options = options.copy(javaExecutable = Path.of(value()))
                    index += 2
                }
                "--force" -> {
                    options = options.copy(force = true)
                    index++
                }
                "--raw" -> {
                    if (!allowRaw) throw CliUsageException("--raw is only valid for compile")
                    options = options.copy(raw = true)
                    index++
                }
                "--diagnostics" -> {
                    if (!allowDiagnostics) throw CliUsageException("--diagnostics is only valid for compile")
                    options = options.copy(diagnostics = true)
                    index++
                }
                else -> throw CliUsageException("unknown ${arguments[0]} option '$option'")
            }
        }
        if (!sideSpecified) throw CliUsageException("${arguments[0]} requires --side <client|server>")
        return options
    }

    private fun printReconstruction(result: ReconstructionResult, out: PrintWriter) {
        val provenance = result.provenance
        out.println("Reconstructed Minecraft ${provenance.inputs().minecraftVersion()} ${provenance.inputs().side()}")
        out.println("  Java files:        ${provenance.output().javaFiles()}")
        out.println("  source-tree hash:  ${provenance.output().sourceTreeSha256()}")
        out.println("  generic changes:   ${provenance.changes().count { it.kind() == "generic-rule" }}")
        out.println("  explicit patches:  ${provenance.patches().size}")
        out.println("  reused:            ${if (result.reused) "yes" else "no"}")
        out.println("  path:              ${result.path}")
        out.println("  provenance:        ${result.provenancePath}")
        out.flush()
    }

    private fun printCompilation(result: CompilationResult, verbose: Boolean, out: PrintWriter) {
        val report = result.report
        val output = report.output()
        out.println("Compiled Minecraft ${report.inputs().minecraftVersion()} ${report.inputs().side()}: ${output.result().lowercase()}")
        out.println("  source files:      ${output.sourceFiles()}")
        out.println("  errors:            ${output.errors()}")
        out.println("  warnings:          ${output.warnings()}")
        if (output.result() == "SUCCESS") {
            out.println("  classfiles:        ${output.classFiles()}")
            out.println("  class-tree hash:   ${output.classTreeSha256()}")
            out.println("  reused:            ${if (result.reused) "yes" else "no"}")
        } else {
            val affected = report.diagnostics().filter { it.severity() == "ERROR" }.map { it.sourcePath() }.distinct().size
            out.println("  affected files:    $affected")
            out.println("  top groups:")
            report.diagnosticGroups().filter { it.severity() == "ERROR" }.sortedByDescending { it.count() }.take(8).forEach {
                out.println("    ${it.count()}  ${it.diagnosticCode()}")
            }
        }
        out.println("  report:            ${result.reportPath}")
        if (verbose && report.diagnostics().isNotEmpty()) {
            out.println()
            report.diagnostics().forEach { diagnostic ->
                val location = if (diagnostic.sourcePath().isBlank()) "<no source>" else "${diagnostic.sourcePath()}:${diagnostic.line()}:${diagnostic.column()}"
                out.println("${diagnostic.severity()} ${diagnostic.diagnosticCode()} $location")
                out.println("  ${diagnostic.message().replace("\n", "\n  ")}")
            }
        }
        out.flush()
    }

    private fun runDecompile(arguments: Array<String>, out: PrintWriter): Int {
        if (arguments.size < 2) throw CliUsageException("decompile requires a manifest path")
        val options = parseDecompileOptions(arguments)
        val manifest = parser.read(Path.of(arguments[1]))
        val platform = platformDetector.detect()
        val result = DecompilationService(CacheLayout(options.cacheDirectory)).decompile(
            manifest,
            platform,
            options.side,
            options.force,
            options.javaExecutable,
        )
        printDecompilation(result, options.verbose, out)
        return 0
    }

    private fun parseDecompileOptions(arguments: Array<String>): DecompileOptions {
        var options = DecompileOptions()
        val seen = mutableSetOf<String>()
        var sideSpecified = false
        var index = 2
        while (index < arguments.size) {
            val option = arguments[index]
            if (!seen.add(option)) throw CliUsageException("$option was specified more than once")
            fun value(): String {
                if (index + 1 >= arguments.size) throw CliUsageException("$option requires a value")
                return arguments[index + 1]
            }
            when (option) {
                "--side" -> {
                    options = options.copy(side = when (value()) {
                        "client" -> DecompilationSide.CLIENT
                        "server" -> DecompilationSide.SERVER
                        else -> throw CliUsageException("--side must be 'client' or 'server'")
                    })
                    sideSpecified = true
                    index += 2
                }
                "--cache-dir" -> {
                    options = options.copy(cacheDirectory = Path.of(value()))
                    index += 2
                }
                "--java" -> {
                    options = options.copy(javaExecutable = Path.of(value()))
                    index += 2
                }
                "--force" -> {
                    options = options.copy(force = true)
                    index++
                }
                "--verbose" -> {
                    options = options.copy(verbose = true)
                    index++
                }
                else -> throw CliUsageException("unknown decompile option '$option'")
            }
        }
        if (!sideSpecified) throw CliUsageException("decompile requires --side <client|server>")
        return options
    }

    private fun printDecompilation(result: DecompilationResult, verbose: Boolean, out: PrintWriter) {
        val provenance = result.provenance
        val inputs = provenance.inputs()
        val output = provenance.output()
        out.println("Decompiled Minecraft ${inputs.minecraftVersion()} ${inputs.side()}")
        out.println()
        out.println("Input:")
        out.println("  classes:           ${output.targetClasses()}")
        out.println("  artifact SHA-256:  ${inputs.targetArtifact().sha256()}")
        out.println()
        out.println("Vineflower:")
        out.println("  version:           ${inputs.vineflower().version()}")
        out.println("  external libs:     ${inputs.externalLibraries().size}")
        out.println("  Java runtime:      ${inputs.javaRuntime().runtimeIdentity()}")
        out.println()
        out.println("Output:")
        out.println("  Java files:        ${output.javaFiles()}")
        out.println("  source-tree hash:  ${output.sourceTreeSha256()}")
        out.println("  failed classes:    ${output.failedClasses().size}")
        out.println("  warnings:          ${provenance.diagnostics().count { it.severity() == "WARN" }}")
        out.println("  reused:            ${if (result.reused) "yes" else "no"}")
        if (verbose && provenance.diagnostics().isNotEmpty()) {
            out.println()
            out.println("Diagnostics:")
            provenance.diagnostics().forEach { diagnostic ->
                out.println("  ${diagnostic.severity()}: ${diagnostic.message()}")
                diagnostic.exceptionMessage()?.let { out.println("    ${diagnostic.exceptionType()}: $it") }
            }
        }
        out.println()
        out.println("Path:")
        out.println("  ${result.path}")
        out.flush()
    }

    private fun runLaunch(arguments: Array<String>, out: PrintWriter, execute: Boolean): Int {
        if (arguments.size < 3) throw CliUsageException("${arguments.firstOrNull()} requires a manifest path and client or server")
        val side = when (arguments[2]) {
            "client" -> LaunchSide.CLIENT
            "server" -> LaunchSide.SERVER
            else -> throw CliUsageException("launch side must be 'client' or 'server'")
        }
        val parsed = parseLaunchOptions(arguments, side)
        val manifestPath = Path.of(arguments[1])
        val manifest = parser.read(manifestPath)
        val platform = platformDetector.detect()
        val layout = CacheLayout(parsed.cacheDirectory)
        val preparer = RuntimePreparer(layout)
        val runtime = RuntimeSelectionOptions(parsed.javaExecutable, parsed.allowJavaMismatch)

        return when (side) {
            LaunchSide.CLIENT -> {
                val session = parsed.clientSession()
                val gameDirectory = parsed.gameDirectory
                    ?: layout.defaultGameDirectory(manifest.id, platform, "client")
                val preparation = preparer.prepareClient(
                    manifest,
                    platform,
                    runtime,
                    ClientLaunchOptions(
                        gameDirectory = gameDirectory,
                        session = session,
                        demo = parsed.demo,
                        useDefaultJvmArguments = !parsed.noDefaultJvmArguments,
                        width = parsed.width,
                        height = parsed.height,
                    ),
                )
                if (!execute) {
                    out.println(LaunchCommandRenderer.render(preparation.specification))
                    out.println()
                    out.println(
                        if (preparation.assets.complete) "Asset preflight: complete (${preparation.assets.expectedObjects} objects)"
                        else "Asset preflight: ${preparation.assets.missingObjects} of ${preparation.assets.expectedObjects} objects missing; " +
                            "run mc-recon resolve $manifestPath --assets before execution",
                    )
                    out.println("Natives: ${if (preparation.natives.reused) "reused" else "prepared"} at ${preparation.natives.directory}")
                    out.flush()
                    0
                } else {
                    preparer.requireClientAssets(preparation, manifestPath)
                    processLauncher.launch(preparation.specification)
                }
            }
            LaunchSide.SERVER -> {
                val gameDirectory = parsed.gameDirectory
                    ?: layout.defaultGameDirectory(manifest.id, platform, "server")
                val specification = preparer.prepareServer(
                    manifest,
                    runtime,
                    ServerLaunchOptions(gameDirectory, parsed.serverArguments),
                )
                if (!execute) {
                    out.println(LaunchCommandRenderer.render(specification))
                    out.flush()
                    0
                } else {
                    processLauncher.launch(specification)
                }
            }
        }
    }

    private fun parseLaunchOptions(arguments: Array<String>, side: LaunchSide): ParsedLaunchOptions {
        var options = ParsedLaunchOptions()
        var index = 3
        while (index < arguments.size) {
            val name = arguments[index]
            if (name == "--") {
                if (side != LaunchSide.SERVER) throw CliUsageException("only server launches accept arguments after --")
                options = options.copy(serverArguments = arguments.drop(index + 1).ifEmpty { listOf("nogui") })
                break
            }
            fun value(): String {
                if (index + 1 >= arguments.size) throw CliUsageException("$name requires a value")
                return arguments[index + 1]
            }
            options = when (name) {
                "--cache-dir" -> options.copy(cacheDirectory = Path.of(value())).also { index++ }
                "--java" -> options.copy(javaExecutable = Path.of(value())).also { index++ }
                "--game-dir", "--server-dir" -> options.copy(gameDirectory = Path.of(value())).also { index++ }
                "--allow-java-version-mismatch" -> options.copy(allowJavaMismatch = true)
                "--demo" -> clientOnly(side, name) { options.copy(demo = true) }
                "--no-default-jvm-args" -> clientOnly(side, name) { options.copy(noDefaultJvmArguments = true) }
                "--username" -> clientOnly(side, name) { options.copy(username = value()).also { index++ } }
                "--uuid" -> clientOnly(side, name) { options.copy(uuid = value()).also { index++ } }
                "--access-token" -> clientOnly(side, name) { options.copy(accessToken = value()).also { index++ } }
                "--client-id" -> clientOnly(side, name) { options.copy(clientId = value()).also { index++ } }
                "--xuid" -> clientOnly(side, name) { options.copy(xuid = value()).also { index++ } }
                "--width" -> clientOnly(side, name) { options.copy(width = positiveInt(name, value())).also { index++ } }
                "--height" -> clientOnly(side, name) { options.copy(height = positiveInt(name, value())).also { index++ } }
                else -> throw CliUsageException("unknown ${side.name.lowercase()} launch option '$name'")
            }
            index++
        }
        return options
    }

    private fun clientOnly(side: LaunchSide, option: String, action: () -> ParsedLaunchOptions): ParsedLaunchOptions {
        if (side != LaunchSide.CLIENT) throw CliUsageException("$option is only valid for client launches")
        return action()
    }

    private fun positiveInt(option: String, value: String): Int =
        value.toIntOrNull()?.takeIf { it > 0 } ?: throw CliUsageException("$option requires a positive integer")

    private fun runInspect(arguments: Array<String>, out: PrintWriter): Int {
        if (arguments.size != 2) throw CliUsageException("inspect requires exactly one manifest path")
        val manifest = parser.read(Path.of(arguments[1]))
        printInspection(manifest, out)
        return 0
    }

    private fun runResolve(arguments: Array<String>, out: PrintWriter): Int {
        if (arguments.size < 2) throw CliUsageException("resolve requires a manifest path")
        var cacheDirectory = Path.of(".mc-recon")
        var includeAssets = false
        var includeCompileLibraries = false
        var cacheSpecified = false
        var index = 2
        while (index < arguments.size) {
            when (arguments[index]) {
                "--assets" -> {
                    if (includeAssets) throw CliUsageException("--assets was specified more than once")
                    includeAssets = true
                    index++
                }
                "--compile-libraries" -> {
                    if (includeCompileLibraries) throw CliUsageException("--compile-libraries was specified more than once")
                    includeCompileLibraries = true
                    index++
                }
                "--cache-dir" -> {
                    if (cacheSpecified) throw CliUsageException("--cache-dir was specified more than once")
                    if (index + 1 >= arguments.size) throw CliUsageException("--cache-dir requires a path")
                    cacheDirectory = Path.of(arguments[index + 1])
                    cacheSpecified = true
                    index += 2
                }
                else -> throw CliUsageException("unknown resolve option '${arguments[index]}'")
            }
        }

        val manifest = parser.read(Path.of(arguments[1]))
        val platform = platformDetector.detect()
        val resolver = VersionResolver(CacheLayout(cacheDirectory))
        val resolved = resolver.resolve(manifest, platform, includeAssets)
        printResolution(resolved, includeAssets, out)
        if (includeCompileLibraries) {
            val compileLibraries = resolver.resolveCompileLibraries(manifest)
            out.println("Compile libraries:      ${compileLibraries.size} verified")
            out.flush()
        }
        return 0
    }

    private fun runIndex(arguments: Array<String>, out: PrintWriter): Int {
        if (arguments.size < 2) throw CliUsageException("index requires a manifest path")
        val options = parseBinaryOptions(arguments, 2, allowFormat = false)
        val manifest = parser.read(Path.of(arguments[1]))
        val stored = BinaryIndexService(CacheLayout(options.cacheDirectory)).loadOrCreate(manifest, options.side)
        printIndex(stored, out)
        return 0
    }

    private fun runBinaryDiff(arguments: Array<String>, out: PrintWriter): Int {
        if (arguments.size < 3) throw CliUsageException("diff-bin requires two manifest paths")
        val options = parseBinaryOptions(arguments, 3, allowFormat = true)
        val beforeManifest = parser.read(Path.of(arguments[1]))
        val afterManifest = parser.read(Path.of(arguments[2]))
        val layout = CacheLayout(options.cacheDirectory)
        val service = BinaryIndexService(layout)
        val before = service.loadOrCreate(beforeManifest, options.side).index
        val after = service.loadOrCreate(afterManifest, options.side).index
        val diff = BinaryDiffEngine().compare(before, after)
        if (options.format == "json") {
            JarIndexStore.deterministicObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out, diff)
            out.println()
            out.flush()
        } else {
            BinaryDiffRenderer.render(diff, out)
            if (options.details) {
                val details = BinaryDiffDetailsService().inspect(
                    layout.versionArtifact(beforeManifest.id, "${options.side.cliName}.jar"),
                    layout.versionArtifact(afterManifest.id, "${options.side.cliName}.jar"),
                    diff,
                )
                BinaryDiffDetailsRenderer.render(details, beforeManifest.id, afterManifest.id, out)
            }
        }
        return 0
    }

    private fun runInspectMethod(arguments: Array<String>, out: PrintWriter): Int {
        if (arguments.size < 2) throw CliUsageException("inspect-method requires a manifest path")
        val options = parseMethodOptions(arguments)
        val manifest = parser.read(Path.of(arguments[1]))
        val layout = CacheLayout(options.cacheDirectory)
        BinaryIndexService(layout).loadOrCreate(manifest, options.side)
        val method = ClassfileMethodInspector().inspect(
            layout.versionArtifact(manifest.id, "${options.side.cliName}.jar"),
            options.className,
            options.methodName,
            options.descriptor,
        )
        SymbolicMethodRenderer.render(options.className, method, out)
        return 0
    }

    private fun parseMethodOptions(arguments: Array<String>): MethodOptions {
        var cacheDirectory = Path.of(".mc-recon")
        var side: BinarySide? = null
        var className: String? = null
        var methodName: String? = null
        var descriptor: String? = null
        val seen = mutableSetOf<String>()
        var index = 2
        while (index < arguments.size) {
            val option = arguments[index]
            if (option !in setOf("--cache-dir", "--side", "--class", "--method", "--descriptor")) {
                throw CliUsageException("unknown inspect-method option '$option'")
            }
            if (!seen.add(option)) throw CliUsageException("$option was specified more than once")
            if (index + 1 >= arguments.size) throw CliUsageException("$option requires a value")
            val value = arguments[index + 1]
            when (option) {
                "--cache-dir" -> cacheDirectory = Path.of(value)
                "--side" -> side = when (value) {
                    "client" -> BinarySide.CLIENT
                    "server" -> BinarySide.SERVER
                    else -> throw CliUsageException("--side must be 'client' or 'server'")
                }
                "--class" -> className = value
                "--method" -> methodName = value
                "--descriptor" -> descriptor = value
            }
            index += 2
        }
        return MethodOptions(
            cacheDirectory,
            side ?: throw CliUsageException("inspect-method requires --side <client|server>"),
            className ?: throw CliUsageException("inspect-method requires --class <binary-name>"),
            methodName ?: throw CliUsageException("inspect-method requires --method <name>"),
            descriptor ?: throw CliUsageException("inspect-method requires --descriptor <JVM-descriptor>"),
        )
    }

    private fun parseBinaryOptions(arguments: Array<String>, start: Int, allowFormat: Boolean): BinaryOptions {
        var options = BinaryOptions()
        var sideSpecified = false
        var cacheSpecified = false
        var formatSpecified = false
        var detailsSpecified = false
        var index = start
        while (index < arguments.size) {
            val option = arguments[index]
            fun value(): String {
                if (index + 1 >= arguments.size) throw CliUsageException("$option requires a value")
                return arguments[index + 1]
            }
            when (option) {
                "--side" -> {
                    if (sideSpecified) throw CliUsageException("--side was specified more than once")
                    val side = when (value()) {
                        "client" -> BinarySide.CLIENT
                        "server" -> BinarySide.SERVER
                        else -> throw CliUsageException("--side must be 'client' or 'server'")
                    }
                    options = options.copy(side = side)
                    sideSpecified = true
                    index += 2
                }
                "--cache-dir" -> {
                    if (cacheSpecified) throw CliUsageException("--cache-dir was specified more than once")
                    options = options.copy(cacheDirectory = Path.of(value()))
                    cacheSpecified = true
                    index += 2
                }
                "--format" -> {
                    if (!allowFormat) throw CliUsageException("--format is only valid for diff-bin")
                    if (formatSpecified) throw CliUsageException("--format was specified more than once")
                    val format = value()
                    if (format != "text" && format != "json") {
                        throw CliUsageException("--format must be 'text' or 'json'")
                    }
                    options = options.copy(format = format)
                    formatSpecified = true
                    index += 2
                }
                "--details" -> {
                    if (!allowFormat) throw CliUsageException("--details is only valid for diff-bin")
                    if (detailsSpecified) throw CliUsageException("--details was specified more than once")
                    options = options.copy(details = true)
                    detailsSpecified = true
                    index++
                }
                else -> throw CliUsageException("unknown ${arguments[0]} option '$option'")
            }
        }
        if (!sideSpecified) throw CliUsageException("${arguments[0]} requires --side <client|server>")
        if (options.details && options.format == "json") {
            throw CliUsageException("--details is available only with text output")
        }
        return options
    }

    private fun printIndex(stored: StoredJarIndex, out: PrintWriter) {
        val index = stored.index
        val artifact = index.artifact()
        val methods = index.classes().sumOf { it.methods().size }
        val fields = index.classes().sumOf { it.fields().size }
        val withCode = index.classes().sumOf { clazz -> clazz.methods().count { it.hasCode() } }
        out.println("Indexed Minecraft ${artifact.minecraftVersion()} ${artifact.side()}")
        if (artifact.artifactKind() == "server-distribution") {
            out.println("Artifact is the published server distribution/bundler, not the extracted server implementation.")
        }
        out.println()
        out.println("Artifact:")
        out.println("  Size:          ${formatSize(artifact.size())}")
        out.println("  SHA-1:         ${artifact.mojangSha1()}")
        out.println("  SHA-256:       ${artifact.artifactHash()}")
        out.println()
        out.println("Entries:")
        out.println("  total:         ${index.entries().size}")
        out.println("  classes:       ${index.classes().size}")
        out.println("  resources:     ${index.resources().size}")
        out.println("  directories:   ${index.entries().count { it.kind() == EntryKind.DIRECTORY }}")
        out.println()
        out.println("Classes:")
        out.println("  methods:       $methods")
        out.println("  fields:        $fields")
        out.println("  with code:     $withCode")
        out.println()
        out.println("Classfile versions:")
        index.classes().groupingBy { it.majorVersion() }.eachCount().toSortedMap().forEach { (version, count) ->
            out.println("  $version:            $count")
        }
        out.println()
        out.println("Index:")
        out.println("  ${stored.path} (${if (stored.reused) "reused" else "generated"})")
        out.flush()
    }

    private fun printInspection(manifest: VersionManifest, out: PrintWriter) {
        val platform = platformDetector.detect()
        val context = RuleContext(platform)
        val applicableLibraries = manifest.libraries.count { RuleEvaluator.isAllowed(it.rules, context) }

        out.println("Minecraft ${manifest.id}")
        out.println()
        out.println("Type:           ${manifest.type.manifestName}")
        out.println("Java:           ${manifest.javaVersion.majorVersion}")
        out.println("Java component: ${manifest.javaVersion.component}")
        out.println()
        printDownload("Client", manifest.clientDownload, out)
        printDownload("Server", manifest.serverDownload, out)
        out.println("Assets:")
        out.println("  index:        ${manifest.assetIndex.id}")
        out.println("  SHA-1:        ${manifest.assetIndex.sha1}")
        out.println("  Size:         ${formatSize(manifest.assetIndex.size)}")
        out.println("  Total size:   ${formatSize(manifest.assetIndex.totalSize)}")
        out.println("  URL:          ${manifest.assetIndex.url}")
        out.println()
        out.println("Libraries:")
        out.println("  total:        ${manifest.libraries.size}")
        out.println("  applicable:   $applicableLibraries")
        out.println("  platform:     ${platform.operatingSystem.manifestName}/${platform.architecture.canonicalName} ${platform.version}")
        out.println()
        out.println("Client main:")
        out.println("  ${manifest.mainClass}")
        out.println()
        out.println("Release time:")
        out.println("  ${manifest.releaseTime}")
        out.flush()
    }

    private fun printDownload(name: String, download: Download, out: PrintWriter) {
        out.println("$name:")
        out.println("  SHA-1:        ${download.sha1}")
        out.println("  Size:         ${formatSize(download.size)}")
        out.println("  URL:          ${download.url}")
        out.println()
    }

    private fun formatSize(size: Long): String = "%,d bytes".format(size)

    private fun printResolution(resolved: ResolvedVersion, includedAssets: Boolean, out: PrintWriter) {
        val standardLibraries = resolved.libraries.sumOf { library ->
            library.artifacts.count { it.role == LibraryArtifactRole.STANDARD }
        }
        out.println(
            "Resolved Minecraft ${resolved.manifest.id} for " +
                "${resolved.platform.operatingSystem.manifestName}/${resolved.platform.architecture.canonicalName}",
        )
        out.println()
        out.println("Client:                 ${originLabel(resolved.client)}")
        out.println("Server:                 ${originLabel(resolved.server)}")
        out.println("Libraries applicable:  ${resolved.libraries.size}")
        out.println("  standard artifacts:  $standardLibraries")
        out.println("  classifiers/natives: ${resolved.classifierAndNativeArtifacts.size}")
        out.println("Asset index:            ${originLabel(resolved.assetIndex.file)} (${resolved.assetIndex.document.objects.size} objects)")
        if (includedAssets) {
            val uniqueAssets = resolved.assetObjects.values.distinctBy { it.path }
            out.println("Asset objects:          ${describeArtifacts(uniqueAssets)}")
        } else {
            out.println("Asset objects:          not requested (use --assets)")
        }
        out.println(
            "Logging configuration:  " +
                if (resolved.loggingConfigurations.isEmpty()) "not present"
                else describeArtifacts(resolved.loggingConfigurations.values.map { it.file }),
        )
        out.println()
        out.println("Verified artifacts:     ${resolved.summary.verifiedArtifacts}")
        out.println("Downloaded:             ${resolved.summary.downloadedArtifacts}")
        out.println("Cached:                 ${resolved.summary.cachedArtifacts}")
        if (resolved.summary.replacedCorruptArtifacts > 0) {
            out.println("Corrupt entries replaced: ${resolved.summary.replacedCorruptArtifacts}")
        }
        out.println("Bytes downloaded:       ${formatSize(resolved.summary.bytesDownloaded)}")
        out.println()
        out.println("Cache:")
        out.println("  ${resolved.cacheRoot}")
        out.flush()
    }

    private fun originLabel(artifact: ResolvedArtifact): String = when {
        artifact.replacedCorruptEntry -> "downloaded (replaced corrupt cache entry)"
        artifact.origin == ArtifactOrigin.CACHE -> "cached"
        else -> "downloaded"
    }

    private fun describeArtifacts(artifacts: Collection<ResolvedArtifact>): String {
        val cached = artifacts.count { it.origin == ArtifactOrigin.CACHE }
        val downloaded = artifacts.size - cached
        return "${artifacts.size} verified ($cached cached, $downloaded downloaded)"
    }

    private fun printUsage(writer: PrintWriter) {
        writer.println("Usage:")
        writer.println("  mc-recon inspect <version-manifest.json>")
        writer.println("  mc-recon resolve <version-manifest.json> [--cache-dir <path>] [--assets] [--compile-libraries]")
        writer.println("  mc-recon index <version-manifest.json> --side <client|server> [--cache-dir <path>]")
        writer.println("  mc-recon diff-bin <before.json> <after.json> --side <client|server> [--cache-dir <path>] [--format <text|json>] [--details]")
        writer.println("  mc-recon inspect-method <version.json> --side <client|server> --class <binary-name> --method <name> --descriptor <descriptor> [--cache-dir <path>]")
        writer.println("  mc-recon decompile <version.json> --side <client|server> [--cache-dir <path>] [--java <path>] [--force] [--verbose]")
        writer.println("  mc-recon reconstruct <version.json> --side <client|server> [--cache-dir <path>] [--force]")
        writer.println("  mc-recon compile <version.json> --side <client|server> [--cache-dir <path>] [--java <path>] [--force] [--raw] [--diagnostics]")
        writer.println("  mc-recon verify <version.json> --side <client|server> [--cache-dir <path>] [--format <text|json>] [--class <binary-name> [--details]]")
        writer.println("  mc-recon verify-compare <before-verification.json> <after-verification.json> [--format <text|json>]")
        writer.println("  mc-recon launch-command <version-manifest.json> <client|server> [launch options]")
        writer.println("  mc-recon run <version-manifest.json> <client|server> [launch options]")
        writer.println()
        writer.println("Launch options: --cache-dir <path> --java <path> --game-dir <path> --allow-java-version-mismatch")
        writer.println("Client: [--demo] [--username <name>] [--uuid <uuid>] [--access-token <token>] [--client-id <id>] [--xuid <xuid>]")
        writer.println("        [--width <pixels> --height <pixels>] [--no-default-jvm-args]")
        writer.println("Server: pass server arguments after -- (defaults to nogui)")
        writer.flush()
    }

    private fun fail(writer: PrintWriter, exitCode: Int, message: String): Int {
        writer.println(message)
        writer.flush()
        return exitCode
    }
}

private class CliUsageException(message: String) : RuntimeException(message)

private data class ParsedLaunchOptions(
    val cacheDirectory: Path = Path.of(".mc-recon"),
    val javaExecutable: Path? = null,
    val gameDirectory: Path? = null,
    val allowJavaMismatch: Boolean = false,
    val demo: Boolean = false,
    val noDefaultJvmArguments: Boolean = false,
    val username: String? = null,
    val uuid: String? = null,
    val accessToken: String? = null,
    val clientId: String = "",
    val xuid: String = "",
    val width: Int? = null,
    val height: Int? = null,
    val serverArguments: List<String> = listOf("nogui"),
) {
    fun clientSession(): ClientSession {
        val defaults = if (demo) ClientLaunchBuilder.DEMO_SESSION else ClientLaunchBuilder.SYNTHETIC_SESSION
        return ClientSession(
            username = username ?: defaults.username,
            uuid = uuid ?: defaults.uuid,
            accessToken = accessToken ?: defaults.accessToken,
            clientId = clientId,
            xuid = xuid,
        )
    }
}

private data class BinaryOptions(
    val cacheDirectory: Path = Path.of(".mc-recon"),
    val side: BinarySide = BinarySide.CLIENT,
    val format: String = "text",
    val details: Boolean = false,
)

private data class MethodOptions(
    val cacheDirectory: Path,
    val side: BinarySide,
    val className: String,
    val methodName: String,
    val descriptor: String,
)

private data class VerificationOptions(
    val cacheDirectory: Path = Path.of(".mc-recon"),
    val side: BinarySide = BinarySide.CLIENT,
    val format: String = "text",
    val className: String? = null,
    val details: Boolean = false,
)

private data class DecompileOptions(
    val cacheDirectory: Path = Path.of(".mc-recon"),
    val side: DecompilationSide = DecompilationSide.CLIENT,
    val javaExecutable: Path? = null,
    val force: Boolean = false,
    val verbose: Boolean = false,
)

private data class SourceBuildOptions(
    val cacheDirectory: Path = Path.of(".mc-recon"),
    val side: DecompilationSide = DecompilationSide.CLIENT,
    val javaExecutable: Path? = null,
    val force: Boolean = false,
    val raw: Boolean = false,
    val diagnostics: Boolean = false,
)
