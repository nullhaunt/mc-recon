# Compiler fingerprinting

This document records the controlled compiler-profile experiment for the Minecraft
`26.3-rc-2` client. Every candidate compiled the same 7,301 reconstructed Java files:

```text
source tree:  df46d69ba60a2527d26315ff85b849ebabe71b0311ff4b6bfb5125d3ea6d7862
classpath:    90b1439699bd0300bb6015fb1d615edf14f95fbe546b4498d7d97904f770214c
artifacts:    54, in resolver-verified manifest order
encoding:     UTF-8
processing:   -proc:none
```

No raw or reconstructed source, reconstruction rule or patch, Vineflower option, or
verification semantic changed during the experiment.

## Experiment interface and isolation

The generic interface accepts a stable option-profile ID:

```bash
./gradlew run --args="compile-profile fixtures/26.3-rc-2.json --side client --profile release25-g-parameters"
./gradlew run --args="toolchain probe fixtures/26.3-rc-2.json --side client"
./gradlew run --args="toolchain probe fixtures/26.3-rc-2.json --side client --profile source-target25-g-parameters"
```

An alternate JDK is selected by launching `mc-recon` under that JDK. Optional
`--java-home` is a guard: it must identify the active in-process Compiler API JDK and
cannot silently borrow a compiler from another installation.

Each materialized profile has a directory beneath:

```text
.mc-recon/reconstruction/26.3-rc-2/client/compiler-profiles/<profile-id>/
    classes/
    compile-report.json
    verification.json
```

The aggregate machine-readable report is
`compiler-profiles/toolchain-probe.json`. Candidate compilation never publishes to
the canonical `classes/`, `compile-report.json`, or `verification.json` paths. Reuse
requires an exact match on source tree, reconstruction provenance, compiler identity,
logical options, and ordered classpath identity, followed by an actual class-tree hash
check.

Compiler identity includes logical `java.home`, vendor, Java/runtime versions, VM name,
JavacTool implementation and module version, `javac -version`, and SHA-256 identities
for the Java launcher, JDK `release` file, and `jdk.compiler.jmod` or runtime modules
image. Persisted reports use `<selected-java-home>` rather than a machine-specific path.

Ranking is deterministic and lexicographic: maximize `EXACT`, then
`EXACT + STRUCTURAL`, avoid semantic-code regressions, maximize debug and method
metadata agreement, and finally maximize semantic method-code matches. The full
metrics remain available; ranking is not an opaque score.

A full compile took approximately 73 seconds on the experiment host. That was small
enough to run every current-JDK profile over all 11,383 classfiles rather than screen
on a subset. The persisted diagnostic corpus is still useful for explanation:

- `Blaze3D`: semantically matching executable code with parameter/debug differences;
- `DeviceTracker`: existing non-package `EXACT` interface;
- `BackendRenderPipeline`: `STRUCTURAL` InnerClasses/constant-pool-order example;
- `TracyBootstrap`: normalized StackMapTable difference; and
- `SetRaidStatus`: semantic-code negative control.

## Original baseline

The isolated `release25-default` profile reproduced the canonical class-tree hash
exactly, proving that profile execution did not perturb the starting point:

```text
class tree:           9b13f230d65dace0e2832607e81bec551215d5b00e6dd0bdba0dfcad9ef51d90
compile report:       d2ebf445259d52f07ad311348ad2ef44a369b62e4e2b27aa7f45d610376f3ce8
verification report:  eb29bb9e29e0a17c8aeaa7662506e03a3f02f463a406b7cdd65d0cca57e3a869

EXACT:                  707
STRUCTURAL:              18
FUNCTIONAL:           2,717
COMPILES:             7,941
semantic code:       89,110 / 95,040 executable corresponding methods
```

## Temurin 25.0.4.1 option matrix

