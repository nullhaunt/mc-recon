# Minecraft Reconstruction Project Plan

> Working name: **mc-recon**

## Overview

`mc-recon` is a reconstruction and software-archaeology toolchain for **Minecraft: Java Edition 26.1+**.

The project has two primary goals:

1. Reconstruct Mojang-published Minecraft versions into **compilable, runnable IntelliJ/Gradle workspaces**, while working toward byte-for-byte matching classfiles.
2. Build a **stable historical source archive** that makes it possible to trace official patch notes, bug fixes, and behavioral changes directly to their implementation.

The distributed Mojang artifacts are the ground truth.

Decompiler output is not assumed to be Mojang's original source code. It is a reconstruction whose accuracy can be measured against the published binaries.

The ideal reconstruction pipeline is:

```text
Version Manifest
       │
       ▼
Official Mojang Artifacts
       │
       ▼
Reconstructed Source
       │
       ▼
      javac
       │
       ▼
Rebuilt Classfiles
       │
       ▼
Compare Against Mojang
```

The historical pipeline is:

```text
26.3-rc-1 ───────────────► 26.3-rc-2
    │                           │
    │       Binary Diff         │
    └────────────┬──────────────┘
                 │
                 ▼
           Source Change
                 │
                 ▼
             MC-311782
```

---

# Goals

## 1. Runnable Development Workspaces

Given a Mojang version manifest, generate a normal Java project that:

- compiles in Gradle;
- imports cleanly into IntelliJ IDEA;
- launches the client;
- launches the dedicated server;
- contains reconstructed vanilla Minecraft source;
- allows direct source modification;
- does not depend on Forge, Fabric, NeoForge, Quilt, Loom, MCPConfig, or another modding framework.

The generated project should feel as close as practical to working on Minecraft itself rather than developing a conventional mod.

---

## 2. Matching Reconstruction

Work toward reconstructing source that produces the same classfiles Mojang distributed.

The long-term target is:

```text
reconstructed Foo.java
        │
        ▼
       javac
        │
        ▼
 rebuilt Foo.class
        │
        ▼
byte-for-byte identical
        │
        ▼
 Mojang Foo.class
```

Exact matching gives the reconstructed source a strong objective correctness criterion.

---

## 3. Historical Source Archive

Generate a stable Git history representing successive Minecraft versions.

This should allow normal Git workflows such as:

```bash
git diff 26.3-rc-1..26.3-rc-2

git log -p -- \
    src/client/java/net/minecraft/SomeClass.java

git blame \
    src/client/java/net/minecraft/SomeClass.java
```

The history should minimize decompiler noise so that unchanged binaries generally correspond to unchanged reconstructed source.

---

## 4. Patch-Note Archaeology

Connect official release notes and Mojira issues with corresponding binary and source changes.

For example:

```text
Minecraft 26.3 Release Candidate 2

MC-311782
"Clicking a button that opens a folder now freezes
the game until the folder is opened."
```

The project should eventually answer:

```bash
mc-recon bug MC-311782
```

with information such as:

```text
Fixed in:
    26.3-rc-2

Compared against:
    26.3-rc-1

Changed classes:
    ...

Changed methods:
    ...

Probable implementation:
    ...

Source diff:
    ...

Confidence:
    HIGH
```

Associations between issues and code should be evidence-based rather than silently inferred.

---

# Initial Scope

The first implementation should intentionally remain narrow.

## Supported

- Minecraft: Java Edition **26.1+**
- client
- dedicated server
- official Mojang manifests
- official Mojang libraries
- assets
- native libraries
- release candidates
- snapshots
- releases
- Java runtime versions declared by Mojang
- Windows, Linux, and macOS artifact rules

## Initially Unsupported

- Minecraft versions before 26.1
- Forge
- NeoForge
- Fabric
- Quilt
- Loom
- MCPConfig
- arbitrary mappings
- mod-loader integration
- distributing generated Minecraft source
- reproducing Mojang's internal repository structure
- exact JAR hashes before classfile reproduction is mature

Pre-26.1 versions can later be implemented as a separate mapped-artifact importer.

---

# Design Principles

## The Binary Is the Oracle

The published Mojang classfile is the authoritative reference.

Source reconstruction is judged against it.

A source file that looks attractive but produces substantially different bytecode is less useful to the reconstruction project than a less elegant source representation that exactly reproduces the target.

