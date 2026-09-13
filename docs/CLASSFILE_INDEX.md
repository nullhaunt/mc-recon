# Classfile index and binary diff

The binary index is an evidence model over JAR and classfile data. It does not invoke a
decompiler and does not depend on mappings. Parsing uses the standard Java 25
`java.lang.classfile` API because it supports classfile version 69 directly and exposes
instructions, labels, constant values, member references, dynamic constants, and
bootstrap methods symbolically. ASM is therefore unnecessary for this milestone.

## Persisted model

Each index records an explicit schema version and source artifact identity. The artifact
identity contains the Minecraft version, selected side, artifact kind, exact size,
Mojang SHA-1, and whole-file SHA-256. A cached index is reused only when both its schema
version and complete artifact identity match the current resolver-verified file.

Indexes are gzip-compressed deterministic JSON under:

```text
.mc-recon/indexes/<minecraft-version>/<side>.json.gz
```

The JSON contains no generation time, temporary path, ZIP timestamp, or host-specific
data. Lists that represent logical sets are sorted; lists with semantic order, including
interfaces, record components, instructions, and exception handlers, preserve that
order. Persistence is published with an atomic replace where the filesystem supports it.

## SHA-256 identities

The index uses distinct fields rather than an overloaded generic hash:

- `artifactHash` hashes every byte of the source JAR.
- `entryHash` hashes the uncompressed bytes of one JAR entry.
- `classExactHash` hashes the complete classfile bytes.
- `classStructureHash` hashes the canonical class declaration and metadata described
  below, excluding executable bodies and debug/source-position data.
- `memberMetadataHash` hashes one field or method declaration and its normalized
  metadata. For fields this includes the logical constant value.
- `methodCodeHash` hashes canonical executable instructions and exception regions.
- `debugHash` hashes normalized method line/local-variable/type-debug information.
- `classDebugHash` hashes class-level source-file and source-debug-extension information.

Mojang SHA-1 remains present only as the distribution-integrity identity supplied by the
manifest. All archaeology fingerprints use SHA-256.

## Class structure

`classStructureHash` includes:

- binary name and classfile major/minor version;
- access flags, superclass, and ordered interfaces;
- generic signature and declaration/type annotations;
- nest host/members, inner-class relationships, and enclosing method;
- ordered record components and permitted subclasses;
- normalized field and method metadata fingerprints; and
- non-debug attribute names.

It excludes method bodies, exception regions, line numbers, local-variable tables,
local-variable-type tables, source-file metadata, and source-debug extensions. This lets
a method implementation or debug table change without falsely appearing to alter the
class declaration.

Field identity is `name + descriptor`. Field metadata includes access flags, signature,
logical constant value, annotations, type annotations, and attribute inventory. Method
identity is also `name + descriptor`; method metadata includes access flags, signature,
ordered declared exceptions, annotations, parameter annotations, annotation default,
method parameters, and non-Code attribute inventory. A descriptor change is therefore a
member removal plus addition, never a name-only merge.

Known important classfile metadata is stored explicitly. Every attribute location also
has an attribute-name inventory. Unknown attributes retain the SHA-256 of their opaque
payload, so the index makes their presence and exact changes visible even when no safe
logical normalization is available.

## Executable method fingerprint

`methodCodeHash` is built from a length-delimited canonical token stream, not raw Code
bytes. It includes the ordered instruction sequence and logical operands, followed by the
ordered exception-handler table.

Canonicalization rules include:

- field and method references use owner binary name, member name, and descriptor;
- constants retain their logical type and value, with raw IEEE bits for floats/doubles;
- class, method-type, and method-handle constants use symbolic descriptors;
- `invokedynamic` and constant-dynamic values recursively include the call-site name and
  descriptor, bootstrap method handle, and logical bootstrap arguments;
- branch, switch, try-region, and handler labels become instruction ordinals;
- switch cases are encoded by case value and canonical target;
- short/long encodings such as `ldc`/`ldc_w`, compact/wide local loads and stores,
  `iinc`/wide `iinc`, and `goto`/`goto_w` normalize to the same logical operation; and
- array operations, conversions, monitors, type checks, allocation, stack operations,
  returns, and discontinued `jsr`/`ret` instructions retain their semantic operands.

As a result, constant-pool reordering or an operand-width serialization choice does not
change `methodCodeHash`, while a changed symbolic reference, constant, bootstrap, branch
target, switch target, or exception region does.

Line numbers, local-variable tables, local-variable-type tables, and code type annotations
are excluded from `methodCodeHash` and included in `debugHash` using instruction-ordinal
positions. Stack-map frames, maximum stack, and maximum locals are excluded from the
primary code hash because they are verifier/serialization data derived from executable
code. Their attribute presence remains visible, and any byte change remains visible via
`classExactHash`.

## Diff classification and limits

The diff engine compares structured indexes. Archive entries and resources are classified
as added, removed, changed, or unchanged by path and exact content. Classes are matched by
JAR entry path, then fields and methods by their JVM identities. Method metadata, code,
and debug changes are independent flags, so a changed containing class never implies a
changed method body.

An exact class change with all covered structure, code, and debug fingerprints unchanged
is reported as `exact bytes changed (outside normalized coverage)`. This is intentional:
the current schema inventories but does not semantically normalize every obscure or
vendor-specific attribute, nor does it treat stack-map serialization as executable
behavior. Such a result remains evidence for later expansion rather than being silently
classified or discarded.

Modern published server JARs are outer distributions/bundlers. They can be indexed as
ordinary JARs, but the result is labeled accordingly and is not equivalent to an index of
the bundled dedicated-server implementation.