All profiles below used Eclipse Temurin OpenJDK `25.0.4.1+1-LTS`, JavacTool
`25.0.4.1`, and the fixed inputs above. `Meta` and `Debug` are matching corresponding
methods. `Raw` is the number of semantically matching methods whose raw Code arrays
still differ. `SF` is classes with a changed SourceFile attribute. `Sem reg` is newly
semantic-code-different methods relative to the baseline.

| Option profile | EXACT | STRUCT | FUNCTIONAL | COMPILES | Semantic | Meta | Debug | Raw | SF | Sem reg |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `release25-default` | 707 | 18 | 2,717 | 7,941 | 89,110 | 51,535 | 4,332 | 22,183 | 0 | 0 |
| `release25-parameters` | 1,207 | 33 | 2,717 | 7,426 | 89,110 | 98,176 | 4,332 | 22,183 | 0 | 0 |
| `release25-g` | 864 | 14 | 2,717 | 7,788 | 89,110 | 51,535 | 12,624 | 22,183 | 0 | 0 |
| `release25-g-parameters` | 1,770 | 45 | 2,717 | 6,851 | 89,110 | 98,176 | 12,625 | 22,183 | 0 | 0 |
| `release25-g-none` | 0 | 0 | 2,566 | 8,817 | 87,802 | 50,597 | 3,312 | 23,532 | 11,383 | 376 |
| `release25-g-source` | 704 | 9 | 2,566 | 8,104 | 87,802 | 50,597 | 3,312 | 23,532 | 0 | 376 |
| `release25-g-lines` | 0 | 0 | 2,717 | 8,666 | 89,110 | 51,535 | 4,332 | 22,183 | 11,383 | 0 |
| `release25-g-vars` | 0 | 0 | 2,717 | 8,666 | 89,110 | 51,535 | 3,312 | 22,183 | 11,383 | 0 |
| `release25-g-source-lines` | 707 | 18 | 2,717 | 7,941 | 89,110 | 51,535 | 4,332 | 22,183 | 0 | 0 |
| `release25-g-source-vars` | 704 | 9 | 2,717 | 7,953 | 89,110 | 51,535 | 3,312 | 22,183 | 0 | 0 |
| `release25-g-lines-vars` | 0 | 0 | 2,717 | 8,666 | 89,110 | 51,535 | 12,624 | 22,183 | 11,383 | 0 |
| `release25-g-source-lines-vars` | 864 | 14 | 2,717 | 7,788 | 89,110 | 51,535 | 12,624 | 22,183 | 0 | 0 |
| `release25-g-source-lines-vars-parameters` | 1,770 | 45 | 2,717 | 6,851 | 89,110 | 98,176 | 12,625 | 22,183 | 0 | 0 |
| `source-target25-default` | 707 | 18 | 2,717 | 7,941 | 89,110 | 51,535 | 4,332 | 22,179 | 0 | 0 |
| `source-target25-g-parameters` | 1,770 | 45 | 2,717 | 6,851 | 89,110 | 98,189 | 12,625 | 22,179 | 0 | 0 |

Every successful profile retained the same 11 compiler warnings as the canonical
build. Warnings were recorded but were not a ranking input.

The following table reports each modeled attribute as
`normalized-different / missing / extra / present-content-changed`, all at method
granularity. Presence and normalized counts intentionally need not sum: an empty
attribute can be present or absent while both normalized content hashes are null.