---

## Preserve Provenance

Every generated artifact should be traceable to its inputs.

Record at least:

```text
Minecraft version
version manifest hash
client/server artifact hashes
dependency hashes
asset index
decompiler version
decompiler configuration
JDK distribution
JDK version
javac arguments
reconstruction transformations
verification result
```

---

## Avoid Unnecessary Modding Infrastructure

The tool should use ordinary Java concepts wherever possible.

Minecraft 26.1+ does not require an obfuscation-remapping ecosystem merely to make the vanilla source understandable.

The generated development environment should therefore remain deliberately boring:

```text
Java
Gradle
IntelliJ
Mojang libraries
Minecraft source
```

---

## Never Silently Repair Decompiled Source

Raw decompiler output should remain available.

If reconstruction requires changing it, the change should be deterministic and attributable to a known transformation.

For example:

```text
VF-RECORD-001
VF-CAST-003
JAVAC-SHAPE-007
```

This allows us to distinguish:

```text
what Vineflower produced
```

from:

```text
what mc-recon changed to restore compilability
```

from:

```text
what was changed to reproduce Mojang's bytecode
```

---

## Separate Reconstruction From Historical Presentation

There are potentially two different representations of the code.

### Reconstruction Source

Optimized for:

- compilation;
- classfile equivalence;
- byte-for-byte matching.

### Historical Source

Optimized for:

- readability;
- stable diffs;
- code archaeology.

Initially these may be identical.

They should nevertheless be treated as conceptually different products so bytecode-matching quirks do not eventually destroy the quality of historical diffs.

---

# Architecture

A tentative module structure:

```text
mc-recon/
├── cli/
│
├── manifest/
│   ├── version parsing
│   ├── Mojang rule evaluation
│   └── OS / architecture handling
│
├── resolver/
│   ├── downloads
│   ├── caching
│   ├── hash verification
│   ├── libraries
│   ├── assets
│   └── natives
│
├── launcher/
│   ├── original client
│   ├── rebuilt client
│   ├── original server
│   └── rebuilt server
│
├── archive/
│   ├── JAR inspection
│   ├── resource extraction
│   └── server bundle extraction
│
├── classfile/
│   ├── class indexing
│   ├── member indexing
│   ├── fingerprints
│   └── structural diffing
│
├── decompiler/
│   └── Vineflower integration
│
├── reconstruction/
│   ├── transformations
│   └── compile-fix passes
│
├── compiler/
│   ├── javac invocation
│   └── toolchain profiles
│
├── verifier/
│   ├── exact comparison
│   ├── normalized comparison
│   └── reports
│
├── history/
│   ├── version graph
│   ├── incremental reconstruction
│   └── Git generation
│
├── changelog/
│   ├── release-note ingestion
│   └── Mojira metadata
│
├── database/
│   └── SQLite provenance/index
│
└── workspace/
    ├── Gradle generation
    └── IntelliJ-compatible project generation
```

The decompiler should not become the center of the architecture.

The most important long-term components are likely:

```text
resolver
classfile
verifier
history
```

---

# Phase 1 — Manifest Resolver

The first target fixture should be:

```text
26.3-rc-2.json
```

Implement complete handling of Mojang's version metadata.

Required functionality:

- version identity;
- client download;
- server download;
- libraries;
- library rules;
- operating-system restrictions;
- architecture restrictions;
- native classifiers;
- assets;
- logging configuration;
- JVM arguments;
- game arguments;
- main class;
- required Java version;
- SHA-1 validation.

Example cache structure:

```text
.mc-recon/
├── objects/
├── libraries/
├── versions/
├── assets/
└── natives/
```

Artifacts should be immutable once their hashes have been validated.

### Command

```bash
mc-recon resolve 26.3-rc-2.json
```

### Exit Criteria

All required artifacts can be downloaded or located locally and validate against Mojang's declared hashes.

---

# Phase 2 — Reproduce the Official Runtime

Before decompiling Minecraft, verify that the manifest has been interpreted correctly.

Commands:

```bash
mc-recon run 26.3-rc-2 --original-client

mc-recon run 26.3-rc-2 --original-server
```

The launcher should synthesize the required JVM invocation from Mojang's metadata.

For debugging:

