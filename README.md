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
