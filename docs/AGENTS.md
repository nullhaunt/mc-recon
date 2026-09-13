# AGENTS.md

## Project

mc-recon reconstructs and chronicles vanilla Minecraft Java Edition
26.1+ from Mojang-published artifacts.

Read PLAN.md before architectural work.

## Core invariants

- Mojang's published artifacts are the ground truth.
- Do not introduce Fabric, Forge, NeoForge, Quilt, Loom, MCPConfig,
  mappings, or modding APIs.
- Preserve artifact provenance and verify Mojang-provided hashes.
- Prefer deterministic, inspectable behavior.
- Keep network-dependent tests out of the normal test suite.
- Do not silently repair or discard Mojang manifest information.
- Do not implement future PLAN.md milestones unless the current task
  requires them.

## Development

Run the full test suite before finishing:

./gradlew test

Build/install locally with:

./gradlew installDist

Primary integration fixture:

fixtures/26.3-rc-2.json