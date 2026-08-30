# Done

## TASK-038: Add GitHub Actions diagnostics summary
**Priority:** P1 | **Tags:** ci, github-actions, diagnostics

Added a dependency-free Node 24 action that reads the latest v1 run, produces a bounded job summary and outputs, and optionally adds safe exact-location annotations. It requires a matching, contiguous terminal stream before declaring a run complete and renders captured text as plain text. Reader, command-escaping, canonical-fixture, and process-level tests pass; the usage is documented and CI runs the action tests on Node 24.

---

## TASK-027: Harden the JSONL schema and reader contract
**Priority:** P1 | **Tags:** schema, compatibility

Published the event-specific v1 JSON Schema, canonical all-events fixture, and partial-file/evolution rules. All canonical JSONL lines validate against the published schema, and the companion reader tests malformed, unsupported, interrupted, and terminal record behavior against the same fixture contract.

---

## TASK-037: Migrate repository build scripts to Groovy DSL
**Priority:** P1 | **Tags:** build-logic, maintenance

Migrated the root and included-build settings/build scripts to Groovy DSL while keeping the binary plugin and convention-plugin implementations in Kotlin. Replaced Kotlin-DSL-only helpers with explicit Kotlin dependencies and public Gradle API callbacks, refreshed lockfiles, and passed the full configuration-cache, API, unit, and TestKit suite.

---

## TASK-033: Harden dependency and repository governance
**Priority:** P1 | **Tags:** supply-chain, build

Centralized repository declarations in settings and fail project-local repositories. Generated committed Gradle lockfiles and SHA-256 verification metadata, enabled Renovate lock maintenance, added CI lock-drift enforcement, protected `local.properties`, and documented the automatic update path. Full verification passes with dependency verification enabled.

---

## TASK-032: Curate the public binary API
**Priority:** P1 | **Tags:** api, compatibility

Internalized the collector model, parser, writer, retention task, build service, and Flow Action. The binary compatibility baseline now contains only the public settings-plugin entry point; full unit and functional verification confirms Gradle still instantiates the internal implementation correctly.

---

## TASK-026: Add Android and multiplatform consumer fixtures
**Priority:** P1 | **Tags:** samples, android, kmp

Added pinned TestKit consumers without adding AGP/KGP to the plugin classpath: Kotlin Multiplatform 2.4.0 on Gradle 9.5.0 verifies an exact `:compileKotlinJvm` failure; AGP 9.3.0 on Gradle 9.5.0/API 37 verifies warning capture. The Android fixture runs without configuration cache due an upstream AGP/Kotlin Flow Action deserialization failure; the limitation is documented. Full verification passes.

---

## TASK-036: Define and enforce settings-DSL configuration semantics
**Priority:** P1 | **Tags:** configuration, api

Selected a Gradle-property-only v1 surface. Removed the misleading typed settings extension, wired all collector parameters directly from `gradle.properties` / `-P` providers, documented the contract, and added TestKit coverage for both supported property configuration and rejected DSL usage. Refreshed the intentional API baseline; the full suite passes.

---

## TASK-031: Make run startup durable without duplicate packages
**Priority:** P0 | **Tags:** durability, architecture, regression

Added a shared writer registry so Gradle's task-event service, logging callbacks, and terminal Flow Action use one run package. The captured settings collector creates `build_started` after settings evaluation; one-package and configuration-cache-reuse TestKit regressions pass. JSONL control-character escaping now covers all JSON controls, and documentation states the remaining pre-registration boundary.

---

## TASK-025: Expand Gradle and Kotlin compatibility matrix
**Priority:** P1 | **Tags:** compatibility, testkit

Defined Gradle 8.12.1 as the minimum supported version and 9.7.1 as the current tested version. Regenerated the wrapper at 9.7.1; the CI matrix compiles and checks the same root build with both Gradle distributions, while TestKit verifies failure-tree/parser behavior on both versions. Kotlin 2.3.21 is verified on 8.12.1 and Kotlin 2.4.0 on 9.7.1. The full configuration-cache suite passes.

---

## TASK-024: Verify cancellation and configuration-failure boundaries
**Priority:** P1 | **Tags:** lifecycle, verification

