# The report contract

`userflow.json` is the canonical output of a story run — the sidecar every other rendering is
derived from. The markdown and the HTML are *renderers over this file*; they never read each other,
and a future consumer (an uploader, a diff tool, a docs site) consumes this file rather than either
of them. That is why its shape is a contract and not an implementation detail.

The model is
[`UserflowReport`](../src/main/java/eu/wohlben/qits/userflows/report/UserflowReport.java);
this document states the rules that record enforces and the reasons behind them.

## Field order

Top level, exactly:

    story, slug, category, description, steps, definitionHash, network, networkHash,
    commands, files, screenshots, video, outcome

Order is pinned with `@JsonPropertyOrder` on every record in the model, nested ones included:

| record        | order                                                              |
| ------------- | ------------------------------------------------------------------ |
| `Step`        | `id`, `line`                                                        |
| `NetworkEdge` | `kind`, `from`, `to`, `label`, `declared`                           |
| `Command`     | `step`, `command`, `exitCode`, `output`, `outputPath`, `truncated`  |
| `WrittenFile` | `step`, `path`, `contentPath`                                       |
| `Screenshot`  | `path`, `label`, `step`, `width`, `height`, `contentHash`           |
| `Video`       | `path`, `width`, `height`                                           |

A sidecar is a document reviewers *diff*. Field order that moved with the serializer would turn
every review into noise, so it is stated once — here and in the annotations — and never inferred.

## Null, never empty

Every optional component — `network` + `networkHash`, `commands`, `files`, `category`,
`description`, `video` — is `null` when a story produced none, and `@JsonInclude(NON_NULL)` drops
the field entirely. Never `[]`, never `""`, never a hash of nothing.

The rule exists so **growing the model cannot rewrite an unrelated story's bytes**: a story that
uses none of these facades keeps the exact sidecar it had before the fields existed. The same
discipline covers the per-record flags — `Command.truncated` and `NetworkEdge.declared` are
`Boolean` and present only when `true`, so the overwhelming majority of records carry neither.

`steps` and `screenshots` are the two exceptions: they are always present, `screenshots` as `[]`
for a browserless story, because a story with no steps is not a story.

## No wall-clock values

Nothing in the sidecar is a timestamp or a duration. Not a command's runtime, not the video's
length, not when the run happened. A wall-clock value would make the canonical document differ on
every run, and the whole point of the sidecar is that an unchanged story produces unchanged bytes.
What a run *proves* is its evidence — output, exit codes, screenshots, edges — not how long the
machine took.

## Hashes

Every hash in the report is the string `sha256:` followed by 64 lowercase hex digits
([`Hashing`](../src/main/java/eu/wohlben/qits/userflows/report/Hashing.java)). The prefixed form is
what an uploader stamps as `qits.userflow.hash` / `qits.diff.hash`, and it is what
`ReportAssertions.assertComplete` matches against.

- **`definitionHash`** covers the story's *fingerprint* lines — verbs, selectors and labels, no
  typed values, no failure line — joined by `\n`. It answers "did the story change?", computed from
  what the story does rather than from its source text. Step **ids** are labels and are not in it.
- **`screenshots[].contentHash`** is over the PNG bytes.
- **`networkHash`** is over the edge set; see below.

## Linking is by step id

A screenshot, a command and a written file each carry the `id` of the step that produced it, and a
renderer places the artifact under that step by looking the id up. The link is never positional: a
mid-story screenshot lands under its own step, and an author renaming a step with `.as("…")` moves
the artifact with it.

