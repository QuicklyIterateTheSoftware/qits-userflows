# qits-userflows/

The **userflows framework** — everything that runs a user story except the stories themselves. A
plain library jar (package root `eu.wohlben.qits.userflows`) on the `userflows` module's test
classpath. It depends on **none** of the app modules; it drives qits by URL only.

## What lives here

- **Annotations**: `@UserStory` (with an optional `category` — a categorized story emits under
  `<category-slug>/<story-slug>/`, so the directory layout carries the grouping),
  `@UserStoryDescription`, `@ExpectedFailure`, `@UserflowPrecondition`, `@UserflowRunsAfter`.
- **`Flow`** — the step-recording facade over Playwright's `Page` (every verb records a step).
- **`Interactions`** — the narrative facade: `note(line)` records a narrative step, `as(id)` names
  the step just recorded. That is all it does; service-to-service traffic is **not** narrated here
  (see the network facades below). Its old `happened(from, to, description)` verb was **removed in
  2026.829** — an interaction is observed now, never narrated. A story method that declares
  `Interactions` but no `Flow` is
  **browserless**: no Chromium is launched, no video/screenshots are produced. A mixed story may
  take both — they share one `StepRecorder`, so steps interleave in call order into a single log
  and definition hash.
- **The network**, which arrives **passively** rather than being narrated:
  - **`NetworkCapture`** — the JVM-wide registry test infrastructure feeds. `source(id, supplier)`
    registers a **cumulative** recording (a mock's request log, an access log) read lazily at story
    end, with a per-source cursor so each recorded entry lands in exactly one story — which makes
    **story order semantically load-bearing**: pre-story traffic (a startup JWKS fetch) attaches to
    whichever story drains first, so a class owning startup traffic pins its method order and
    `assertEdge`s that edge on the story that claims it;
    `observe(kind, from, to, label)` appends one edge immediately, for taps that see traffic as it
    happens. `actor(name)` is the sticky narrative initiator a tap reads for an edge's `from`
    (reset to the default at every story start) — a tap living inside an async fixture must read
    `actor()` on the **story thread** at the moment of the call, never on a callback thread, or the
    edge inherits whatever actor is current when the response lands. `labelNormalizer` composes
    over the default scrubbing. Async far-side traffic (a fire-and-forget forward) races the
    story-end drain: the story must await the far side's recording (a relative count poll) before
    returning, or the edge lands in the next story's diagram.
  - **`NetworkEdge`** — the captured record: `(kind, from, to, label)`, with `http` sugar and the
    kind constants (`http`/`event`/`socket`/`package`/`process`/`jdbc`, an **open** vocabulary).
    Direction is always *who initiated*.
  - **`NetworkTaps`** — the taps the framework **ships**, so a story class never writes one.
    `restAssured(service)` / `restAssured(service, ignoredPath)` installs the RestAssured filter
    four repos had hand-copied as a local `StoryNetworkFilter`: every request a story makes becomes
    `<actor> -> <service>`, labelled `METHOD <scrubbed path> -> <status>`. Idempotent per service,
    because `RestAssured.filters` **appends**. The per-repo copy is **legacy** — a new story class
    calls this instead, and an existing copy is deleted when its repo next touches its stories.
    RestAssured is an `<optional>` dependency here: a module that never calls the tap resolves this
    jar exactly as before, one that calls it without the library fails at link time on the call.
    Install only from a **story class's** `@BeforeAll` (`RestAssured.filters` is JVM-global across
    the whole failsafe fork, so nothing else has a story border around it), and **a story class
    that installs a tap must pin at least one edge** (`assertEdge` / `assertEdgeCount`) — otherwise
    a `@BeforeAll` dropped in a later edit silently empties every diagram in the class and every
    remaining assertion still passes. The default skip is any path with a `/q/` segment; verify it
    against your own `quarkus.http.non-application-root-path` before relying on it.
  - **`Labels`** — the default scrubber that rewrites run-local path segments (UUIDs, long hex,
    bare numeric ids) and `sha256:…` literals into `{id}` / `{digest}`, so an observed label is
    template-shaped and the hash below does not move with every generated id. Since 2026.829 it
    also rewrites a **query value** that is a UUID or a long hex run (`?project=<uuid>` →
    `?project={id}`); ordinary values — words, dotted versions, and deliberately **pure digits**,
    since a `limit=10` is authored rather than generated — are left exactly as they were.
  - **`Network`** — the per-story facade, for the two things passivity cannot do: `declare(kind,
    from, to, label)` records an edge **no tap can see** (a spawned process over pipes, a JDBC
    store, a docker socket) — the escape hatch, marked `"declared": true` in the sidecar and drawn
    distinctly, so a claim never renders like evidence — and `actor(name)` names the initiator.
    Neither records a step: edges describe traffic around the story, not what the author did, so
    they sit outside the step log and the definition hash. Their stability contract is the
    **`networkHash`** over the canonically sorted, deduplicated edge set (`declared` excluded).
    Declaring `Network` without a `Flow` keeps a story browserless. A declared edge's four fields
    are **not** scrubbed — an author's literal must stay readable — so they are **checked**
    instead: a field `Labels.scrub` would rewrite is an `IllegalArgumentException` naming the field
    and the shape it should have had. Without the guard an interpolated id moves the `networkHash`
    on every run, and the only symptom is a hash that never settles.