```bash
mc-recon launch-command 26.3-rc-2
```

should print the generated invocation.

### Exit Criteria

The original Mojang client and server can be launched through `mc-recon`.

This proves that:

- dependencies are correct;
- native extraction is correct;
- classpaths are correct;
- JVM arguments are correct;
- game arguments are correctly constructed.

---

# Phase 3 — Binary and Classfile Index

Before using a decompiler, analyze the published artifacts directly.

For every JAR entry, record:

```text
path
size
SHA-1
SHA-256
entry type
```

For every `.class` file, record:

```text
class name
classfile version
access flags
superclass
interfaces
fields
methods
attributes
```

For every method:

```text
name
descriptor
generic signature
access flags
code hash
exception table
bootstrap dependencies
```

Create multiple fingerprints:

```text
rawHash
classStructureHash
methodCodeHash
normalizedCodeHash
```

### Example

```bash
mc-recon diff-bin 26.3-rc-1 26.3-rc-2
```

Output:

```text
Minecraft 26.3-rc-1 -> 26.3-rc-2

Added classes:       0
Removed classes:     0
Changed classes:     N
Changed methods:     N
Changed resources:   N
```

### Exit Criteria

Useful release-to-release change reports can be produced without decompiling either release.

---

# Phase 4 — MC-311782 Case Study

Use the transition:

```text
26.3-rc-1
    ↓
26.3-rc-2
```

as the first software-archaeology fixture.

Determine:

- which JAR entries changed;
- which classes changed;
- which fields changed;
- which methods changed;
- which method bodies changed;
- which constants changed;
- which resources changed.

Then determine which changes correspond to:

```text
MC-311782
Clicking a button that opens a folder now freezes
the game until the folder is opened
```

Only the relevant changed classes need to be decompiled initially.

### Exit Criteria

The implementation change corresponding to MC-311782 can be identified and explained from the published binaries.

This validates the historical-research concept before building the full source reconstruction pipeline.

---

# Phase 5 — Vineflower Integration

Integrate a pinned Vineflower release programmatically.

Decompiler inputs should include:

- Minecraft artifact;
- complete dependency classpath;
- correct JDK environment;
- deterministic configuration.

Keep raw decompiler output separate:

```text
build/reconstruction/raw-source/
```

Reconstruction output belongs elsewhere:

```text
build/reconstruction/source/
```

Raw output should never be silently overwritten by reconstruction transformations.

### Exit Criteria

A complete source tree can be produced deterministically.

Running the decompiler twice against identical inputs should produce identical source.

---

# Phase 6 — Compile-to-Green

Compile the reconstructed source using the dependencies identified from Mojang's artifacts.

Initial objective:

```text
decompiled source
       │
       ▼
      javac
       │
       ▼
successful compilation
```

Failures should be categorized.

Potential categories:

```text
Decompiler syntax error
Decompiler type-inference error
Constructor reconstruction
Synthetic construct
Generic-signature reconstruction
Record reconstruction
Compiler-version mismatch
Unknown
```

Avoid accumulating unexplained hand-edited patches.

Every fix should eventually become either:

- a deterministic reconstruction transformation; or
- an explicit reconstruction patch with provenance.

### Exit Criteria

The reconstructed client and dedicated server compile successfully.

---

# Phase 7 — Runnable Reconstructed Workspace

Generate an ordinary Gradle project.

Expected tasks:

```bash
./gradlew runClient
./gradlew runServer
```

The generated Gradle project should consume reconstructed source.

It should not need to perform the entire reconstruction process during every Gradle import.

Conceptually:

```text
mc-recon
    │
    ├── decompiles
    ├── reconstructs
    └── generates project
              │
              ▼
          Gradle project
              │
              ▼
           IntelliJ
```

### Exit Criteria

A user can:

1. generate the workspace;
2. open it in IntelliJ;
3. modify a Minecraft source file;
4. press Run;
5. observe the changed behavior.

At this point the personal-research and source-modding objective is functional.

---

# Phase 8 — Verification System

Every reconstructed class should receive a verification status.

Proposed states:

```text
EXACT
STRUCTURAL
FUNCTIONAL
COMPILES
UNRESOLVED
```

## EXACT

```text
rebuilt .class == Mojang .class
```

Byte-for-byte identical.

## STRUCTURAL

