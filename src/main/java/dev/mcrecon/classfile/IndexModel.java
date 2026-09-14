package dev.mcrecon.classfile;

import java.util.List;

/** Versioned, persistence-safe domain model for JAR and classfile indexes. */
public final class IndexModel {
    private IndexModel() {}

    public static final int SCHEMA_VERSION = 2;

    public enum EntryKind {
        CLASS,
        RESOURCE,
        DIRECTORY
    }

    public record ArtifactIdentity(
            String minecraftVersion,
            String side,
            String artifactKind,
            long size,
            String mojangSha1,
            String artifactHash
    ) {}

    public record JarIndex(
            int schemaVersion,
            ArtifactIdentity artifact,
            List<JarEntryIndex> entries,
            List<ClassIndex> classes,
            List<ResourceIndex> resources
    ) {}

    public record JarEntryIndex(
            String path,
            EntryKind kind,
            long size,
            String entryHash
    ) {}

    public record ResourceIndex(
            String path,
            long size,
            String entryHash
    ) {}

    /**
     * An attribute inventory entry. Known attributes retain their name. Unknown
     * attributes additionally retain an exact payload SHA-256 so their contents
     * are never silently discarded by the normalized index.
     */
    public record AttributeIndex(
            String name,
            boolean unknown,
            String unknownContentHash
    ) {}

    public record InnerClassIndex(
            String innerClass,
            String outerClass,
            String innerName,
            int accessFlags
    ) {}

    public record EnclosingMethodIndex(
            String owner,
            String name,
            String descriptor
    ) {}

    public record RecordComponentIndex(
            String name,
            String descriptor,
            String signature,
            List<String> annotations,
            List<String> typeAnnotations,
            List<AttributeIndex> attributes,
            String metadataHash
    ) {}

    public record FieldIndex(
            String name,
            String descriptor,
            int accessFlags,
            String signature,
            String constantValue,
            List<String> annotations,
            List<String> typeAnnotations,
            List<AttributeIndex> attributes,
            String memberMetadataHash
    ) {
        public String identity() {
            return name + descriptor;
        }
    }

    public record MethodParameterIndex(
            String name,
            int accessFlags
    ) {}

    public record MethodIndex(
            String name,
            String descriptor,
            int accessFlags,
            String signature,
            List<String> exceptions,
            List<String> annotations,
            List<String> typeAnnotations,
            List<String> parameterAnnotations,
            String annotationDefault,
            List<MethodParameterIndex> parameters,
            List<AttributeIndex> attributes,
            List<AttributeIndex> codeAttributes,
            boolean hasCode,
            String memberMetadataHash,
            String methodCodeHash,
            String debugHash,
            String lineNumberHash,
            String localVariableHash,
            String localVariableTypeHash,
            String codeTypeAnnotationHash,
            String exactCodeArrayHash,
            String stackMapHash,
            Integer maxStack,
            Integer maxLocals
    ) {
        public String identity() {
            return name + descriptor;
        }
    }

    public record ClassIndex(
            String entryPath,
            String binaryName,
            long exactByteSize,
            String classExactHash,
            int minorVersion,
            int majorVersion,
            int accessFlags,
            String superclass,
            List<String> interfaces,
            String signature,
            String sourceFile,
            String nestHost,
            List<String> nestMembers,
            List<InnerClassIndex> innerClasses,
            EnclosingMethodIndex enclosingMethod,
            List<RecordComponentIndex> recordComponents,
            List<String> permittedSubclasses,
            List<String> annotations,
            List<String> typeAnnotations,
            List<AttributeIndex> attributes,
            String classStructureHash,
            String classDebugHash,
            List<FieldIndex> fields,
            List<MethodIndex> methods
    ) {}
}
