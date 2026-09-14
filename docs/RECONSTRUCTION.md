# Source reconstruction and compilation

`mc-recon` keeps the binary, raw decompiler evidence, reconstructed source, and
compiled output as separate layers:

```text
official client.jar
        |
        v
raw-source/             immutable Vineflower evidence
        |
        v
reconstructed-source/   attributable source repairs
        |
        v
classes/                javac 25 output
```

Reconstruction never writes beneath `raw-source`. It copies the complete raw tree to
a sibling staging directory, applies the known rule and patch set, verifies that the
raw tree hash is unchanged, and transactionally publishes the result. A failed run
does not replace the last valid reconstructed tree. No formatter, import organizer,
mapping tool, or modding toolchain participates.

## Commands and layout

After resolving and decompiling a client:

```bash
mc-recon resolve fixtures/26.3-rc-2.json --compile-libraries
mc-recon decompile fixtures/26.3-rc-2.json --side client
mc-recon compile fixtures/26.3-rc-2.json --side client --raw --diagnostics
mc-recon reconstruct fixtures/26.3-rc-2.json --side client
mc-recon compile fixtures/26.3-rc-2.json --side client
```

`compile --raw` is an evidence-gathering mode. It compiles the pristine Vineflower
tree into a temporary directory, persists `raw-compile-report.json`, and never
publishes its classes. Normal `compile` first validates/reuses reconstruction and then
publishes classes only after a successful zero-error compilation. `--force` bypasses
the relevant successful-output cache. `--diagnostics` prints the complete structured
diagnostic list; ordinary failure output remains concise.

The default layout is:

```text
.mc-recon/reconstruction/<version>/<side>/
    raw-source/
    decompile.json
    raw-compile-report.json
    reconstructed-source/
    reconstruction.json
    classes/
    compile-report.json
    compiler-profiles/
        <profile-id>/
            classes/
            compile-report.json
            verification.json
        toolchain-probe.json
```

Resources and JAR packaging are deliberately outside this milestone.

## Compiler and classpath

Compilation uses the standard JDK Compiler API rather than parsing console output.
This provides stable diagnostic codes and structured source, line, column, severity,
and message fields. The JDK running `mc-recon` must be the selected manifest Java
major (Java 25 for 26.3-rc-2); an incompatible `--java` is rejected because an
in-process compiler cannot be borrowed from a different executable.

The effective logical options are:

```text
--release 25
-encoding UTF-8
-proc:none
-Xmaxerrs 100000
-Xmaxwarns 100000
-classpath <resolved-manifest-artifacts>
-d <class-output>
```

The classpath comes only from verified primary Java artifacts in Mojang's version
manifest, in manifest order. It includes unclassified API JARs guarded for another
operating system when source in the complete client references them (notably the
macOS Objective-C bridge). Classifier coordinates and native payloads are excluded.
No Gradle/Maven transitive graph is resolved, and dependencies are not decompiled or
recompiled.

Annotation processing is disabled explicitly. Warnings are retained, not globally
suppressed.

Compiler archaeology uses the separate `compile-profile` and `toolchain probe`
commands. Those commands retain the same reconstructed source and resolver-verified
classpath while varying a deterministic `CompilerProfile`; they never publish over
the canonical compile output. Profile provenance adds portable JDK/JavacTool identity
and compiler-file hashes, and reuse also requires that identity and the ordered logical
options to match. See [COMPILER_FINGERPRINTING.md](COMPILER_FINGERPRINTING.md) for the
interface, isolation guarantees, complete 26.3-rc-2 matrix, and measured result.

## Transformation model

A generic `ReconstructionRule` emits non-overlapping text edits for a demonstrated
source pattern. Each change records the rule ID, relative source path, logical target,
and SHA-256 fingerprints of the replaced and replacement fragments. Rules are tested
for a positive case, a similar non-match, and idempotence.

The current generic rules are:

| Rule | Purpose | 26.3-rc-2 applications |
| --- | --- | ---: |
| `VF-ANNOTATION-001` | Remove decompiled compile-only annotations whose artifacts are absent from Mojang's manifest dependency universe | 57 |
| `VF-GENERIC-001` | Restore erased covariant `Brain<T>` return typing through a wildcard bridge cast | 20 |
| `VF-ANNOTATION-002` | Move `@Nullable` to the legal simple-name segment of a qualified nested type | 1 |
| `VF-LAMBDA-001` | Restore erased nested functional-interface targets for known lambda/method-reference declarations | 10 |

The wildcard bridge used by `VF-GENERIC-001` is intentional: the original method
descriptors and instructions return the erased `Brain` value without a target-specific
runtime cast.

### Exact patch escape hatch