Verified early settings failure produces no package, while configuration failure after registration produces a terminal failure package. Documented interruption/absence semantics and retained synthetic interrupted-artifact coverage; TestKit has no public cancellation interrupt control.

---

## TASK-023: Strengthen redaction verification and invalid-setting reporting
**Priority:** P1 | **Tags:** security, configuration

Added conservative URL-credential fixtures alongside private-key and false-positive coverage. Existing TestKit checks verify invalid redaction modes/patterns become collector warnings without changing the build outcome. The root README now documents disabled-mode risk.

---

## TASK-022: Improve diagnostic category and source-location normalization
**Priority:** P1 | **Tags:** diagnostics, schema

Added opt-out consumer-root path normalization and focused tests proving external/relative paths remain unchanged. Failure-associated Problems now map clear label signatures to stable categories while uncertain data remains unknown. Full suite passes.

---

## TASK-021: Complete parser context and custom matcher configuration
**Priority:** P1 | **Tags:** parser, configuration

Added bounded preceding/trailing parser context plus anchored custom error/warning matchers. Invalid/unanchored matchers are ignored with a collector warning. Unit and TestKit configuration-cache coverage pass.

---

## TASK-020: Add bounded parallel and high-volume stress coverage
**Priority:** P1 | **Tags:** parallelism, performance, verification

Added deterministic 200-callback stress coverage with a 32-event cap. It verifies contiguous sequencing, one event-limit warning, retained diagnostics, and a terminal-last artifact. Full configuration-cache suite passes.

---

## TASK-019: Validate artifact integrity under writer failures
**Priority:** P0 | **Tags:** durability, fault-injection

Added an injectable file-operation seam. Tests prove text-snapshot publication failure leaves durable JSONL/terminal evidence, while complete append failure is contained and cannot surface through the collector. Full configuration-cache suite passes.

---

## TASK-018: Decide and implement retention behavior for v1
**Priority:** P0 | **Tags:** retention, release-blocker

Kept ordinary builds non-destructive and added the explicit root `pruneBuildDiagnostics` task. It deletes only planner-verified completed UUID packages beyond `retainCompletedRuns`; the full suite verifies retention and terminal-last behavior.

---

## TASK-017: Make the writer safe under concurrent Gradle callbacks
**Priority:** P0 | **Tags:** concurrency, reliability

Serialized all public writer mutations through one re-entrant lock, protecting sequence numbers, byte limits, deduplication, JSONL append, and text snapshots. Added a 40-way concurrent writer test and verified the full configuration-cache suite.

---

## TASK-016: Verify parallel-attribution honesty
**Priority:** P1 | **Tags:** parallelism, verification

Added a parallel two-subproject TestKit fixture. It verifies that structured failure records preserve exact `:one:fails` and `:two:fails` paths while parser-derived output remains ambiguous. The full configuration-cache suite passes.

---

## TASK-015: Make numeric property parsing observational
**Priority:** P1 | **Tags:** configuration, reliability

Made `maxEvents`, `maxBytesPerBuild`, and `retainCompletedRuns` Gradle-property parsing defensive. Malformed values and negative event/byte limits fall back to defaults rather than failing settings evaluation. Verified with TestKit and the full configuration-cache suite.

---

## TASK-014: Add conservative task-path filtering
**Priority:** P2 | **Tags:** configuration, filters

Added comma-separated full-path include/exclude globs for exact task-attributed lifecycle and failure records; exclusions win. Ambiguous parser output remains untouched. Fixed the initial provider-capture configuration-cache regression and verified the full suite.

---

## TASK-013: Capture failure-associated Problems when available
**Priority:** P1 | **Tags:** problems-api, collectors

Added an isolated reflective adapter for failure-associated Problems. Available severity/message/location fields are normalized with `problems_api` origin; missing or changing methods safely return no records and preserve the structured failure-tree fallback. Verified by adapter tests and the full configuration-cache suite.

---

## TASK-012: Add built-in parser control
**Priority:** P2 | **Tags:** configuration, parser

Added `buildDiagnostics.builtInParsersEnabled`, defaulting to true. When false, no global output listeners are registered but structured task lifecycle and failure-tree events continue. Verified through TestKit with configuration cache.

---