| Option profile | MethodParameters | LineNumberTable | LocalVariableTable | LocalVariableTypeTable |
| --- | ---: | ---: | ---: | ---: |
| `release25-default` | 46,809 / 42,868 / 0 / 3,941 | 85,706 / 0 / 0 / 85,706 | 88,112 / 88,312 / 0 / 0 | 15,701 / 15,701 / 0 / 0 |
| `release25-parameters` | 31 / 0 / 0 / 31 | 85,706 / 0 / 0 / 85,706 | 88,112 / 88,312 / 0 / 0 | 15,701 / 15,701 / 0 / 0 |
| `release25-g` | 46,809 / 42,868 / 0 / 3,941 | 85,706 / 0 / 0 / 85,706 | 4,788 / 0 / 147 / 4,642 | 1,013 / 56 / 3 / 954 |
| `release25-g-parameters` | 31 / 0 / 0 / 31 | 85,706 / 0 / 0 / 85,706 | 4,779 / 0 / 147 / 4,633 | 1,012 / 56 / 3 / 953 |
| `release25-g-none` | 46,809 / 42,868 / 0 / 3,941 | 94,102 / 94,102 / 0 / 0 | 87,214 / 87,414 / 0 / 0 | 15,693 / 15,693 / 0 / 0 |
| `release25-g-source` | 46,809 / 42,868 / 0 / 3,941 | 94,102 / 94,102 / 0 / 0 | 87,214 / 87,414 / 0 / 0 | 15,693 / 15,693 / 0 / 0 |
| `release25-g-lines` | 46,809 / 42,868 / 0 / 3,941 | 85,706 / 0 / 0 / 85,706 | 88,112 / 88,312 / 0 / 0 | 15,701 / 15,701 / 0 / 0 |
| `release25-g-vars` | 46,809 / 42,868 / 0 / 3,941 | 95,040 / 95,040 / 0 / 0 | 4,788 / 0 / 147 / 4,642 | 1,013 / 56 / 3 / 954 |
| `release25-g-source-lines` | 46,809 / 42,868 / 0 / 3,941 | 85,706 / 0 / 0 / 85,706 | 88,112 / 88,312 / 0 / 0 | 15,701 / 15,701 / 0 / 0 |
| `release25-g-source-vars` | 46,809 / 42,868 / 0 / 3,941 | 95,040 / 95,040 / 0 / 0 | 4,788 / 0 / 147 / 4,642 | 1,013 / 56 / 3 / 954 |
| `release25-g-lines-vars` | 46,809 / 42,868 / 0 / 3,941 | 85,706 / 0 / 0 / 85,706 | 4,788 / 0 / 147 / 4,642 | 1,013 / 56 / 3 / 954 |
| `release25-g-source-lines-vars` | 46,809 / 42,868 / 0 / 3,941 | 85,706 / 0 / 0 / 85,706 | 4,788 / 0 / 147 / 4,642 | 1,013 / 56 / 3 / 954 |
| `release25-g-source-lines-vars-parameters` | 31 / 0 / 0 / 31 | 85,706 / 0 / 0 / 85,706 | 4,779 / 0 / 147 / 4,633 | 1,012 / 56 / 3 / 953 |
| `source-target25-default` | 46,809 / 42,868 / 0 / 3,941 | 85,706 / 0 / 0 / 85,706 | 88,112 / 88,312 / 0 / 0 | 15,701 / 15,701 / 0 / 0 |
| `source-target25-g-parameters` | 18 / 0 / 0 / 18 | 85,706 / 0 / 0 / 85,706 | 4,766 / 0 / 147 / 4,620 | 1,012 / 56 / 3 / 953 |

The experiments establish that javac's default debug mode is byte-identical to
`-g:source,lines` for this source. `-g` is byte-identical to
`-g:source,lines,vars`, and the corresponding parameter variants are also identical.
Options omitting `source` remove SourceFile from all 11,383 classes. Options omitting
`lines` do not merely lose line tables here: `-g:none` and `-g:source` also disturb
synthetic-method correspondence and introduce 376 semantic-code regressions, so they
are unambiguously inferior.

## Best current-JDK profile

The best observed Temurin profile is:

```text
Eclipse Temurin OpenJDK 25.0.4.1+1-LTS
com.sun.tools.javac.api.JavacTool / javac 25.0.4.1

-source 25 -target 25
-encoding UTF-8
-proc:none
-g
-parameters
-Xmaxerrs 100000
-Xmaxwarns 100000
-classpath <54 resolver-verified manifest artifacts>
-d <isolated profile output>
```

