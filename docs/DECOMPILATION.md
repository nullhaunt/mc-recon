# Deterministic raw-source decompilation

`mc-recon decompile <manifest> --side client` turns the resolver-verified client artifact
into an inspectable raw Java tree. This is a provenance boundary: the tree records what
the pinned decompiler produced, not a compile-ready reconstruction. No mappings, source
repairs, resources, compiler probes, or workspace generation participate.

## Integration strategy

The build pins the regular Maven artifact `org.vineflower:vineflower:1.12.0` exactly.
mc-recon calls Vineflower's `Fernflower` library API in-process. This avoids a discovered
or user-installed executable, avoids shell construction, keeps diagnostics structured,
and permits a strict result saver that writes only target Java source. The target JAR is
added with `addSource`; dependencies and the Java runtime are context only.

The result saver rejects absolute, drive-qualified, backslash, traversal, duplicate, and
non-`.java` output paths. Source is encoded explicitly as UTF-8. CRLF and bare CR are
normalized to LF; no other source rewriting or formatting occurs. `skip-extra-files=1`
prevents client resources from entering `raw-source`.

Generation occurs in a sibling temporary directory. After successful decompilation,
error/failure checks, output validation, and hashing, mc-recon transactionally swaps the
complete source tree and deterministic `decompile.json`, retaining backups until both
new artifacts are in place. A failed engine run never replaces the previous valid tree.

## Inputs and classpath

The source artifact comes from `VersionResolver.resolveClientInputsCached`, so Mojang's
declared SHA-1 and size are checked again before use. The same cache-only path verifies
the applicable libraries selected by Mojang rule semantics.

External libraries preserve manifest/library artifact order. `STANDARD` artifacts are
included. A primary artifact whose Maven coordinate itself has a classifier remains
included as `CLASSIFIER`, matching the existing runtime classpath semantics. Legacy
classifier artifacts selected through a manifest `natives` map have role `NATIVE` and
are excluded. Maven POMs and transitive resolution are never consulted.

The selected Java executable must match the manifest's required major version. Its Java
home is passed to `include-runtime`, giving Vineflower the selected runtime's JRT modules
without adding mc-recon's ambient process classpath. Provenance records the manifest
component, major version, and normalized `java -version` identity, never the local path.

Modern published server artifacts are outer bundlers. Until the resolver exposes the
inner dedicated-server implementation JAR, `--side server` fails explicitly rather than
mislabeling launcher output as server source.

## Effective Vineflower configuration

Every core Vineflower option is materialized into a sorted map and persisted in
`decompile.json`; defaults are therefore visible rather than implicit. The local Java
home is represented in provenance by the stable token `selected-java-runtime` and is
bound to the recorded runtime identity. The complete logical configuration is:

```text
__dump_original_lines__=0
__unit_test_mode__=0
ascii-strings=0
banner=
boolean-as-int=1
bytecode-source-mapping=0
decompile-assert=1
decompile-complex-constant-dynamic=0
decompile-enums=1
decompile-finally=1
decompile-generics=1
decompile-inner=1
decompile-java4=1
decompile-preview=1
decompile-switch-expressions=1
decompiler-comments=1
dump-bytecode-on-error=0
dump-code-lines=0
dump-exception-on-error=0
dump-text-tokens=0
ensure-synchronized-monitors=1
error-message=Please report this to the Vineflower issue tracker, at https://github.com/Vineflower/vineflower/issues with a copy of the class file (if you have the rights to distribute it!)
excluded-classes=
explicit-generics=0
force-jsr-inline=0
hide-default-constructor=1
hide-empty-super=1
ignore-invalid-bytecode=0
include-classpath=0
include-runtime=selected-java-runtime
included-classes=
incorporate-returns=1
indent-string=   
inline-simple-lambdas=0
keep-literals=1
lambda-to-anonymous-class=0
log-level=INFO
mark-corresponding-synthetics=0
max-time-per-method=0
method-to-decompile=
new-line-separator=1
old-try-dedup=0
override-annotation=1
pattern-matching=1
preferred-line-length=160
prettify-ifs=0
remove-bridge=1
remove-empty-try-catch=1
remove-getclass=1
remove-imports=0
remove-synthetic=1
rename-members=0
show-hidden-statements=0
simplify-stack=1
skip-extra-files=1
sourcefile-comments=0
synthetic-not-set=0
ternary-constant-simplification=0
ternary-in-if=0
thread-count=4
try-loop-fix=1
undefined-as-object=1
use-lvt-names=1
use-method-parameters=1
validate-inner-classes-names=1
verify-anonymous-classes=0
verify-merges=0
verify-pre-post-merges=0
warn-inconsistent-inner-attributes=1
```

The fixed worker count removes host CPU count from configuration identity. Literal
resugaring, if prettification, ternary-condition folding, and brace-only lambda inlining
are disabled to reduce cosmetic reshaping. Modern Java constructs, signatures, declared
names, records/sealed metadata handled by Vineflower, pattern matching, preview features,
and switch expressions remain enabled. Invalid bytecode is not ignored. Exception and
bytecode dumps are kept out of source so machine-specific failure details cannot leak
into raw Java; failures remain diagnostics instead.

## Provenance and reuse

`decompile.json` is deterministic, schema-versioned JSON containing:

- Minecraft version and side;
- target Mojang SHA-1, computed SHA-256, and byte size;
- Vineflower version and the complete option map above;
- the ordered external libraries with manifest identity, role/classifier, Mojang SHA-1,
  computed SHA-256, and byte size;
- selected Java runtime context;
- target class count, emitted Java-file count, failed classes, retained warnings/errors,
  and the source-tree SHA-256; and
- mc-recon and decompilation-format versions.

There are no timestamps, usernames, absolute paths, temporary names, or file metadata.
Warnings and errors are sorted, deduplicated, and known input/output paths are replaced
with stable labels. Any Vineflower error or reported failed class makes the run fail
before publication.

A cached result is reusable only when schema, decompilation format, target identity,
ordered dependencies, runtime identity, Vineflower version, and every option match. The
stored source file count and tree hash are recomputed before reuse. `--force` bypasses
reuse but retains the same publication checks.

## Source-tree fingerprint

Fingerprint schema `mc-recon-source-tree-v1` hashes only relative paths and exact file
bytes:

1. Start SHA-256 with ASCII `mc-recon-source-tree-v1` followed by one NUL byte.
2. Express every regular-file path relative to `raw-source` with `/` separators.
3. Sort paths lexically by unsigned UTF-8 bytes.
4. For each file, append the path-byte length as an eight-byte big-endian integer, the
   UTF-8 path bytes, the file-byte length in the same integer form, and the exact bytes.
5. Finish SHA-256 and encode it as lowercase hexadecimal.

Timestamps, permissions, directory entries, ZIP metadata, and filesystem iteration order
do not participate.

## Known limitations

- The output is uncompiled raw decompiler source; known Vineflower type/generic/cast
  defects are intentionally retained for the later compile-to-green milestone.
- One Java unit can represent a root plus nested, local, anonymous, and lambda classfiles,
  so Java-file count is not expected to equal class count.
- Completeness uses the target JAR class inventory together with Vineflower's explicit
  failed-class/error diagnostics. The binary index remains an independent oracle.
- Runtime suitability is established at the required major version, not by identifying
  Mojang's exact compiler JDK.