- **`Commands`** — the shell-command recording facade: `run("git clone {} repo", url)` runs an argv
  with **no shell** (the template is tokenized first, so a value is always exactly one argument),
  `sh(script)` is the documented `sh -c` escape hatch, `file`/`script` write fixtures into the
  story's private scratch dir (`UserflowPaths.workDir`, created and wiped lazily), `expectExit`/
  `expectAnyExit`/`timeout`/`env`/`in` shape the next command, and `redact(secret)` masks a value
  out of everything the report publishes. Every command's step, merged stdout+stderr transcript and
  exit code are recorded **before** the exit assertion, so a failing command's output survives into
  the bundle. Browserless like `Interactions`, and shares the same `StepRecorder`.
- **`UserStoryExtension`** — the JUnit 5 extension: browser/video lifecycle (browser only when the
  story asks for a `Flow`), `Flow`/`Interactions`/`Network`/`Commands`/`UserflowContext` injection,
  outcome tracking, report emission (draining the capture registry **once** per story, after
  redaction masking is registered), the passed-story registry, and the `ExecutionCondition` that
  skips a dependent whose precondition didn't pass.
- **`UserflowClassOrderer`** — topological class ordering over the precondition + runs-after graph.
- **`UserflowContext`** — the shared key→value store for dependency handoff.
- **`report/`** — the canonical `UserflowReport` model (incl. `network` + `networkHash`, `commands`
  and `files`), the JSON + markdown + HTML renderers (the markdown gets a `## Network` mermaid
  `graph LR` section via `NetworkMermaid` — where a declared edge carries both a ` [declared]`
  label suffix and a trailing `linkStyle <i> stroke:#8b949e,stroke-dasharray:3 3` line, so the
  arrow itself is muted and dashed like the SVG's — the HTML the same graph drawn as a script-free
  inline SVG via `NetworkDiagramSvg`; a command's transcript and a written file's dump render
  under their own step), the `SiteIndexWriter` (which rescans the sidecars and draws the
  **aggregate** network of the whole suite), and `ReportAssertions` / `Slugs` / `Hashing` /
  `UserflowPaths`. The optional list components are **null, never an empty list**, when a story
  recorded none — a story that uses none of these facades keeps the exact sidecar bytes it had
  before they existed. `ReportAssertions` is the self-test surface: `assertComplete`,
  `assertEdge(slug, kind, from, to, label)` (which checks the sidecar quadruple *and* the exact
  mermaid line), `assertDeclaredEdge`, `assertCommand`, `assertNotLeaked`, … plus the four
  **negative** network claims, which are what a presence check cannot say: `assertEdgeCount`,
  `assertNoEdgesFrom` ("nothing left this process"), `assertNoEdgesTo` ("nothing reached the thing
  being protected") and `assertOnlyEdgesFrom(slug, actors…)` — the actor set is the story's promise
  even where the request *count* belongs to the client (npm's update-notifier fetches a package
  nobody asked for). The last one takes varargs in the flat spelling and a `List` in the
  categorized one, because two varargs overloads differing only by a leading `String` are ambiguous
  at every call site.
- **Utilities**: `UserflowTarget` (base URL + reachability self-skip), `HarnessResources` (bundled
  test-page URLs), `HarnessHttpServer` (recording local HTTP server for service harness stories; its
  `servedRequests()` is a ready-made `NetworkCapture` source), `Urls`.

## Rules

- **No product stories here.** Real qits user stories live in the sibling
  [`userflows`](../userflows/AGENTS.md) module, organized by domain (`…userflows.project`,
  `…userflows.projectrepository`, …). This module's `src/test` holds only the framework's own
  **self-test harness stories** — `*Test` classes under `…userflows.harness` that drive a bundled
  static page (no running qits) to cover step recording, the failure path, the command facade, the
  passively captured network, and the ordering/skip/runs-after dependency machinery on every default
  build — plus a handful of plain unit tests for the pure pieces a story cannot pin down precisely
  (`CommandLineTest`, `LabelsTest`, `NetworkTest`, `NetworkTapsTest`, `MarkdownReportRendererTest`,
  `HtmlReportRendererTest`, `JsonReportWriterTest`, `NetworkDiagramSvgTest`, `ReportAssertionsTest`,
  `SiteIndexWriterTest`). Harness stories must stay portable: `/bin/sh` and POSIX tools only, no
  running qits. **Never** call `NetworkCapture.drain()` from a unit test: the registry is JVM-global
  and a stray drain steals edges from whichever harness story runs next in the same sequential fork
  — cursor semantics are covered by the two ordered
  stories in `ServiceInteractionHarnessTest`. The same caution governs the other two JVM-globals a
  unit test may touch: `ReportAssertionsTest` repoints `qits.userflows.output-dir` at a temp bundle
  and `NetworkTapsTest` installs a RestAssured filter — each restores what it changed, in
  `@AfterEach` / `finally`, so no harness story inherits it. **No rest-assured-driven harness
  story** lives here: the tap needs a service to call, which is a service repository's story.
- **This split is temporary.** It keeps the framework separate from the stories ahead of the epic's
  part 4, which extracts `qits-userflows` into a standalone repository so qits-managed projects can
  depend on it. Keep the framework free of any coupling to qits internals (URL-only), so the
  extraction stays a move, not a rewrite.
- Authoring conventions (how to write a story, the `Flow` API, dependent flows, running) are
  documented where the stories live — see [`userflows/AGENTS.md`](../userflows/AGENTS.md) and the
  `userflows` skill. The report contract — field order, the null-never-empty rule, the hashes and
  the network section's canonical form — lives in
  [`docs/report-contract.md`](docs/report-contract.md).
- Playwright is pinned (`playwright.version`) to the Chromium baked into `docker/qits/Dockerfile`;
  bump both in lockstep. Never needs `-Dqits.variant`.
- **Releasing this library**: push a branch to the platform git host and open a **release request**
  against this repository — `POST /projects/api/repositories/<repoId>/release-requests` with
  `{"branch","summary"}`, or the Release Requests view. Nothing merges and nothing releases at that
  call: qits-projects folds `main` and the named branch onto a backing branch `release/<id>`,
  `.config/qits/ci-event-release-request.yml` runs the QA build over that fold, and a **gating green
  verdict on the fold** is what lets Auto Release stamp the CalVer, bump the manifests and tag.
  `.config/qits/ci-event-release.yml` then builds that tag and publishes
  `eu.wohlben.qits:qits-userflows` under the minted version to the platform Maven repository. This
  repository carries no `deployments.yml`, so nothing deploys and `main` is finalized at the release
  itself rather than after a deployment. Consumers pick the version up via their upstream bump
  trains. The flow itself is qits-projects' to document.