It produces:

```text
EXACT:                  1,770   (+1,063; 6.21% -> 15.55%)
STRUCTURAL:                45   (+27)
FUNCTIONAL:             2,717   (unchanged)
COMPILES:               6,851   (-1,090 through promotions)
normalized equivalent:  1,815   (+1,090)

semantic code matching: 89,110  (unchanged)
semantic regressions:         0
method metadata matching: 98,189
debug matching methods:    12,625
```

`-parameters` alone accounts for 500 exact promotions, `-g` alone for 157, and
another 406 require both; those three promotion sets are disjoint. The combined
profile has 1,063 exact promotions, no exact regressions, 1,099 status promotions,
no status regressions, and no semantic-code improvements or regressions.

The winning representation residuals are:

```text
MethodParameters:              18 changed (all present; contents differ)
LineNumberTable:           85,706 changed (all present; contents differ)
LocalVariableTable:         4,766 normalized changes
  absent from candidate:        0 methods
  extra in candidate:          147 methods
  present-content changed:   4,620 methods
LocalVariableTypeTable:      1,012 normalized changes
  absent from candidate:       56 methods
  extra in candidate:            3 methods
  present-content changed:     953 methods

raw Code arrays changed among semantic matches: 22,179
normalized StackMapTable changed:                   59
max_locals changed:                                  1
max_stack changed:                                   0
```

The 85,706 remaining line-table changes are therefore not a missing-debug-option
problem. They reflect reconstructed source positions. The remaining LVT/LVTT contents
likewise depend on recovered names, scopes, generic signatures, and source shape.

### `--release` versus `-source/-target`

The two target modes have identical class classifications and semantic-code totals,
but are not byte-identical. `-source 25 -target 25` improves 13 method-metadata
comparisons, reduces MethodParameters changes from 31 to 18, reduces normalized LVT
changes from 4,779 to 4,766, and reduces raw Code differences from 22,183 to 22,179.

Fourteen classfiles differ between the two winning flag variants. Thirteen are
anonymous subclasses whose super-constructor parameter names come from the JDK API;
for example, `RealmsMainScreen$1` records `name` with `-source/-target` but `arg0` with
`--release`. The latter resolves platform APIs through `ct.sym`, while the former uses
the pinned runtime image and can observe its MethodParameters metadata. The remaining
class, `net.minecraft.util.filefix.virtualfilesystem.CopyOnWriteFSPath`, accounts for
the four fewer raw Code differences. This makes `-source/-target` the empirical winner
for the fixed JDK, but does not by itself prove Mojang's historical command line.

## Alternate JDK candidates

