import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import test from "node:test";

const schema = JSON.parse(readFileSync(new URL("../../schema/diagnostics-v1.schema.json", import.meta.url), "utf8"));
const validate = addFormats(new Ajv2020()).compile(schema);

test("every canonical JSONL record validates against the published v1 schema", () => {
  ["success", "failure", "warning", "interrupted", "all-events"].forEach((name) => {
    const fixture = readFileSync(new URL(`../../src/test/resources/golden/${name}.jsonl`, import.meta.url), "utf8");
    fixture.split(/\r?\n/).filter(Boolean).forEach((line, index) => {
      assert.ok(validate(JSON.parse(line)), `${name} line ${index + 1}: ${JSON.stringify(validate.errors)}`);
    });
  });
});