Ids are `step-NN` by default (matching a screenshot's file-name prefix) or an explicit author id.
`step-<n>` is reserved for auto-assignment, so an explicit id can never collide with the one a
later step will receive.

## The network section

`network` is the story's dependency graph and `networkHash` its fingerprint. The two travel
together: both present, or both absent.

Each edge is `(kind, from, to, label)` plus the optional `declared` flag. Direction is always **who
initiated** — data may flow both ways on a socket, but the dependency is the dial. Labels are
template-shaped: component names and `{id}` / `{digest}` placeholders, never hosts, ports or
run-local values, because the label is hashed.

### Canonical form

The list in the sidecar is already canonical, and `networkHash` is computed over exactly it:

1. **Dedupe** on the `(kind, from, to, label)` quadruple. An edge that is both observed and
   declared counts as observed — evidence beats declaration.
2. **Sort** by `kind`, then `from`, then `to`, then `label`.
3. Join each edge's four fields with `\t`, join the edges with `\n`, and `sha256` the UTF-8 bytes
   of the result.

`declared` is **excluded** from the hash. The hash states *which dependencies exist*; provenance
changing — a declared edge becoming observable once a tap is written — should not read as the
network having changed.

Because the list is stored canonically, `Hashing.networkHash(report.network())` recomputes the
sidecar's own `networkHash`. `assertComplete` does exactly that, which is how a sorting or dedup
regression is caught rather than merely hashed over.

### Observed and declared

Almost every edge arrives **passively**: taps and cumulative recordings feed
[`NetworkCapture`](../src/main/java/eu/wohlben/qits/userflows/NetworkCapture.java), the extension
drains it once per story, and the diagram is drawn from what was seen. Observed labels are scrubbed
by [`Labels`](../src/main/java/eu/wohlben/qits/userflows/Labels.java) — whole UUID / long-hex /
numeric path segments and `sha256:…` literals become `{id}` / `{digest}` — and then by an optional
`NetworkCapture.labelNormalizer`.

A **query value** is scrubbed too, but only where it can only have been generated: a UUID or a
32+-character hex run after `=` becomes `{id}` / `{digest}`. Everything else after an `=` survives
— a branch name, a dotted version, and **pure digits**, which are deliberately left alone although
a bare numeric *path segment* is rewritten. The asymmetry is the rule stated in one line:
`/tasks/42` is a row this run created, `?limit=10` is a number the story typed.

`declared: true` marks the one deliberate exception: an edge the story *knows* but no tap can see —
a spawned process talked to over pipes, a JDBC store, a docker socket — recorded with
`Network.declare`. Declared labels are author-written literals and are not scrubbed — they are
*checked*: `declare` runs all four fields through `Labels.scrub` and refuses one the scrubber would
rewrite, naming the field and the template shape it should have had. Unscrubbed keeps an author's
literal readable; the check is what stops one interpolated id moving `networkHash` on every run.

The flag is a provenance statement, and both renderers are required to honour it: the markdown
appends ` [declared]` inside the edge's label **and** emits a `linkStyle` line for the edge, the
HTML draws the edge in the muted colour with its own arrowhead. **A claim never renders like
evidence.** Any consumer that redraws this data owes the same distinction.

Those `linkStyle` lines are part of the canonical mermaid rendering, not decoration: one per
declared edge, `    linkStyle <i> stroke:#8b949e,stroke-dasharray:3 3`, emitted after every edge
line and in edge order. `<i>` is the edge's 0-based position in the (already canonical) list, which
is its mermaid link index because node declarations create no links — so the same edge set always
renders the same bytes, and `assertComplete`'s every-line-present check covers the styles exactly as
it covers the arrows. The grey is `NetworkDiagramSvg`'s declared colour, so the two renderings mute
a claim identically; the label suffix stays because a label is readable in a diff and in a terminal,
where no arrow is ever drawn.

Redaction reaches edges too: every field of every edge passes through the story's masker (the one
`Commands.redact` builds) before it reaches the sidecar, so a credential a tap saw on the wire is
masked in the label exactly as it is in a transcript.

### The kind vocabulary

`kind` is an **open** vocabulary — an unrecognised kind still renders, prefixed into the label,
rather than failing. The known shapes are:

| kind      | means                                                          |
| --------- | -------------------------------------------------------------- |
| `http`    | a plain HTTP request/response                                   |
| `event`   | a delivered event or pushed frame (the pusher initiates)        |
| `socket`  | a long-lived socket connection — websocket, unix socket         |
| `package` | a package-manager upload or download, whatever its transport    |
| `process` | a spawned process talked to over its pipes                      |
| `jdbc`    | a database the component talks to directly                      |

This vocabulary deliberately **diverges** from the one in `network-capture-proxy-plan.md`, which
treats WebSocket as a protocol category of its own. That plan describes a capture proxy: it records
what a wire carried, so its categories are transport categories. This is a report about a story —
`socket` covers every long-lived dialled connection, because to a reader of a dependency map the
interesting fact is that the component holds a connection open to another, not which RFC the
handshake followed. A capture source that speaks the plan's vocabulary maps its `websocket` onto
`socket` on the way in.

## Renderers

- `userflow.json` — this document, written by `JsonReportWriter`, pretty-printed 2-space so a
  review diff is readable.
- `user-story.md` — the portable rendering. Steps as an indented block with artifacts interleaved,
  the network as a mermaid `graph LR` block after the steps, then the video link.
- `index.html` — the browser rendering, self-contained and script-free: styling inline, the network
  drawn as inline SVG by `NetworkDiagramSvg` rather than by a script, every reference relative.
- `index.html` at the bundle root — the site index, rewritten after every story emit, carrying the
  aggregate network of every sidecar found. Its union dedupes on the same quadruple with the same
  observed-wins rule, and attributes each edge to the stories that produced it.