Some failures have no safe file-independent textual interpretation. An
`ExactSourcePatch` therefore names one version and relative file and requires exactly
one copy of its expected context. It fails closed if the context is absent or
ambiguous. It records the same before/after fingerprints as a generic rule and is
listed separately in provenance.

For 26.3-rc-2 there are 173 exact patch applications, grouped as follows:

| Family | Applications | Why exact context is required |
| --- | ---: | --- |
| Generic/capture repairs | 86 | The necessary witness, bridge cast, or target interface depends on the declaration and invocation signatures |
| Generic inference repairs | 65 | Local type witnesses and minimally widened/narrowed expressions differ by API and method |
| Scope/name reconstruction | 12 | Vineflower merged or reused local names/scopes whose valid boundary is method-specific |
| Record reconstruction | 5 | Component/accessor/body recovery depends on the target record descriptor and bytecode |
| Lambda capture reconstruction | 2 | Later compiler phases exposed reused non-final Beacon renderer locals |
| Constructor reconstruction | 1 | `VulkanFeature` bytecode validates before `this(...)`, an ordering Java source cannot express directly |
| Pattern binding reconstruction | 1 | One decompiled pattern illegally reuses an existing binding |
| Switch reconstruction | 1 | Target enum bytecode proves `NONE` follows the unchanged/default result |

These are deliberately visible rather than hidden as arbitrary edits. Representative
ambiguous cases were checked against original descriptors, generic `Signature`
attributes, invocation/checkcast instructions, and switch bytecode using the existing
classfile inspection commands. In particular, the constructor helper preserves the
target validation-before-field-lookup behavior as closely as legal Java allows, and
the enum repair follows the original tableswitch target.

## Provenance, hashing, and reuse

`reconstruction.json` records schema and implementation/rule/patch-set versions, the
`decompile.json` identity, raw source-tree hash, every applied change, rule counts,
exact patch targets, output Java-file count, and reconstructed tree hash. Reuse
requires all relevant identities to match and the published tree to re-hash exactly.

`compile-report.json` records the source identity, compiler identity, effective
options, ordered classpath artifact identities (Mojang SHA-1, local SHA-256, and size),
source count, structured diagnostics and diagnostic-code groups, result, class count,
and class tree hash. Successful class reuse additionally requires the compiler,
options, classpath, and actual class tree to match.

Source and class tree identities hash sorted forward-slash relative paths, exact file
bytes, and explicit domain separators. They exclude timestamps, permissions, host
paths, and usernames. Source is UTF-8 with Vineflower's LF shape preserved.

## 26.3-rc-2 compile-to-green evidence

The untouched 7,301-file raw client tree produced 234 errors in 141 files and 11
warnings. The raw report retains every diagnostic grouped by javac code, source file,
line, column, and message. Root-cause classification of those 234 initial errors was:

| Root-cause family | Errors |
| --- | ---: |
| Generic inference, wildcard capture, or raw-cast loss | 118 |
| Missing compile-only annotation metadata | 57 |
| Erased covariant `Brain<T>` return typing | 20 |
| Decompiler scoping/name collision | 16 |
| Record reconstruction | 10 |
| Lambda/method-reference target typing | 10 |
| Constructor reconstruction | 1 |
| Pattern binding reconstruction | 1 |
| Qualified type-annotation placement | 1 |

The measured diagnostic progression was:

```text
raw baseline                         234 errors / 141 files
annotation/Brain/constructor layer   155 errors /  98 files
record/lambda/scope layer            121 errors /  86 files
first inference/capture layer         80 errors /  59 files
second inference/capture layer        42 errors /  25 files
codec/DataFixer layer                 14 errors /   7 files
remaining semantic layer               2 errors /   2 files
latent effective-final repairs        16 errors /   1 file
exhaustive enum switch                 1 error  /   1 file
final                                  0 errors
```

The temporary increase is genuine: javac reached flow analysis only after preceding
type errors were removed, exposing non-effectively-final lambda captures that were
not part of the initial diagnostic set.

The successful build retains 11 warnings: ten uses of APIs deprecated for removal and
one inexact non-varargs call. They are evidence for later matching work, not blockers.

## Limitations

Compile-to-green establishes compilability, not source or classfile identity. The
reconstructed source still contains decompiler-shaped control flow, raw/unchecked
constructs, synthesized legal-source bridges, and removed compile-only annotations.
The generated classfiles are deterministic under the selected JDK but are not required
to match Mojang's classfiles. Compiler fingerprinting has now constrained the best
tested Java 25 profile without identifying one historical Mojang JDK distribution;
source-shape matching, resources/JAR reproduction, and workspace generation remain
separate milestones.