The option search was completed before alternate JDK acquisition. Three Microsoft
Java 25 builds were then tested only with the winning option profile. These were
selected as release-adjacent, official OpenJDK-derived candidates; the official
[current download page](https://learn.microsoft.com/en-us/java/openjdk/download) and
[older release archive](https://learn.microsoft.com/en-us/java/openjdk/older-releases)
provide the distributions, checksums, and source tags.

| Distribution/runtime | Identity SHA-256 | Compiler-file SHA-256 | Result |
| --- | --- | --- | --- |
| Temurin `25.0.4.1+1-LTS` | `ac06bb632a3a29112ad90cc0dfb47bd41bfffd1495569ac3a349d79504e302df` | `092f78628f077ceb8ad4c324fd5f10695dcd2f317000747c5d326008f9cfddff` | winning tree |
| Microsoft `25.0.3+9-LTS` | `0033bdf7024a673ca44bbc2b0e56641c8a9a059b135cd52f028953bdb7b0ecb0` | `fdc483c6ca64029cf27c61c88caa9998371ad211ce9dcc830fd66246628b3b65` | identical winning tree |
| Microsoft `25.0.4+7-LTS` | `ee67f3d3a5b1d7e5466ea98c5eb678026ebc2acafc850d8d31cfb67e2db8da70` | `fb1fde5e274d441803999739e904ab2c2728dd0bde9d41d254f93e02306593f2` | identical winning tree |
| Microsoft `25.0.4.1+1-LTS` | `9eb7deb9520fe39f7311f18da5f237d08f3f69d9650f32abd82c235a8e7b3641` | `eff0be529ae27d53e6961adfaf23cc4c9b7e90f87c9f2d88ba24b83fe044beda` | identical winning tree |

All four binary-distinct compiler identities emitted this exact class tree:

```text
1b4f23a6f1dc5e45df16edada2540f512cc5f49647337ee32107a27483ad75ee
```

Consequently every candidate has the same 1,770 / 45 / 2,717 / 6,851 class counts,
89,110 semantic code matches, 98,189 metadata matches, and 12,625 debug matches.
Vendor label and Java 25 patch versions `25.0.3` through `25.0.4.1` explain none of
the remaining mismatch for this corpus. Testing another vendor package of the same
upstream javac line would not be an independent compiler-behavior experiment unless
its compiler identity or output first demonstrates a substantive difference.

The Microsoft archives were downloaded to the project tool cache, never installed
system-wide, and checked against the adjacent official checksum files:

| Archive | SHA-256 |
| --- | --- |
| `microsoft-jdk-25.0.3-windows-x64.zip` | `ba3ce9fa6ebb921eccda29b08c2bad26b5f8839080931096c73f306e0dd9856f` |
| `microsoft-jdk-25.0.4-windows-x64.zip` | `54ba13f3ef80887fa74708b2a32daaae6262517ba68433d850bb4b426343172b` |
| `microsoft-jdk-25.0.4.1-windows-x64.zip` | `3c9099e60a82e17f5847052f50f3b107bfc1d32b366e1a20adbd1d0f303760e7` |

## Reproducibility and attribution

The winning Temurin candidate was forcibly recompiled twice. Both runs produced:

```text
class tree:           1b4f23a6f1dc5e45df16edada2540f512cc5f49647337ee32107a27483ad75ee
compile report:       4f453737cebec0ab77d6caf34cb8636851e43575769b3ea148ab45612ff4488e
verification report:  2c558917feb843ef5b14160f62050790ae49023194d00f89db940a92e3590881
```

The measured attribution is:

- **Compiler options — CONFIRMED:** `-parameters` and full `-g` account for 1,063
  exact promotions and 1,090 normalized-equivalent promotions. They eliminate every
  missing MethodParameters and LVT attribute in corresponding methods without a
  semantic-code regression.
- **Target/API mode — CONFIRMED as an output difference, PLAUSIBLE historically:**
  `-source/-target` improves 13 metadata comparisons and four raw Code comparisons,
  but produces no additional exact class.
- **JDK distribution/patch — no measured contribution among candidates:** four
  distinct Temurin/Microsoft identities across `25.0.3`–`25.0.4.1` emit identical
  output. The actual Mojang vendor and patch are therefore **UNKNOWN**. The evidence
  constrains behavior to the tested mainstream OpenJDK javac 25 family, not to one
  distributor.
- **Source/debug-position reconstruction — HIGH_CONFIDENCE remaining cause:** all
  line tables are present under the winner, yet 85,706 differ in content. Most of the
  remaining LVT/LVTT disagreement is likewise present-content disagreement.
- **Remaining source shape/lowering — HIGH_CONFIDENCE remaining cause:** the 5,930
  semantic-code differences are completely unaffected by the winning flags or tested
  JDKs. Constant-pool/serialization effects remain in 22,179 semantically matching
  methods.

The next matching task should therefore attack source-shape recovery, with separate
debug/source-position reconstruction if exact line tables are an objective. Lambda and
synthetic-method reconstruction should be the first source-shape subproblem because it
dominates missing/extra method identities. Further JDK acquisition is lower-value
unless a candidate represents a demonstrably different javac implementation or build.
This report does not implement any of those source changes.
