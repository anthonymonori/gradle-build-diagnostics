import { readFileSync, existsSync } from "node:fs";
import { resolve, relative, isAbsolute, sep } from "node:path";

const EVENT_TYPES = new Set([
  "build_started",
  "diagnostic",
  "task_finished",
  "collector_warning",
  "build_finished",
]);

const DIAGNOSTIC_SEVERITIES = new Set(["error", "warning"]);
const ATTRIBUTIONS = new Set(["exact", "ambiguous", "unknown"]);

/** Reads the latest v1 run without treating captured diagnostics as trusted input. */
export function readLatestRun(reportDirectory) {
  const baseDirectory = resolve(reportDirectory);
  const latestPath = resolve(baseDirectory, "latest.json");
  if (!existsSync(latestPath)) return { state: "missing" };

  let latest;
  try {
    latest = JSON.parse(readFileSync(latestPath, "utf8"));
  } catch {
    return { state: "invalid_latest" };
  }

  if (!isSafeRunPointer(latest)) return { state: "invalid_latest" };
  const runDirectory = resolve(baseDirectory, latest.path);
  if (!isWithin(resolve(baseDirectory, "runs"), runDirectory)) return { state: "invalid_latest" };

  const jsonlPath = resolve(runDirectory, "diagnostics.jsonl");
  if (!existsSync(jsonlPath)) return { state: "missing_jsonl", runDirectory };

  return readJsonl(readFileSync(jsonlPath, "utf8"), runDirectory, latest.runId);
}

export function readJsonl(contents, runDirectory = "", expectedRunId) {
  const diagnostics = [];
  const warnings = [];
  let malformedLines = 0;
  let unsupportedRecords = 0;
  let trailingRecords = 0;
  let protocolViolations = 0;
  let expectedSequence = 1;
  let streamRunId = expectedRunId;
  let started = false;
  let lastEvent;
  let outcome;

  contents.split(/\r?\n/).forEach((line) => {
    if (!line.trim()) return;
    if (lastEvent?.eventType === "build_finished") {
      trailingRecords += 1;
      return;
    }
    let event;
    try {
      event = JSON.parse(line);
    } catch {
      malformedLines += 1;
      return;
    }

    if (event.schemaVersion !== 1) {
      unsupportedRecords += 1;
      return;
    }
    if (!hasCommonFields(event)) {
      malformedLines += 1;
      return;
    }
    if (!EVENT_TYPES.has(event.eventType)) {
      unsupportedRecords += 1;
      acceptStreamRecord(event);
      return;
    }
    if (!hasEventFields(event)) {
      malformedLines += 1;
      return;
    }

    if (!acceptStreamRecord(event)) return;

    if (event.eventType === "diagnostic") diagnostics.push(event);
    if (event.eventType === "collector_warning") warnings.push(event.reason);
    if (event.eventType === "build_finished") outcome = event.outcome;
  });

  function acceptStreamRecord(event) {
    if (streamRunId && event.runId !== streamRunId) {
      protocolViolations += 1;
      return false;
    }
    if (!streamRunId) streamRunId = event.runId;
    if (!started && event.eventType !== "build_started") protocolViolations += 1;
    if (event.sequence !== expectedSequence) protocolViolations += 1;
    expectedSequence = event.sequence + 1;
    if (event.eventType === "build_started") {
      if (started) protocolViolations += 1;
      started = true;
    }

    lastEvent = event;
    return true;
  }

  const finished = started && lastEvent?.eventType === "build_finished" && trailingRecords === 0 &&
    protocolViolations === 0;
  return {
    state: finished ? "complete" : "incomplete",
    runDirectory,
    diagnostics,
    warnings,
    malformedLines,
    unsupportedRecords,
    trailingRecords,
    protocolViolations,
    outcome: finished ? outcome : undefined,
  };
}

export function renderSummary(result, maxDiagnostics) {
  if (result.state === "missing") {
    return "## Gradle build diagnostics\n\nNo diagnostics artifact was found. The plugin may not be applied, or its output directory may differ from this action's `report-directory` input.\n";
  }
  if (result.state === "invalid_latest") {
    return "## Gradle build diagnostics\n\nThe diagnostics `latest.json` pointer is invalid, so no report was read.\n";
  }
  if (result.state === "missing_jsonl") {
    return "## Gradle build diagnostics\n\nThe selected diagnostics run does not contain `diagnostics.jsonl`.\n";
  }

  const errors = result.diagnostics.filter((diagnostic) => diagnostic.severity === "error").length;
  const warnings = result.diagnostics.filter((diagnostic) => diagnostic.severity === "warning").length;
  const outcome = result.outcome ?? "interrupted";
  const lines = [
    "## Gradle build diagnostics",
    "",
    "| Build result | Errors | Warnings | State |",
    "|---|---:|---:|---|",
    `| ${markdown(outcome)} | ${errors} | ${warnings} | ${result.state} |`,
  ];

  if (result.malformedLines || result.unsupportedRecords || result.trailingRecords || result.protocolViolations) {
    lines.push(
      "",
      `Reader note: ignored ${result.malformedLines} malformed, ${result.unsupportedRecords} unsupported, ${result.trailingRecords} trailing, and ${result.protocolViolations} protocol-violating record(s).`,
    );
  }
  if (result.warnings.length) {
    lines.push("", `Collector note: ${result.warnings.map(markdown).join(", ")}.`);
  }
  if (result.state === "incomplete") {
    lines.push("", "This run does not have a complete valid terminal stream, so earlier diagnostics may still be useful but the final outcome is unknown.");
  }

  const shown = result.diagnostics.slice(0, positiveInteger(maxDiagnostics, 20));
  if (shown.length) {
    lines.push("", "| Severity | Category | Task | Location | Message |", "|---|---|---|---|---|");
    shown.forEach((diagnostic) => {
      lines.push(
        `| ${markdown(diagnostic.severity)} | ${markdown(diagnostic.category)} | ${markdown(diagnostic.taskPath ?? "—")} | ${markdown(locationText(diagnostic.location))} | ${markdown(diagnostic.message)} |`,
      );
    });
  }
  if (result.diagnostics.length > shown.length) {
    lines.push("", `Showing ${shown.length} of ${result.diagnostics.length} diagnostics.`);
  }

  return `${lines.join("\n")}\n`;
}

