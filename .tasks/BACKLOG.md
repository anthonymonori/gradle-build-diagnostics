# Backlog


## TASK-034: Add JaCoCo coverage reporting and an initial quality threshold
**Priority:** P2 | **Tags:** tests, coverage, ci

Publish HTML and XML JaCoCo reports for unit and functional tests, with a modest ratcheting threshold suitable for an early plugin.

### Plan

- Configure reports for the JVM test suites and aggregate them in `check`.
- Exclude generated and Gradle wiring code only with an explicit rationale.
- Upload reports from CI and decide whether Pages publication is useful after the first public release.

---

## TASK-035: Validate release artifacts before publication
**Priority:** P2 | **Tags:** release, publishing

Verify plugin marker and implementation artifacts from a clean local repository before the deferred Artifactory publication is enabled.

### Plan

- Assemble and inspect generated plugin marker metadata.
- Consume the staged artifact from an isolated fixture.
- Extend the draft-release workflow only after the local smoke test is reliable.

---

## TASK-028: Add release-quality CI and artifact-consumption guidance
**Priority:** P2 | **Tags:** documentation, ci

Provide copyable CI upload, retention, privacy, and human/agent-consumption examples that match actual v1 behavior.

### Plan

- Add GitHub Actions and generic CI snippets without publishing credentials.
- Document artifact collection on success/failure and partial-run handling.
- Add a small reader/validation example against fixtures.

---

## TASK-029: Prepare public release metadata and quality gates
**Priority:** P2 | **Tags:** release, governance

Prepare the repository for an initial internal/public release without configuring Artifactory publication yet.

### Plan

- Add license, changelog, compatibility policy, and contribution/security guidance as appropriate.
- Add plugin validation, formatting/static-analysis, and dependency/license checks proportionate to this repository.
- Audit ignored/generated/sensitive files before the first commit and release workflow.

---

## TASK-030: Conduct a pre-release consumer dogfood review
**Priority:** P1 | **Tags:** dogfood, acceptance

Run the plugin against at least two realistic consumer repositories and compare captured artifacts with their raw failures before inviting wider user tests.

### Plan

- Select consumers covering JVM/Kotlin and Android/KMP if available.
- Capture expected diagnostics, gaps, noise, and redaction observations.
- Convert findings into explicit follow-up tasks and define initial user-test acceptance criteria.

---
