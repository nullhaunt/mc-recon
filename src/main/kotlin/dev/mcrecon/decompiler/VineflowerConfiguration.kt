package dev.mcrecon.decompiler

import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences
import java.nio.file.Path
import java.util.TreeMap

object VineflowerConfiguration {
    const val VERSION = "1.12.0"
    const val RUNTIME_PROVENANCE_VALUE = "selected-java-runtime"

    /** Complete pinned logical option set stored in provenance and used for cache identity. */
    fun provenanceOptions(): Map<String, String> = effectiveOptions(RUNTIME_PROVENANCE_VALUE)

    /** Actual API options; only the selected Java-home token is materialized as a local path. */
    fun apiOptions(javaHome: Path): Map<String, Any> = effectiveOptions(javaHome.toString())

    private fun effectiveOptions(runtime: String): Map<String, String> {
        val options = TreeMap<String, String>()
        IFernflowerPreferences.DEFAULTS.forEach { (name, value) -> options[name] = value.toString() }

        // Pin dynamic/environment-sensitive behavior and deliberate reconstruction choices.
        options[IFernflowerPreferences.INCLUDE_ENTIRE_CLASSPATH] = "0"
        options[IFernflowerPreferences.INCLUDE_JAVA_RUNTIME] = runtime
        options[IFernflowerPreferences.THREADS] = "4"
        options[IFernflowerPreferences.NEW_LINE_SEPARATOR] = "1"
        options[IFernflowerPreferences.SKIP_EXTRA_FILES] = "1"
        options[IFernflowerPreferences.BANNER] = ""
        options[IFernflowerPreferences.LOG_LEVEL] = "INFO"
        options[IFernflowerPreferences.RENAME_ENTITIES] = "0"
        options[IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES] = "1"
        options[IFernflowerPreferences.DECOMPILE_INNER] = "1"
        options[IFernflowerPreferences.DECOMPILE_ENUM] = "1"
        options[IFernflowerPreferences.DECOMPILE_PREVIEW] = "1"
        options[IFernflowerPreferences.PATTERN_MATCHING] = "1"
        options[IFernflowerPreferences.SWITCH_EXPRESSIONS] = "1"
        options[IFernflowerPreferences.LAMBDA_TO_ANONYMOUS_CLASS] = "0"
        options[IFernflowerPreferences.USE_DEBUG_VAR_NAMES] = "1"
        options[IFernflowerPreferences.USE_METHOD_PARAMETERS] = "1"
        options[IFernflowerPreferences.LITERALS_AS_IS] = "1"
        options[IFernflowerPreferences.IGNORE_INVALID_BYTECODE] = "0"
        options[IFernflowerPreferences.DUMP_BYTECODE_ON_ERROR] = "0"
        options[IFernflowerPreferences.DUMP_EXCEPTION_ON_ERROR] = "0"
        options[IFernflowerPreferences.PRETTIFY_IFS] = "0"
        options[IFernflowerPreferences.TERNARY_CONDITIONS] = "0"
        options[IFernflowerPreferences.INLINE_SIMPLE_LAMBDAS] = "0"
        return options
    }
}
