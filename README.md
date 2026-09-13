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
classfiles, which run on Java 25; no Java 25-only APIs are needed in this slice.

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
