# Editable Gradle workspaces

`mc-recon workspace` materializes a normal Gradle Java project from a validated
reconstruction. The workspace is a development copy, not another reconstruction cache:

```text
.mc-recon/reconstruction/<version>/client/reconstructed-source/  authoritative evidence
                                  |
                                  | byte-preserving copy
                                  v
<workspace>/src/main/java/                                      editable source
```

Generation never writes through links into the canonical tree. Symbolic links are rejected
while materializing source and runtime trees. The output path must be explicit and must not
already exist; there is intentionally no destructive `--force` mode.

## Generate and import

Resolve the complete cache-only input set, reconstruct, and generate:

```bash
mc-recon resolve fixtures/26.3-rc-2.json --assets --compile-libraries
mc-recon reconstruct fixtures/26.3-rc-2.json --side client
mc-recon workspace fixtures/26.3-rc-2.json \
    --side client \
    --output workspaces/26.3-rc-2
```

`--editable` is accepted for clarity but is the default and currently the only mode. Open the
output directory as a Gradle project in IntelliJ IDEA. mc-recon does not generate `.idea/` or
`.iml` metadata.

The generated project contains:

```text
build.gradle.kts
settings.gradle.kts
gradle.properties
gradlew / gradlew.bat / gradle/wrapper/
reconstruction.lock.json
workspace-manifest.json
src/main/java/             editable reconstructed source
src/main/resources/        unmodified client JAR resources
.mc-recon/libraries/       exact local compile/runtime artifacts
.mc-recon/assets/          launcher asset index and object store
.mc-recon/logging/         manifest logging configuration
.mc-recon/natives/         already-prepared native runtime
run/client/                isolated mutable game directory
```

## Gradle and Java

The wrapper is pinned to Gradle 9.1.0, the first Gradle release supporting Java 25 for
both execution and toolchains. See Gradle's
[Java compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html)
and [9.1.0 release notes](https://docs.gradle.org/9.1.0/release-notes.html).

The project requests a Java 25 toolchain and sets Java 25 source and target compatibility.
Every `JavaCompile` uses UTF-8, annotation processing disabled, full debug information, and
parameter metadata. Gradle therefore models the winning compiler profile intentionally as:

```text
-source 25 -target 25 -encoding UTF-8 -proc:none -g -parameters
```

It does not configure `--release 25`. A 4 GiB Gradle daemon heap is configured because a
single compilation covers 7,301 reconstructed Java units.

## Exact dependencies and resources

There are no repositories in the generated build and no modding Gradle plugins. Compile and
runtime configurations use only copied, resolver-verified local files. Compile order is the
complete 54-artifact manifest API universe used by reconstruction; runtime order is the
host-applicable launch classpath produced by the existing launcher model. The original Mojang
client JAR is not a dependency. `runClient` uses Gradle's main output first, followed by the
exact runtime artifacts.

Client JAR resources are copied byte-for-byte. The extractor rejects absolute, drive-qualified,
backslash, traversal, and duplicate paths. It excludes all `.class` files and these invalid or
inapplicable original-archive entries:

```text
META-INF/MANIFEST.MF
META-INF/INDEX.LIST
META-INF/*.SF
META-INF/*.RSA
META-INF/*.DSA
META-INF/*.EC
META-INF/SIG-*
```

The Mojang asset object store is not copied into `src/main/resources`. It remains an external
launcher asset tree under `.mc-recon/assets`, addressed through the manifest asset index.

## Runtime and authentication

Workspace planning asks the existing manifest-driven client launch builder for the main class,
JVM arguments, game arguments, feature defaults, native properties, asset paths, and logging
configuration. The generated build only substitutes portable workspace paths, session values,
and Gradle's compiled runtime classpath. Native extraction logic is not duplicated in Gradle.

`runClient` uses `run/client` and defaults to demo mode with mc-recon's synthetic,
unauthenticated session. Authentication is not implemented. Optional session values can be
provided at invocation time with environment variables:

```text
MC_RECON_USERNAME
MC_RECON_UUID
MC_RECON_ACCESS_TOKEN
MC_RECON_CLIENT_ID
MC_RECON_XUID
```

or corresponding project properties such as `-PmcReconUsername=...`. Extra simple
whitespace-separated JVM arguments can be passed with `MC_RECON_JVM_ARGS` or
`-PmcReconJvmArgs=...`. Tokens are not written to generated files or lockfiles. Local session
files and common local-property filenames are ignored by the generated `.gitignore`.

## Provenance, portability, and regeneration

`reconstruction.lock.json` records the original manifest/client/source/decompiler/
reconstruction/compiler identities, both exact dependency orders, the asset index, platform,
generator version, and workspace format. It has no timestamps, usernames, host paths, or
credentials. `workspace-manifest.json` hashes the complete initial generated tree except itself
and the intentionally mutable `run/`, `.gradle/`, and `build/` directories. It describes the
starting point and is not an ongoing restriction after edits.

The first implementation is self-contained for the host platform: it copies dependencies,
assets, logging, and prepared natives to favor correctness and a reliable IntelliJ import over
disk minimization. The generated `.gitignore` excludes this `.mc-recon/` payload so Mojang
artifacts are not accidentally committed. A workspace must be regenerated for another OS or architecture because
Mojang's native selection and conditional JVM arguments are platform-specific.

Generation occurs in a sibling staging directory. Before publication mc-recon checks build
files, wrapper completeness, exact source-tree identity, resources, dependencies, assets, and
lockfile readability. Publication then moves the complete stage into a nonexistent output path.
A failed run cannot leave a directory that looks complete. Gradle compilation remains a
post-generation validation step because wrapper distribution availability is external to the
mechanical, cache-only generator.

`jar` produces a convenient development JAR with the manifest main class. It is not signed and
does not attempt classfile- or archive-exact Mojang JAR reproduction.