The normalized classfile representation matches, but serialization details differ.

Possible causes include:

- constant-pool ordering;
- debug attributes;
- other non-semantic representation differences.

## FUNCTIONAL

Compilation succeeds and behavior appears equivalent, but substantial bytecode differences remain.

## COMPILES

The source compiles, but equivalence has not yet been established.

## UNRESOLVED

The source does not compile or contains a known reconstruction problem.

Example report:

```text
Minecraft 26.3-rc-2

Classes:         12,000

EXACT             9,300   77.50%
STRUCTURAL        1,900   15.83%
FUNCTIONAL          550    4.58%
COMPILES            225    1.88%
UNRESOLVED           25    0.21%
```

The figures above are illustrative.

### Exit Criteria

Every class has a verification status and a useful mismatch report.

---

# Phase 9 — Compiler Fingerprinting

The runtime Java version does not necessarily identify Mojang's exact compiler toolchain.

Build a system to test candidate compiler profiles.

Variables include:

```text
JDK distribution
JDK patch release
javac executable
debug information
parameter metadata
--release
-source
-target
annotation processing
other compiler switches
```

Command:

```bash
mc-recon toolchain probe 26.3-rc-2
```

Possible report:

```text
Candidate                          Exact classes
------------------------------------------------
JDK profile A                         71.3%
JDK profile B                         88.9%
JDK profile C                         99.1%
```

Persist discovered profiles:

```json
{
  "minecraft": "26.3-rc-2",
  "compiler": "...",
  "java": "25.0.x",
  "flags": [
    "..."
  ],
  "confidence": 0.997
}
```

### Exit Criteria

A pinned compiler profile substantially improves exact classfile reproduction.

---

# Phase 10 — Git History Generator

Successive reconstructed versions should form one evolving repository.

Example tags:

```text
26.3-pre-3
26.3-rc-1
26.3-rc-2
26.3
```

Each generated commit should record:

```text
Minecraft version
release timestamp
manifest hash
client hash
server hash
verification summary
official changelog source
```

The result should work naturally with Git:

```bash
git diff 26.3-rc-1..26.3-rc-2

git log -p -- \
    src/client/java/net/minecraft/client/Minecraft.java
```

### Critical Rule

Do **not** independently redecompile the entire game for every version.

That would make the archive vulnerable to irrelevant decompiler output changes.

---

# Phase 11 — Incremental Historical Reconstruction

Historical reconstruction should operate from the previous version.

Given:

```text
Version N
Version N+1
```

apply:

```text
If class binary is unchanged:
    preserve previous reconstructed source verbatim.

If class binary changed:
    reconstruct the changed source family.

If resource is unchanged:
    preserve it verbatim.
```

A source family may include:

```text
Foo.java
Foo.class
Foo$Inner.class
Foo$1.class
Foo$2.class
```

These classfiles originate from one compilation unit and should generally be considered together.

### Exit Criteria

Small Mojang binary changes generally produce small source-level Git diffs.

---

# Phase 12 — Release Notes and Issue Integration

Collect official release-note metadata.

Store entries similar to:

```text
version
category
issue_id
summary
source
```

Then associate release changes with source and binary changes.

Example:

```text
MC-311782

Fixed in:
    26.3-rc-2

Release delta:
    N changed classes

Probable implementation:
    net.minecraft....
    method(...)

Association:
    HIGH_CONFIDENCE
```

Use explicit confidence values:

```text
CONFIRMED
HIGH_CONFIDENCE
INFERRED
UNKNOWN
```

Commands:

```bash
mc-recon bug MC-311782

mc-recon notes 26.3-rc-2

mc-recon diff \
    --notes \
    26.3-rc-1 \
    26.3-rc-2
```

---

# Phase 13 — Research Database

Git is an excellent source-history interface.

It is not sufficient for all structured queries.

Maintain a SQLite database alongside generated history.

Proposed tables:

```text
versions
artifacts
jar_entries
classes
members
resources
source_units
verification
release_notes
issues
issue_associations
toolchains
transformations
```

This allows queries such as:

```bash
mc-recon history \
    net.minecraft.SomeClass.someMethod

mc-recon introduced \
    net.minecraft.SomeClass.someMethod

mc-recon changed \
    --package net.minecraft.client.renderer \
    --since 26.1
```

Potential future queries:

