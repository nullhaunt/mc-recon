package dev.mcrecon.verification

import com.fasterxml.jackson.databind.ObjectMapper
import dev.mcrecon.classfile.JarIndexStore
import dev.mcrecon.verification.VerificationModel.Report
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class VerificationReportStore(
    private val mapper: ObjectMapper = JarIndexStore.deterministicObjectMapper(),
) {
    fun read(path: Path): Report {
        if (!Files.isRegularFile(path)) throw VerificationException("Verification report does not exist: $path")
        return try {
            Files.newInputStream(path).use { mapper.readValue(it, Report::class.java) }
        } catch (exception: Exception) {
            throw VerificationException("Cannot read verification report $path: ${exception.message}", exception)
        }
    }

    fun write(path: Path, report: Report) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n"
            Files.writeString(temporary, json, StandardCharsets.UTF_8)
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
