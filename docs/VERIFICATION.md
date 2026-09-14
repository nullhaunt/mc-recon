# Classfile reconstruction verification

`mc-recon verify <manifest> --side client` compares the resolver-verified Mojang
client JAR with the successful reconstructed class directory recorded by
`compile-report.json`. It never recompiles, changes source, changes compiler options, or
accepts an arbitrary candidate in the normal workflow.

The deterministic report is written to:

```text
.mc-recon/reconstruction/<version>/<side>/verification.json
```

Use `--format json` for the complete report on standard output. Use
`--class <binary-name>` for one class, and add `--details` to render symbolic
instruction differences for changed or unmatched methods. Two saved reports can be
compared with:

```text
mc-recon verify-compare before-verification.json after-verification.json
```

## Classification contract

Classes are joined only by sorted JVM classfile entry path, for example
`net/minecraft/Foo$1.class`. There is no fuzzy matching. Fields and methods are joined
by `name + descriptor`.

The primary state is derived in this order:

1. `EXACT`: `classExactHash` is equal.
2. `STRUCTURAL`: exact bytes differ, but `classStructureHash`, every corresponding
   `methodCodeHash`, `classDebugHash`, and every corresponding method `debugHash` are
   equal. Because the structure fingerprint includes field and method metadata, all
   normalized field and method metadata are equal too. This state means only data
   outside normalized fingerprint coverage differs.
3. `FUNCTIONAL`: all field and method identity sets correspond and at least one
   executable `methodCodeHash` differs. Metadata and debug differences remain visible
   as independent flags; the name does not claim behavioral equivalence.
4. `COMPILES`: the class exists on both sides but does not satisfy a stronger rule.
   This includes debug-only or metadata-only mismatches and member-inventory changes.
5. `MISSING`: a reference entry path has no rebuilt class.
6. `EXTRA`: a rebuilt entry path has no reference class.

The rules are deliberately conservative. In particular, debug-only differences are
not called `STRUCTURAL`, and a changed class hash alone never determines a non-exact
state.

`classStructureHash` is the established composite structure fingerprint, so the
reported class-structure, field-metadata, and method-metadata dimensions intentionally
overlap. Added and missing members are reported separately from changed metadata.

## Report evidence

Schema 1 records:

- the Mojang SHA-1, exact artifact SHA-256, kind, and size;
- the candidate class-tree SHA-256, source-tree SHA-256, compilation and
  reconstruction provenance hashes, compiler identity/options, and all 54 ordered
  classpath identities;
- status and independent dimensions for every reference class, plus any extra class;
- changed, missing, and extra field and method identities;
- complete aggregate class, field, method, debug, verifier, and attribute counts;
- the classfile-index and verification-format versions; and
- overlapping exact-class facets and deterministic mismatch patterns.

No timestamp, absolute path, username, temporary name, permission bit, or directory
iteration order enters the report. The candidate directory index rejects symbolic
links and non-class files and uses the same `ClassfileIndexer` as JAR indexing.

Supplemental index-schema-2 diagnostics do not change the version-1 structural, member,
code, or debug fingerprints. They split debug data into line-number, local-variable,
local-variable-type, and code-type-annotation hashes and record raw Code-array hashes,
normalized stack-map representations, `max_stack`, and `max_locals`. These values
locate representation differences and are not promoted into executable semantics.

## Minecraft 26.3-rc-2 client baseline

The candidate is the pre-verification compile-to-green output. No reconstruction rule,
patch, Vineflower setting, javac option, or JDK selection was changed before measuring
it.

```text
Reference classes:  11,383
Rebuilt classes:    11,383

EXACT:                  707    6.21%
STRUCTURAL:              18    0.16%
FUNCTIONAL:           2,717   23.87%
COMPILES:             7,941   69.76%

MISSING:                  0
EXTRA:                    0
```

Member totals are:

```text
Fields
  corresponding:     51,609
  matching metadata: 51,604
  changed metadata:       5
  changed constants:      0
  missing:                36
  extra:                  35

Methods
  corresponding:          98,352
  matching metadata:      51,535
  changed metadata:       46,817
  executable corresponding: 95,040
  matching code:          89,110  (93.76%)
  changed code:            5,930  ( 6.24%)
  debug changed:          94,020
  missing:                   386
  extra:                     385
```

Independent class dimensions (overlapping) are:

```text
class structure:                 10,081  88.56%
field metadata:                       3   0.03%
method metadata:                 10,078  88.54%
method implementation:            2,830  24.86%
debug:                            10,139  89.07%
outside normalized coverage:         18   0.16%
```

The most prominent raw/debug facts are:

```text
SourceFile changes:                         0 classes
LineNumberTable differences:           85,706 methods
LocalVariableTable differences:        88,112 methods
LocalVariableTypeTable differences:    15,701 methods
MethodParameters differences:          46,809 methods
code type-annotation differences:          44 methods

LocalVariableTable attributes removed: 88,312
LocalVariableTypeTable removed:         15,701
MethodParameters attributes removed:    42,868
```

Among the 89,110 semantically matching executable methods, 22,183 raw Code arrays
still differ, normally because constant-pool indexes and other encodings are not
semantic instruction operands. Only 59 have a different normalized stack-map
representation, one has different `max_locals`, and none has different `max_stack`.

## Distribution and causes