```text
Which versions changed chunk serialization?

When was this method introduced?

Which release removed this field?

Which snapshots modified this rendering subsystem?

Which classes changed when this bug was fixed?
```

---

# Phase 14 — Shifted Development Builds

Once a version has been reconstructed and verified, users should be able to intentionally diverge from Mojang's version.

Conceptually:

```bash
mc-recon setup 26.3

mc-recon verify

mc-recon shift
```

The workspace is now a development build.

Verification should distinguish intentional changes from unintended rebuild differences.

Example:

```text
Expected modified classes:
    net.minecraft.SomeClass

Unexpected modified classes:
    none
```

If changing one source file unexpectedly changes hundreds of unrelated classfiles, the toolchain is not sufficiently deterministic.

This makes the shift workflow a useful regression test for the reconstruction system itself.

---

# Phase 15 — Archive-Exact Reconstruction

Exact JAR reproduction should be a late-stage objective.

The target becomes:

```text
SHA-1(rebuilt client.jar)
    ==
SHA-1(Mojang client.jar)
```

This requires reproducing details beyond Java compilation:

- ZIP entry ordering;
- timestamps;
- compression methods;
- compression settings;
- manifest serialization;
- resource metadata;
- potentially the original ZIP implementation behavior.

Classfile-exact reproduction is more important initially.

Archive-exact reproduction should therefore be treated as a later milestone.

---

# Generated Workspace

A generated project might resemble:

```text
minecraft-26.3-rc-2/
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew
├── gradle/
│
├── reconstruction.lock.json
│
├── client/
│   └── src/
│       └── main/
│           ├── java/
│           └── resources/
│
├── server/
│   └── src/
│       └── main/
│           ├── java/
│           └── resources/
│
├── run/
│   ├── client/
│   └── server/
│
└── .mc-recon/
    ├── reference/
    ├── reports/
    ├── patches/
    └── verification/
```

Avoid generated `.idea/` files unless they prove necessary.

IntelliJ should import the standard Gradle model.

---

# Reconstruction Lockfile

Each generated project should contain something similar to:

```text
reconstruction.lock.json
```

It should identify:

```text
Minecraft version
manifest hash
client hash
server hash
asset index
dependency hashes
Vineflower version
Vineflower options
JDK distribution
JDK version
javac arguments
reconstruction transformation version
```

The purpose is to make reconstruction experiments independently repeatable.

---

# Proposed CLI

## Artifact Handling

```bash
mc-recon inspect VERSION

mc-recon resolve VERSION
```

## Runtime Validation

```bash
mc-recon run VERSION --original-client

mc-recon run VERSION --original-server

mc-recon launch-command VERSION
```

## Reconstruction

```bash
mc-recon decompile VERSION

mc-recon build VERSION

mc-recon verify VERSION
```

## Workspace

```bash
mc-recon setup VERSION

mc-recon run VERSION
```

## Binary Research

```bash
mc-recon diff-bin VERSION_A VERSION_B
```

## Historical Research

```bash
mc-recon diff VERSION_A VERSION_B

mc-recon history RANGE

mc-recon bug MC-311782

mc-recon notes VERSION
```

## Symbol Research

```bash
mc-recon query SYMBOL

mc-recon history SYMBOL

mc-recon introduced SYMBOL
```

## Toolchain Research

```bash
mc-recon toolchain probe VERSION
```

## Development

```bash
mc-recon shift VERSION
```

The exact command syntax can evolve, but designing against the expected user experience will expose architectural requirements early.

---

# Implementation Stack

The primary application should likely be written in **Kotlin on the JVM**.

Advantages include straightforward interoperability with:

- Vineflower;
- ASM;
- the JDK Class-File API;
- Gradle;
- SQLite;
- Java ZIP/JAR APIs.

Use Gradle Kotlin DSL for both:

- the `mc-recon` project;
- generated development workspaces.

The primary product should be a standalone CLI.

```text
Standalone CLI       YES

Gradle plugin         NO, initially
Mod loader            NO
IntelliJ plugin       NO, initially
```

Additional integrations can be developed later without making them architectural dependencies.

---

# Milestones

## M0 — Binary Archaeology

No decompiler is required.

### Requirements