export function annotationCommands(result, workspaceDirectory, maxAnnotations) {
  if (result.state !== "complete" && result.state !== "incomplete") return [];
  const limit = positiveInteger(maxAnnotations, 10);
  return result.diagnostics
    .filter(isAnnotatable)
    .slice(0, limit)
    .map((diagnostic) => annotationCommand(diagnostic, workspaceDirectory));
}

function isSafeRunPointer(pointer) {
  return pointer && typeof pointer.path === "string" && typeof pointer.runId === "string" &&
    pointer.path === `runs/${pointer.runId}` && !pointer.runId.includes("/") && !pointer.runId.includes("\\");
}

function hasCommonFields(event) {
  return Number.isInteger(event.sequence) && event.sequence > 0 && typeof event.runId === "string" &&
    event.runId.length > 0 && validTimestamp(event.timestamp);
}

function hasEventFields(event) {
  switch (event.eventType) {
    case "build_started": return event.state === "in_progress";
    case "build_finished": return event.outcome === "success" || event.outcome === "failure";
    case "task_finished": return typeof event.taskPath === "string" && (event.outcome === "success" || event.outcome === "failure");
    case "collector_warning": return typeof event.reason === "string";
    case "diagnostic":
      return DIAGNOSTIC_SEVERITIES.has(event.severity) && typeof event.category === "string" &&
        typeof event.origin === "string" && typeof event.message === "string" && Array.isArray(event.context) &&
        ATTRIBUTIONS.has(event.attribution) && typeof event.fingerprint === "string" &&
        typeof event.truncated === "boolean" && typeof event.redacted === "boolean" && validLocation(event.location);
    default: return false;
  }
}

function validLocation(location) {
  return location === undefined || (location !== null && (location.path === null || typeof location.path === "string") &&
    (location.line === null || Number.isInteger(location.line) && location.line > 0) &&
    (location.column === null || Number.isInteger(location.column) && location.column > 0));
}

function validTimestamp(timestamp) {
  return typeof timestamp === "string" &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(timestamp) &&
    !Number.isNaN(Date.parse(timestamp));
}

function isAnnotatable(diagnostic) {
  return diagnostic.attribution === "exact" && diagnostic.location?.path &&
    Number.isInteger(diagnostic.location.line) && diagnostic.location.line > 0 &&
    isSafeRelativePath(diagnostic.location.path);
}

function annotationCommand(diagnostic, workspaceDirectory) {
  const properties = [`file=${escapeWorkflowProperty(diagnostic.location.path)}`, `line=${diagnostic.location.line}`];
  if (Number.isInteger(diagnostic.location.column) && diagnostic.location.column > 0) {
    properties.push(`col=${diagnostic.location.column}`);
  }
  const level = diagnostic.severity === "error" ? "error" : "warning";
  const title = escapeWorkflowProperty(`Gradle ${diagnostic.category}`);
  const source = resolve(workspaceDirectory, diagnostic.location.path);
  if (!isWithin(resolve(workspaceDirectory), source)) return "";
  return `::${level} ${properties.join(",")},title=${title}::${escapeWorkflowData(diagnostic.message)}`;
}

function isSafeRelativePath(path) {
  return typeof path === "string" && !isAbsolute(path) && !path.split(/[\\/]/).includes("..");
}

function isWithin(parent, child) {
  const path = relative(parent, child);
  return path === "" || (!path.startsWith(`..${sep}`) && path !== ".." && !isAbsolute(path));
}

function positiveInteger(value, fallback) {
  const parsed = Number.parseInt(value, 10);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function locationText(location) {
  if (!location?.path) return "—";
  return `${location.path}${location.line ? `:${location.line}` : ""}${location.column ? `:${location.column}` : ""}`;
}

function markdown(value) {
  return String(value)
    .replace(/[\r\n]+/g, " ")
    .replace(/[\u0000-\u001F\u007F]/g, " ")
    .replace(/([\\`*_{}\[\]<>()#+\-.!|~])/g, "\\$1")
    .slice(0, 500);
}

function escapeWorkflowData(value) {
  return String(value).replace(/%/g, "%25").replace(/\r/g, "%0D").replace(/\n/g, "%0A");
}

function escapeWorkflowProperty(value) {
  return escapeWorkflowData(value).replace(/:/g, "%3A").replace(/,/g, "%2C");
}