## TASK-011: Reserve terminal artifact capacity
**Priority:** P1 | **Tags:** reliability, limits

Reserved structural capacity for mandatory start and terminal records. Regular diagnostics/task events stop before the reserve, so byte pressure cannot remove the terminal build state. Added an edge-case writer test and verified the full configuration-cache suite.

---

## TASK-010: Align emitted lifecycle events with the v1 contract
**Priority:** P1 | **Tags:** schema, lifecycle

Added `task_finished` JSONL records from public task completion events with exact task paths and success/failure outcomes. Updated the schema and written contract to use the actual `runId` metadata and defer unimplemented privacy-sensitive metadata. Verified with the full configuration-cache suite.

---

## TASK-009: Implement redaction-mode policy
**Priority:** P1 | **Tags:** security, configuration

Implemented the lazy `buildDiagnostics.redactionMode` property. `conservative` remains the safe default, `disabled` is an explicit trusted-local opt-out, and invalid values fall back to conservative with a collector warning. Added unit and TestKit coverage; the full configuration-cache suite passes.

---

## TASK-008: Add retention and safe run-package maintenance
**Priority:** P2 | **Tags:** retention, reliability

Added a non-destructive retention planner that only considers direct UUID run packages with a valid terminal record for their own run ID. It ranks terminal timestamps, scans a bounded tail, preserves incomplete/malformed evidence, treats `0` as disabled, and never changes a build outcome. Verified with the full configuration-cache test suite.

---

## TASK-007: Harden JSONL schema and artifact integrity
**Priority:** P1 | **Tags:** schema, verification

Added a versioned JSON Schema and golden JSONL fixtures for success, failure, warning, and interruption. Verified canonical ordering/fields and malformed custom-redaction warnings without a runtime JSON dependency.

---

## TASK-006: Complete v1 collector configuration and documentation
**Priority:** P1 | **Tags:** configuration, documentation

Added warnings, limits, custom redaction patterns, console-summary configuration, documented defaults, and TestKit verification for output relocation, disablement, warning opt-in, and console summary.

---

## TASK-005: Add public diagnostics configuration and property overrides
**Priority:** P1 | **Tags:** configuration, api

Added the typed settings extension plus `buildDiagnostics.enabled` and `buildDiagnostics.outputBaseDirectory` properties. TestKit verifies disabled collection and settings-relative output relocation with configuration cache enabled.

---

## TASK-004: Add sample projects and consumer-facing documentation
**Priority:** P2 | **Tags:** samples, documentation

Added and ran a Kotlin/JVM included-build consumer sample. Documented settings-plugin application, CI run-directory uploads, agent consumption, and Android-compatible root-settings application.

---

## TASK-003: Implement Gradle settings-plugin collection and TestKit integration tests
**Priority:** P1 | **Tags:** gradle, testkit, collectors

Implemented per-run production artifacts, exact structured failure-tree collection, terminal Flow Action records, `latest.json`, and ambiguous stdout/stderr parsing. TestKit covers success, task/configuration failure, Kotlin 2.3.21/2.4.0 failures, warnings, and configuration-cache reuse. V1 accepts Gradle-managed output-listener lifetime.

---

## TASK-002: Implement normalized event model and durable artifact writers
**Priority:** P1 | **Tags:** core, jsonl, rendering

Implemented and tested the normalized diagnostic model, ANSI/control cleanup, conservative and custom redaction, UTF-8 byte limits, post-redaction SHA-256 fingerprints, deduplication, append-and-flush JSONL, atomic text snapshots, per-run limits, and explicit interruption semantics. Writers contain their own failures so collection cannot alter a build outcome.

---

## TASK-001: Define v1 artifact contract and validate Gradle event capabilities
**Priority:** P0 | **Tags:** architecture, schema, spike

Defined the v1 artifact contract and selected Gradle 8.12+/Java 17, per-run `.gradle/build-diagnostics` packages, best-effort configuration coverage, and conservative redaction. Implemented and verified a TestKit settings-plugin capability spike against Gradle 8.12.1: structured failure trees arrived for generic and Kotlin 2.3.21/2.4.0 compilation failures, including configuration-cache reuse; associated Problems were exposed but empty in each fixture. The spike also establishes that task events do not cover a build-script configuration failure.

---