- [ ] Parse `26.3-rc-2` manifest
- [ ] Download/cache required artifacts
- [ ] Verify Mojang hashes
- [ ] Evaluate library rules
- [ ] Resolve natives
- [ ] Resolve assets
- [ ] Launch original client
- [ ] Launch original server
- [ ] Index client JAR
- [ ] Index server artifacts
- [ ] Fingerprint every class
- [ ] Fingerprint every method
- [ ] Load `26.3-rc-1`
- [ ] Generate RC1 → RC2 binary diff
- [ ] Identify MC-311782 implementation delta

### Purpose

M0 proves that:

1. Mojang's published metadata can drive our runtime;
2. our artifact model is correct;
3. binary history can be generated without decompilation;
4. class/member-level differencing is useful;
5. patch-note archaeology is practical.

---

## M1 — Runnable Reconstruction

### Requirements

- [ ] Deterministic Vineflower integration
- [ ] Raw decompiler source preservation
- [ ] Reconstruction transformation system
- [ ] Compile all client source
- [ ] Compile all server source
- [ ] Generate ordinary Gradle workspace
- [ ] Import into IntelliJ
- [ ] Launch reconstructed client
- [ ] Launch reconstructed server
- [ ] Support source modification

### Purpose

M1 satisfies the basic research and personal source-modding objective.

---

## M2 — Matching Reconstruction

### Requirements

- [ ] Classfile verifier
- [ ] Verification classifications
- [ ] Method-level mismatch reports
- [ ] Compiler fingerprinting
- [ ] Toolchain profiles
- [ ] Exact-source reconstruction workflow
- [ ] Increasing EXACT percentage
- [ ] Verification regression tests

### Purpose

M2 turns the project into a matching decompilation effort rather than merely a source generator.

---

## M3 — Minecraft Chronicle

### Requirements

- [ ] Incremental source history
- [ ] Git history generation
- [ ] Stable unchanged source
- [ ] SQLite research index
- [ ] Release-note ingestion
- [ ] Mojira issue metadata
- [ ] Issue/change associations
- [ ] Symbol history
- [ ] Release comparison reports

### Purpose

M3 turns the project into a software-archaeology platform.

It should answer questions such as:

```text
What code changed when Mojang fixed MC-311782?

When was this renderer method introduced?

Which releases modified chunk serialization?

Which snapshot introduced this field?

Was this source change real, or just decompiler noise?

What changed alongside this patch-note entry?
```

---

# First Vertical Slice

The initial implementation should deliberately avoid Vineflower.

Start with:

```text
fixtures/
└── 26.3-rc-2.json
```

Implement:

```bash
mc-recon inspect fixtures/26.3-rc-2.json
```

Example output:

```text
Minecraft 26.3-rc-2

Type:          snapshot
Java:          25

Client:
  size:        ...
  SHA-1:       ...
  URL:         ...

Server:
  size:        ...
  SHA-1:       ...
  URL:         ...

Libraries:
  total:       ...
  applicable:  ...

Assets:
  index:       34

Client main:
  net.minecraft.client.main.Main
```

Then implement:

```bash
mc-recon resolve fixtures/26.3-rc-2.json
```

followed by:

```bash
mc-recon run 26.3-rc-2 --original-client
```

then:

```bash
mc-recon inspect-jar 26.3-rc-2
```

then:

```bash
mc-recon diff-bin 26.3-rc-1 26.3-rc-2
```

The first development sequence should therefore be:

```text
manifest parser
      │
      ▼
artifact resolver
      │
      ▼
original launcher
      │
      ▼
classfile index
      │
      ▼
RC1 → RC2 binary diff
      │
      ▼
MC-311782 case study
      │
      ▼
decompiler
      │
      ▼
compiler
      │
      ▼
verifier
      │
      ▼
Git history
```

This establishes the objective data model before introducing the much noisier problems of source reconstruction.

---

# Long-Term Project Definition

`mc-recon` aims to:

> **Reconstruct and chronicle vanilla Minecraft: Java Edition 26.1+ from Mojang's published artifacts, producing runnable development workspaces, measurable binary reproductions, and a stable source history for research and personal modification.**

The final product should combine characteristics of:

- a matching decompilation project;
- a vanilla Minecraft development workspace;
- a binary-analysis tool;
- a Git source-history generator;
- and a software-archaeology database.

The central invariant remains simple:

> **Mojang's published binary is the ground truth.**