# Consumer dogfood review

## 2026-08-30: controlled consumers

This review used the checked-in Kotlin/JVM sample and the existing Android and Kotlin
Multiplatform TestKit consumers. They are useful controlled evidence, but they do not replace a
later trial in a second real repository.

| Consumer | Build result | What the artifact retained | Result |
|---|---|---|---|
| `samples/project` Kotlin/JVM | `:diagnosticFailure` failed | terminal failure record and exact task failures | useful, with duplicate failure-tree entries |
| Kotlin 2.4 JVM fixture | `:compileKotlin` failed | exact structured task failure on Gradle 9.7.1 | passed |
| Android library fixture | successful warning task | warning retained with ambiguous output attribution | passed |
| Kotlin Multiplatform fixture | `:compileKotlinJvm` failed | exact structured task failure | passed |

The JVM sample's raw terminal output only presents `sample failure`. Its run package also records
the failed task and the structured cause, so it is substantially easier to inspect. It currently
records both Gradle's task-execution wrapper and the underlying cause, however, and preserves a
large stack-trace context for each. TASK-039 tracks making this more concise without losing the
underlying error.

The Android and KMP fixtures run without configuration cache because of the existing upstream
AGP/Kotlin Flow Action limitation. The artifact assertions themselves pass.

## External user-test acceptance

Before a wider trial, run the plugin in a second real JVM, Android, or KMP repository using its
published plugin marker. Confirm a compiler or test failure, an opt-in warning from a successful
task, and an interrupted run. Upload the selected run directory, verify captured text has no
unexpected secrets, and confirm the GitHub Action summary and exact-location annotations match the
artifact.
