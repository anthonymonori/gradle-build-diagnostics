import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";
import {
  annotationCommands,
  readJsonl,
  readLatestRun,
  renderSummary,
} from "../src/reader.js";

const started = record("build_started", { state: "in_progress" });
const finished = record("build_finished", { sequence: 3, outcome: "failure" });
const exactDiagnostic = record("diagnostic", {
  sequence: 2,
  severity: "error",
  category: "compilation",
  origin: "stderr_parser",
  message: "Unresolved reference: total",
  context: [],
  attribution: "exact",
  fingerprint: "fixture",
  truncated: false,
  redacted: false,
  location: { path: "src/main/kotlin/Checkout.kt", line: 42, column: 17 },
});

test("reads a complete run and renders a bounded table", () => {
  const result = readJsonl(jsonl(started, exactDiagnostic, finished));

  assert.equal(result.state, "complete");
  assert.equal(result.outcome, "failure");
  assert.match(
    renderSummary(result, 20),
    /\| failure \| 1 \| 0 \| complete \|/,
  );
  assert.match(renderSummary(result, 20), /Unresolved reference: total/);
});

test("retains valid earlier diagnostics when the final line is malformed", () => {
  const result = readJsonl(`${jsonl(started, exactDiagnostic)}\n{not-json`);

  assert.equal(result.state, "incomplete");
  assert.equal(result.diagnostics.length, 1);
  assert.equal(result.malformedLines, 1);
  assert.match(renderSummary(result, 20), /final outcome is unknown/);
});

test("treats scalar JSON lines as malformed without crashing", () => {
  const terminal = { ...finished, sequence: 2 };
  const result = readJsonl(
    [JSON.stringify(started), "null", JSON.stringify(terminal)].join("\n"),
  );

  assert.equal(result.state, "complete");
  assert.equal(result.malformedLines, 1);
});

test("does not treat a terminal event with trailing data as complete", () => {
  const result = readJsonl(`${jsonl(started, finished)}\n{not-json`);

  assert.equal(result.state, "incomplete");
  assert.equal(result.trailingRecords, 1);
});

test("ignores an additive future event type while preserving stream integrity", () => {
  const futureEvent = JSON.stringify({
    ...record("future_event", {}),
    eventType: "future_event",
    sequence: 2,
    extra: "future",
  });
  const terminal = { ...finished, sequence: 3 };
  const result = readJsonl(
    [JSON.stringify(started), futureEvent, JSON.stringify(terminal)].join("\n"),
  );

  assert.equal(result.state, "complete");
  assert.equal(result.unsupportedRecords, 1);
  assert.equal(result.diagnostics.length, 0);
});

test("does not complete a stream with a malformed canonical record", () => {
  const invalidDiagnostic = JSON.stringify({
    ...record("diagnostic", { severity: "error" }),
    sequence: 2,
  });
  const result = readJsonl(
    [JSON.stringify(started), invalidDiagnostic, JSON.stringify(finished)].join(
      "\n",
    ),
  );

  assert.equal(result.state, "incomplete");
  assert.equal(result.malformedLines, 1);
  assert.equal(result.diagnostics.length, 0);
});

test("does not complete a stream without a matching start record and run ID", () => {
  const wrongRun = { ...finished, runId: "other" };
  const result = readJsonl(jsonl(wrongRun), "", "fixture");

  assert.equal(result.state, "incomplete");
  assert.equal(result.protocolViolations, 1);
});

test("does not complete a stream with a sequence gap", () => {
  const terminal = { ...finished, sequence: 3 };
  const result = readJsonl(jsonl(started, terminal));

  assert.equal(result.state, "incomplete");
  assert.equal(result.protocolViolations, 1);
});

test("only annotates exact diagnostics with safe repository-relative locations", () => {
  const unsafe = JSON.stringify(
    record("diagnostic", {
      sequence: 3,
      severity: "error",
      category: "test",
      origin: "failure_tree",
      message: "unsafe",
      context: [],
      attribution: "exact",
      fingerprint: "unsafe",
      truncated: false,
      redacted: false,
      location: { path: "../outside.kt", line: 1, column: null },
    }),
  );
  const result = readJsonl(
    [
      JSON.stringify(started),
      JSON.stringify(exactDiagnostic),
      unsafe,
      JSON.stringify({ ...finished, sequence: 4 }),
    ].join("\n"),
  );
  const annotations = annotationCommands(result, "/workspace", 10);

  assert.equal(annotations.length, 1);
  assert.equal(
    annotations[0],
    "::error file=src/main/kotlin/Checkout.kt,line=42,col=17,title=Gradle compilation::Unresolved reference: total",
  );
});