All 707 exact classes are interfaces. Of these, 565 are `package-info.class` files;
the other 142 are real interfaces, including `DeviceTracker` (three methods) and
`GpuSampler` (seven methods). No record, enum, anonymous/local class, class with 1–5
executable methods, or class with at least 20 executable methods is exact. The exact
count is therefore useful but is strongly dominated by trivial package metadata; the
non-package exact rate is 142 / 10,818 (1.31%).

A mutually exclusive diagnostic grouping of all classes is:

```text
EXACT                                                   707
STRUCTURAL / outside normalized coverage                 18
no code/member/field mismatch; only detected
  MethodParameters and debug-table dimensions         7,723
one or more semantic method-code differences           2,830
other metadata/member-inventory mismatch, no code        105
```

The 7,723-class group is a strict report-derived filter, not an assertion that every
byte outside those normalized dimensions already matches. It is nevertheless strong
evidence that compiler/debug configuration is the broadest first-order mismatch:
the current options contain neither `-parameters` nor an explicit `-g` mode, while
the Mojang files systematically contain method-parameter and local-variable tables.
Changed line numbers also show that reconstructed source positioning will remain a
separate debug-shape problem after compiler settings are identified.

The 2,830 classes with code differences are not one homogeneous cause. Samples show
genuine reconstructed control-flow/source-shape loss, lambda capture and synthetic
method-shape differences, generic/bridge consequences, and compiler-lowering
possibilities. There are 115 classes with field or method identity changes; the 386
missing and 385 extra methods are concentrated in lambda bodies and synthetic access
patterns. Of the 5,930 changed method bodies, 410 are `lambda$...`, 76 are
constructors, 13 are class initializers, and 107 are `equals`/`hashCode`/`toString`;
1,700 of the affected classes have only one changed body. Of missing/extra methods,
245/265 are lambda helpers and 55/33 are `access$...` helpers. These are method-shape
signals, not automatic root-cause labels. For example, the explicit legal-source helper
in `VulkanFeature` appears as one extra method and is a known reconstruction-patch
consequence. These observations need source-by-source attribution before repair.

## Representative evidence

- `EXACT` — `com.mojang.blaze3d.audio.DeviceTracker`: all bytes match; it is a
  nontrivial interface with three abstract methods.
- `STRUCTURAL` —
  `com.mojang.renderpearl.backend.api.BackendRenderPipeline`: every normalized
  fingerprint matches. `javap -v` shows an equal-size class whose three
  `InnerClasses` entries (`AttribBinding`, `VertexBuffer`, `Shader`) are serialized in
  a different order, changing constant-pool assignment and raw bytes.
- `FUNCTIONAL` — `net.minecraft.world.entity.ai.behavior.SetRaidStatus`: three of
  four executable methods match, but `lambda$create$1` does not. The reference expresses
  the `PRE_RAID` case first through `!hasFirstWaveSpawned || isBetweenWaves`; the
  reconstruction expresses the logically equivalent `RAID` case first through
  `hasFirstWaveSpawned && !isBetweenWaves`. The activity outcome is equivalent, but the
  branch layout and constant-loading order differ. This is a likely Vineflower
  source-shape loss with high confidence, not a debug-only compiler issue.
- `COMPILES` — `com.mojang.blaze3d.Blaze3D`: all seven executable fingerprints and
  all raw Code arrays match. Both `openPath` and `openUri` lack the reference
  `MethodParameters`; four methods lack `LocalVariableTable`; all seven have changed
  debug hashes/line tables. This is likely compiler/debug configuration plus source
  line positioning, with high confidence.
- `COMPILES` member-shape sample —
  `com.mojang.realmsclient.gui.screens.RealmsClientOutdatedScreen`: the reference has
  a static synthetic `lambda$init$1` with an explicit enclosing-instance parameter,
  while the rebuild has an instance-shaped lambda helper. This is a source/compiler
  lowering distinction; attribution to one side is not yet certain.

`com.mojang.blaze3d.Blaze3D` therefore preserves the previously established RC2
runtime behavior at the normalized executable-bytecode level even though its classfile
is not exact.

## Toolchain context and next milestone

The candidate provenance records:

```text
compiler:       JDK compiler API (javac)
runtime/vendor: Eclipse Temurin OpenJDK 25.0.4.1+1 LTS
class version:  Java 25 / major 69
--release:      25
options:        --release 25 -encoding UTF-8 -proc:none
                -Xmaxerrs 100000 -Xmaxwarns 100000
                -classpath <54 resolver-verified manifest artifacts>
                -d <class-output>
source tree:    df46d69ba60a2527d26315ff85b849ebabe71b0311ff4b6bfb5125d3ea6d7862
class tree:     9b13f230d65dace0e2832607e81bec551215d5b00e6dd0bdba0dfcad9ef51d90
reference JAR:  01fae8f1d0ba9bbcd62af0014fd2f0ba3781c5ce026487be9f97c7f5beab09ff
```

The next matching milestone should begin with compiler fingerprinting, especially
`MethodParameters` and local-variable debug emission, while retaining the current
baseline unchanged. That choice is driven by the 93.76% semantic method-code match rate
and the 7,723 classes whose detected normalized gap is limited to parameter/debug
evidence. Source matching should follow for the 2,830 code-different classes and the
line-number/source-position problem. This is a recommendation only; no compiler or
source repair is part of this baseline.

Two consecutive report generations must have identical report bytes. The verified
report SHA-256 is recorded here after the final double run:

```text
eb29bb9e29e0a17c8aeaa7662506e03a3f02f463a406b7cdd65d0cca57e3a869
```
