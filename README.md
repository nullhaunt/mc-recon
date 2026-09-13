# mc-recon

`mc-recon` is an early-stage standalone JVM tool for inspecting and, eventually,
reconstructing published Minecraft: Java Edition artifacts. Mojang's published
metadata and binaries are treated as the ground truth.

## Getting started

Build and test with Java 25:

```bash
./gradlew build
```

The build selects a Java 25 toolchain. Kotlin currently emits Java 24-compatible
classfiles, which run on Java 25. Binary indexing uses the standard JDK
`java.lang.classfile` API; no external bytecode framework is required.

Inspect a local Mojang version manifest without downloading anything:

```bash
./gradlew run --args="inspect fixtures/26.3-rc-2.json"
```

Resolve the runtime artifacts into the default `.mc-recon` cache:

```bash
./gradlew run --args="resolve fixtures/26.3-rc-2.json"
```

Use a different cache root, or explicitly download every object referenced by the
asset index:

```bash
./gradlew run --args="resolve fixtures/26.3-rc-2.json --cache-dir D:/minecraft-cache"
./gradlew run --args="resolve fixtures/26.3-rc-2.json --assets"
```

`resolve` downloads the client, server, host-applicable libraries and classifiers,
asset index, and logging configuration. Asset objects are only downloaded with
`--assets` because the complete object set is several hundred megabytes. Every cache hit
and download is checked against Mojang's declared SHA-1 and size. Downloads use
partial files and are published to their final paths only after verification.

An installed distribution provides the conceptual command directly:

```bash
./gradlew installDist
./build/install/mc-recon/bin/mc-recon inspect fixtures/26.3-rc-2.json
./build/install/mc-recon/bin/mc-recon resolve fixtures/26.3-rc-2.json
```

The manifest parser retains Mojang's ordered rules and conditional argument
structures. Library applicability is evaluated for the current operating system,
architecture, and OS version; feature flags default to false for `inspect`.

## Runtime commands

Build and inspect the exact argument vector without starting Minecraft:

```bash
./gradlew run --args="launch-command fixtures/26.3-rc-2.json client --demo"
./gradlew run --args="launch-command fixtures/26.3-rc-2.json server"
```

Run the original verified artifacts with inherited terminal input and output:

```bash
./gradlew run --args="run fixtures/26.3-rc-2.json client --demo"
./gradlew run --args="run fixtures/26.3-rc-2.json server"
```

Runtime commands are cache-only: they never download implicitly. A client run checks
every object in the asset index first; resolve missing objects explicitly with
`resolve <manifest> --assets`. The default client and server game directories live
under `.mc-recon/runtime/<version>/<platform>/run/`, never the user's normal
`.minecraft` directory. Use `--cache-dir` and `--game-dir` to override these paths.

The selected Java executable is `--java <path>` when supplied, otherwise the Java
runtime currently executing `mc-recon`. Its major version must exactly match the
manifest (Java 25 for `26.3-rc-2`); `--allow-java-version-mismatch` is an explicit
escape hatch. Client default-user JVM arguments come from the manifest and can be
disabled with `--no-default-jvm-args`. Effective client ordering is default-user JVM
arguments, manifest JVM arguments, manifest logging argument, main class, then
manifest game arguments.

Client commands without session options use clearly synthetic, unauthenticated
placeholder values; `--username` may be supplied independently. `--demo` additionally
enables the manifest's demo feature and `--demo` game argument. Authentication is
intentionally not implemented. Advanced callers may independently override the
ephemeral `--username`, `--uuid`, and `--access-token` fields (plus optional
`--client-id` and `--xuid`); they are not persisted and access tokens are redacted by
`launch-command`. Native extraction is content-keyed, excludes unsafe ZIP paths and
`META-INF`, and is reused only while its source hashes and extracted outputs match.
Legacy artifacts selected through a manifest `natives` map are extracted and omitted
from the normal classpath. A classifier supplied as a library's primary
`downloads.artifact` remains on the classpath because that placement is explicit in
modern Mojang metadata.

The server command executes the published JAR with `nogui` by default. It never
creates or changes `eula.txt`; accepting Mojang's EULA remains an explicit user
action in the selected server directory. Additional server arguments can be passed
after `--`.

## Binary archaeology

Index an already-resolved client artifact directly from its manifest:

```bash
./gradlew run --args="index fixtures/26.3-rc-2.json --side client"
```

Compare two resolved versions, as concise text or structured JSON:

```bash
./gradlew run --args="diff-bin fixtures/26.3-rc-1.json fixtures/26.3-rc-2.json --side client"
./gradlew run --args="diff-bin fixtures/26.3-rc-1.json fixtures/26.3-rc-2.json --side client --format json"
```

Add `--details` to text output to inspect every added, removed, or implementation-changed
method. The report renders JVM instructions with symbolic class/member references,
canonical branch targets, logical constants, exception regions, and recursively expanded
`invokedynamic`/constant-dynamic bootstrap handles and arguments:

```bash
./gradlew run --args="diff-bin fixtures/26.3-rc-1.json fixtures/26.3-rc-2.json --side client --details"
```

Detailed inspection is on demand and does not change or enlarge the persisted index.
An unchanged call target that is relevant to an investigation can be inspected directly
by full JVM identity:

```bash
./gradlew run --args="inspect-method fixtures/26.3-rc-2.json --side client --class net.minecraft.util.Util --method nonCriticalIoPool --descriptor ()Lnet/minecraft/TracingExecutor;"
```

These commands are cache-only and never download artifacts. Resolve a missing artifact
explicitly with `mc-recon resolve <manifest>`. Deterministic, schema-versioned indexes
are stored as compressed JSON at `.mc-recon/indexes/<version>/<side>.json.gz` and are
reused only while the source artifact identity still matches.

Every archive entry and class has an exact SHA-256, while separate structural, member,
method-code, and debug fingerprints answer what changed without confusing constant-pool
layout or debug tables with executable changes. Indexing operates on JAR/classfile models
and performs no decompilation, mapping, or source generation. See
[`docs/classfile-index.md`](docs/classfile-index.md) for the precise fingerprint semantics.

`--side server` currently indexes Mojang's published server distribution JAR as an
ordinary JAR. Modern releases use an outer bundler, so this is deliberately labeled as
the distribution/bundler and is not claimed to be the extracted dedicated-server
implementation.
