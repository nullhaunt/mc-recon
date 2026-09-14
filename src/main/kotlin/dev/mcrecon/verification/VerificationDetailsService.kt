package dev.mcrecon.verification

import dev.mcrecon.classfile.ClassfileMethodInspector
import dev.mcrecon.classfile.DetailedMethodChange
import dev.mcrecon.classfile.DiffModel.ChangeKind
import dev.mcrecon.classfile.DiffModel.MethodChange
import dev.mcrecon.classfile.SymbolicCodeDiff
import dev.mcrecon.classfile.SymbolicMethodCode
import dev.mcrecon.verification.VerificationModel.ClassVerification
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.jar.JarFile

class VerificationDetailsService(
    private val inspector: ClassfileMethodInspector = ClassfileMethodInspector(),
    private val representationInspector: ClassfileRepresentationInspector = ClassfileRepresentationInspector(),
) {
    fun inspect(referenceJar: Path, candidateRoot: Path, result: ClassVerification): List<DetailedMethodChange> {
        val oldMethods = readReferenceBytes(referenceJar, result.entryPath())?.let(inspector::inspect).orEmpty()
        val rebuiltMethods = readCandidateBytes(candidateRoot, result.entryPath())?.let(inspector::inspect).orEmpty()
        val oldByIdentity = oldMethods.associateBy(SymbolicMethodCode::identity)
        val rebuiltByIdentity = rebuiltMethods.associateBy(SymbolicMethodCode::identity)
        val metadata = result.methods().metadataChangedMethods().toSet()
        val code = result.methods().codeChangedMethods().toSet()
        val debug = result.methods().debugChangedMethods().toSet()
        val missing = result.methods().missingMethods().toSet()
        val extra = result.methods().extraMethods().toSet()
        val relevant = (code + missing + extra).toSortedSet()
        return relevant.map { identity ->
            val old = oldByIdentity[identity]
            val rebuilt = rebuiltByIdentity[identity]
            val changeKind = when {
                identity in missing -> ChangeKind.REMOVED
                identity in extra -> ChangeKind.ADDED
                else -> ChangeKind.CHANGED
            }
            val (name, descriptor) = splitIdentity(identity, old, rebuilt)
            val change = MethodChange(
                name,
                descriptor,
                changeKind,
                identity in metadata,
                identity in code,
                identity in debug,
                identity in debug && identity !in metadata && identity !in code,
            )
            DetailedMethodChange(
                result.binaryName(),
                result.entryPath(),
                change,
                old,
                rebuilt,
                SymbolicCodeDiff.compare(old?.elements.orEmpty(), rebuilt?.elements.orEmpty()),
            )
        }
    }

    fun inspectRepresentation(
        referenceJar: Path,
        candidateRoot: Path,
        result: ClassVerification,
    ): ClassfileRepresentationDetails = representationInspector.inspect(
        readReferenceBytes(referenceJar, result.entryPath()),
        readCandidateBytes(candidateRoot, result.entryPath()),
    )

    private fun readReferenceBytes(jarPath: Path, entryPath: String): ByteArray? =
        JarFile(jarPath.toFile(), false).use { jar ->
            val entry = jar.getJarEntry(entryPath) ?: return null
            jar.getInputStream(entry).use { it.readAllBytes() }
        }

    private fun readCandidateBytes(root: Path, entryPath: String): ByteArray? {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val path = normalizedRoot.resolve(entryPath.replace('/', java.io.File.separatorChar)).normalize()
        if (!path.startsWith(normalizedRoot)) throw VerificationException("Unsafe candidate class entry path '$entryPath'")
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        return Files.readAllBytes(path)
    }

    private fun splitIdentity(
        identity: String,
        old: SymbolicMethodCode?,
        rebuilt: SymbolicMethodCode?,
    ): Pair<String, String> {
        val method = old ?: rebuilt
        if (method != null) return method.name to method.descriptor
        val descriptorStart = identity.indexOf('(')
        if (descriptorStart < 0) throw VerificationException("Invalid method identity '$identity'")
        return identity.substring(0, descriptorStart) to identity.substring(descriptorStart)
    }
}
