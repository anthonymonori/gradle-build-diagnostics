import { appendFileSync } from "node:fs";
import { readLatestRun, renderSummary, annotationCommands } from "./reader.js";

const input = (name, fallback) => process.env[`INPUT_${name.toUpperCase().replaceAll("-", "_")}`] ?? fallback;
const result = readLatestRun(input("report-directory", ".gradle/build-diagnostics"));

writeSummary(renderSummary(result, input("max-diagnostics", "20")));
writeOutputs(result);

if (input("annotations", "false").toLowerCase() === "true") {
  annotationCommands(result, process.env.GITHUB_WORKSPACE ?? process.cwd(), input("max-annotations", "10"))
    .filter(Boolean)
    .forEach((command) => process.stdout.write(`${command}\n`));
}

function writeSummary(summary) {
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
  else process.stdout.write(summary);
}

function writeOutputs(report) {
  if (!process.env.GITHUB_OUTPUT) return;
  const errors = report.diagnostics?.filter((diagnostic) => diagnostic.severity === "error").length ?? 0;
  const warnings = report.diagnostics?.filter((diagnostic) => diagnostic.severity === "warning").length ?? 0;
  appendFileSync(
    process.env.GITHUB_OUTPUT,
    `outcome=${report.outcome ?? ""}\nrun-directory=${report.runDirectory ?? ""}\nerror-count=${errors}\nwarning-count=${warnings}\n`,
  );
}
