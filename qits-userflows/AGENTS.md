# qits-userflows/

The **userflows framework** — everything that runs a user story except the stories themselves. A
plain library jar (package root `eu.wohlben.qits.userflows`) on the `userflows` module's test
classpath. It depends on **none** of the app modules; it drives qits by URL only.

## What lives here

- **Annotations**: `@UserStory` (with an optional `category` — a categorized story emits under
  `<category-slug>/<story-slug>/`, so the directory layout carries the grouping),
  `@UserStoryDescription`, `@ExpectedFailure`, `@UserflowPrecondition`, `@UserflowRunsAfter`.
- **`Flow`** — the step-recording facade over Playwright's `Page` (every verb records a step).
- **`Interactions`** — the service-level recording facade: `happened(from, to, "GET /idp/jwks")`
  records a service-to-service interaction (a step + a structured entry the report renders as a
  mermaid sequence diagram), `note(line)` a narrative step. A story method that declares
  `Interactions` but no `Flow` is **browserless**: no Chromium is launched, no video/screenshots
  are produced. A mixed story may take both — they share one `StepRecorder`, so steps interleave
  in call order into a single log and definition hash.
- **`Commands`** — the shell-command recording facade: `run("git clone {} repo", url)` runs an argv
  with **no shell** (the template is tokenized first, so a value is always exactly one argument),
  `sh(script)` is the documented `sh -c` escape hatch, `file`/`script` write fixtures into the
  story's private scratch dir (`UserflowPaths.workDir`, created and wiped lazily), `expectExit`/
  `expectAnyExit`/`timeout`/`env`/`in` shape the next command, and `redact(secret)` masks a value
  out of everything the report publishes. Every command's step, merged stdout+stderr transcript and
  exit code are recorded **before** the exit assertion, so a failing command's output survives into
  the bundle. Browserless like `Interactions`, and shares the same `StepRecorder`.
- **`UserStoryExtension`** — the JUnit 5 extension: browser/video lifecycle (browser only when the
  story asks for a `Flow`), `Flow`/`Interactions`/`Commands`/`UserflowContext` injection, outcome
  tracking, report emission, the passed-story registry, and the `ExecutionCondition` that skips a
  dependent whose precondition didn't pass.
- **`UserflowClassOrderer`** — topological class ordering over the precondition + runs-after graph.
- **`UserflowContext`** — the shared key→value store for dependency handoff.
- **`report/`** — the canonical `UserflowReport` model (incl. `interactions`, `commands` and
  `files`), the JSON + markdown + HTML renderers (the markdown gets an `## Interactions` mermaid
  `sequenceDiagram` section; a command's transcript and a written file's dump render under their own
  step), and `ReportAssertions` / `Slugs` / `Hashing` / `UserflowPaths`. The optional list
  components are **null, never an empty list**, when a story recorded none — a story that uses none
  of these facades keeps the exact sidecar bytes it had before they existed.
- **Utilities**: `UserflowTarget` (base URL + reachability self-skip), `HarnessResources` (bundled
  test-page URLs), `HarnessHttpServer` (recording local HTTP server for service harness stories),
  `Urls`.

## Rules

- **No product stories here.** Real qits user stories live in the sibling
  [`userflows`](../userflows/AGENTS.md) module, organized by domain (`…userflows.project`,
  `…userflows.projectrepository`, …). This module's `src/test` holds only the framework's own
  **self-test harness stories** — `*Test` classes under `…userflows.harness` that drive a bundled
  static page (no running qits) to cover step recording, the failure path, the command facade, and
  the ordering/skip/runs-after dependency machinery on every default build — plus a handful of
  plain unit tests for the pure pieces a story cannot pin down precisely (`CommandLineTest`,
  `MarkdownReportRendererTest`, `HtmlReportRendererTest`, `SiteIndexWriterTest`). Harness stories
  must stay portable: `/bin/sh` and POSIX tools only, no running qits.
- **This split is temporary.** It keeps the framework separate from the stories ahead of the epic's
  part 4, which extracts `qits-userflows` into a standalone repository so qits-managed projects can
  depend on it. Keep the framework free of any coupling to qits internals (URL-only), so the
  extraction stays a move, not a rewrite.
- Authoring conventions (how to write a story, the `Flow` API, dependent flows, running) are
  documented where the stories live — see [`userflows/AGENTS.md`](../userflows/AGENTS.md) and the
  `userflows` skill. The report contract lives in
  `docs/epics/qits-userflows/features/2026-07-19_qits-userflows.md`.
- Playwright is pinned (`playwright.version`) to the Chromium baked into `docker/qits/Dockerfile`;
  bump both in lockstep. Never needs `-Dqits.variant`.
- **Releasing this library**: push a branch to the platform git host and release it through
  `POST /workspaces/api/branches/release?projectId=qits&repositoryName=qits-userflows` (or the
  workspace UI) — the door takes the public `(projectId, repoName)` pair, never a storage id. A
  library release merges into `main` only — `promotions` comes back empty because nothing
  deploys — and the release pipeline publishes `eu.wohlben.qits:qits-userflows` under the minted
  CalVer version to the platform Maven repository. Consumers pick it up via their upstream bump
  trains. See release-workflow-in-workspaces.md in the qits-qits wrapper.