test("escapes captured text and annotation properties before writing workflow commands", () => {
  const diagnostic = JSON.stringify(
    record("diagnostic", {
      sequence: 2,
      severity: "error",
      category: "compilation:parser",
      origin: "stderr_parser",
      message: "first line\n::error::injected",
      context: [],
      attribution: "exact",
      fingerprint: "escaped",
      truncated: false,
      redacted: false,
      location: { path: "src/a,b.kt", line: 1, column: null },
    }),
  );
  const result = readJsonl(
    [JSON.stringify(started), diagnostic, JSON.stringify(finished)].join("\n"),
  );

  assert.equal(
    annotationCommands(result, "/workspace", 10)[0],
    "::error file=src/a%2Cb.kt,line=1,title=Gradle compilation%3Aparser::first line%0A::error::injected",
  );
});

test("renders captured Markdown syntax as literal text", () => {
  const diagnostic = {
    ...exactDiagnostic,
    message: "[click](https://untrusted.invalid) <b>not bold</b>",
  };
  const result = readJsonl(jsonl(started, diagnostic, finished));

  assert.match(
    renderSummary(result, 20),
    /\\\[click\\\]\\\(https:\/\/untrusted\\\.invalid\\\) \\<b\\>not bold\\<\/b\\>/,
  );
});

test("uses only the safe latest pointer under the report runs directory", () => {
  const directory = mkdtempSync(join(tmpdir(), "diagnostics-action-"));
  const selectedStarted = { ...started, runId: "run" };
  const selectedFinished = { ...finished, runId: "run", sequence: 2 };
  writeFileSync(
    join(directory, "latest.json"),
    JSON.stringify({ runId: "run", path: "runs/run" }),
  );
  mkdirSync(join(directory, "runs", "run"), { recursive: true });
  writeFileSync(
    join(directory, "runs", "run", "diagnostics.jsonl"),
    jsonl(selectedStarted, selectedFinished),
  );

  assert.equal(readLatestRun(directory).state, "complete");
  writeFileSync(
    join(directory, "latest.json"),
    JSON.stringify({ runId: "run", path: "../other" }),
  );
  assert.equal(readLatestRun(directory).state, "invalid_latest");
});

test("accepts every canonical plugin event fixture", () => {
  const fixture = readFileSync(
    new URL(
      "../../src/test/resources/golden/all-events.jsonl",
      import.meta.url,
    ),
    "utf8",
  );
  const result = readJsonl(fixture);

  assert.equal(result.state, "complete");
  assert.equal(result.diagnostics.length, 1);
  assert.deepEqual(result.warnings, ["invalid_parser_matcher"]);
});

test("action process writes a summary, outputs, and guarded annotations", () => {
  const directory = mkdtempSync(join(tmpdir(), "diagnostics-action-process-"));
  const reportDirectory = join(directory, "diagnostics");
  const runDirectory = join(reportDirectory, "runs", "run");
  const summary = join(directory, "summary.md");
  const output = join(directory, "outputs.txt");
  const selectedStarted = { ...started, runId: "run" };
  const selectedDiagnostic = { ...exactDiagnostic, runId: "run" };
  const selectedFinished = { ...finished, runId: "run" };
  mkdirSync(runDirectory, { recursive: true });
  writeFileSync(
    join(reportDirectory, "latest.json"),
    JSON.stringify({ runId: "run", path: "runs/run" }),
  );
  writeFileSync(
    join(runDirectory, "diagnostics.jsonl"),
    jsonl(selectedStarted, selectedDiagnostic, selectedFinished),
  );

  const actionProcess = spawnSync(
    globalThis.process.execPath,
    ["src/main.js"],
    {
      cwd: new URL("..", import.meta.url),
      encoding: "utf8",
      env: {
        ...globalThis.process.env,
        GITHUB_STEP_SUMMARY: summary,
        GITHUB_OUTPUT: output,
        GITHUB_WORKSPACE: "/workspace",
        INPUT_REPORT_DIRECTORY: reportDirectory,
        INPUT_ANNOTATIONS: "true",
      },
    },
  );

  assert.equal(actionProcess.status, 0, actionProcess.stderr);
  assert.match(readFileSync(summary, "utf8"), /Gradle build diagnostics/);
  assert.match(readFileSync(output, "utf8"), /error-count=1/);
  assert.match(
    actionProcess.stdout,
    /::error file=src\/main\/kotlin\/Checkout.kt,line=42,col=17/,
  );
});

function record(eventType, fields) {
  return {
    schemaVersion: 1,
    sequence: 1,
    timestamp: "2026-08-30T12:00:00Z",
    eventType,
    runId: "fixture",
    ...fields,
  };
}

function jsonl(...events) {
  return events.map(JSON.stringify).join("\n");
}
