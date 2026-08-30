# Gradle Build Diagnostics Action

Adds a small diagnostics table to a GitHub Actions job summary. It reads the latest run produced by
the Gradle Build Diagnostics plugin and does not change the build result.

Use it in the same job as Gradle, after the build command, so its files are still on the runner:

```yaml
- run: ./gradlew check

- name: Summarize build diagnostics
  if: always()
  uses: anthonymonori/gradle-build-diagnostics/github-action@<version>
  with:
    annotations: true
```

## Inputs

| Input | Default | Meaning |
|---|---|---|
| `report-directory` | `.gradle/build-diagnostics` | Plugin output base directory. |
| `annotations` | `false` | Emit guarded workflow annotations for exact source locations. |
| `max-diagnostics` | `20` | Maximum entries in the summary table. |
| `max-annotations` | `10` | Maximum annotations added to the workflow check. |

The action exits successfully if the plugin is not applied or no artifact exists. It reports that
case in the job summary. It ignores malformed JSONL records and unsupported future event types,
while clearly showing that fact in the summary.

Annotations never use parser output with ambiguous attribution. They require an exact attribution,
a positive line number, and a source path inside the repository.
