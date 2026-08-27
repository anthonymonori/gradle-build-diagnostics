# In Progress

## TASK-027: Harden the JSONL schema and reader contract
**Priority:** P1 | **Tags:** schema, compatibility

Make the schema accurately describe every event-specific field, additive-evolution rules, malformed-line handling, and reader expectations.

### Plan

- Audit emitted event fields against the current schema and fixtures.
- Add event-specific schema definitions and golden validation tests.
- Document partial-file reading and schema evolution rules.

---
