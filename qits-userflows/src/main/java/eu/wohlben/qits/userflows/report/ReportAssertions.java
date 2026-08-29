package eu.wohlben.qits.userflows.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reading + asserting helpers over an emitted report — the framework's own self-test surface. A
 * harness story's {@code @AfterAll} companion uses these to prove the framework produced a
 * complete, consistent bundle. Lives in {@code src/main} (not {@code src/test}) so the
 * src/test-is-stories-only rule stays absolute: a story class carries no plumbing, only calls into
 * here.
 */
public final class ReportAssertions {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ReportAssertions() {}

  /** Parse a story's canonical {@code userflow.json}. */
  public static UserflowReport read(String slug) {
    return read("", slug);
  }

  /** The categorized spelling — {@code <category-slug>/<slug>/userflow.json}. */
  public static UserflowReport read(String categorySlug, String slug) {
    Path json = UserflowPaths.reportDir(categorySlug, slug).resolve(JsonReportWriter.FILE_NAME);
    try {
      return MAPPER.readValue(json.toFile(), UserflowReport.class);
    } catch (IOException e) {
      throw new UncheckedIOException("no readable " + json, e);
    }
  }

  /** The rendered {@code user-story.md} text. */
  public static String markdown(String slug) {
    return markdown("", slug);
  }

  public static String markdown(String categorySlug, String slug) {
    try {
      return Files.readString(
          UserflowPaths.reportDir(categorySlug, slug).resolve(MarkdownReportRenderer.FILE_NAME),
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("no readable user-story.md for " + slug, e);
    }
  }

  /**
   * Assert a fully-formed passing report: the sidecar + markdown exist, the definition hash is
   * well-formed, every recorded step line appears (in order) in the markdown, each screenshot file
   * exists with plausible dimensions and a content hash, and {@code recording.webm} is non-empty.
   */
  public static void assertComplete(String slug, String expectedOutcome) {
    assertComplete("", slug, expectedOutcome);
  }

  /** The categorized spelling of {@link #assertComplete(String, String)}. */
  public static void assertComplete(String categorySlug, String slug, String expectedOutcome) {
    UserflowReport report = read(categorySlug, slug);
    Path dir = UserflowPaths.reportDir(categorySlug, slug);

    assertEquals(expectedOutcome, report.outcome(), "outcome");
    assertTrue(
        report.definitionHash().matches("sha256:[0-9a-f]{64}"),
        () -> "definition hash malformed: " + report.definitionHash());

    // Every step line appears in the markdown Steps section, in the recorded order. Start scanning
    // at the "## Steps" header so a step line that also occurs in the description can't satisfy it.
    String md = markdown(categorySlug, slug);
    int stepsHeader = md.indexOf("## Steps");
    assertTrue(stepsHeader >= 0, "user-story.md has no ## Steps section");
    int cursor = stepsHeader;
    for (UserflowReport.Step step : report.steps()) {
      int at = md.indexOf(step.line(), cursor);
      assertTrue(at >= 0, () -> "step missing or out of order in markdown: " + step.line());
      cursor = at + step.line().length();
    }

    for (UserflowReport.Screenshot shot : report.screenshots()) {
      Path png = dir.resolve(shot.path());
      assertTrue(Files.isRegularFile(png), () -> "missing screenshot " + png);
      assertTrue(shot.width() > 0 && shot.height() > 0, () -> "implausible dimensions " + shot);
      assertTrue(
          shot.contentHash().matches("sha256:[0-9a-f]{64}"),
          () -> "content hash malformed: " + shot.contentHash());
      // the link is by explicit step id and resolves to the screenshot step that produced it, not
      // inferred from position — so the association holds for a mid-story screenshot too
      UserflowReport.Step linked =
          report.steps().stream()
              .filter(step -> step.id().equals(shot.step()))
              .findFirst()
              .orElse(null);
      assertTrue(linked != null, () -> "screenshot references unknown step id: " + shot);
      assertTrue(
          linked != null && linked.line().startsWith("screenshot "),
          () -> "screenshot not linked to a screenshot step: " + shot);
    }

    if (report.network() == null) {
      assertTrue(
          report.networkHash() == null,
          "networkHash present without a network section — the two travel together");
      assertTrue(
          !md.contains("## Network"),
          "user-story.md has a Network section but the sidecar records no edges");
    } else {
      assertTrue(!report.network().isEmpty(), "network is empty — the contract is null, never []");
      // The hash is recomputable from the sidecar's own edges — which also proves the list is
      // stored in its canonical (sorted, deduplicated) form.
      assertEquals(
          Hashing.networkHash(report.network()),
          report.networkHash(),
          "networkHash does not match the sidecar's own edge set");
      int networkHeader = md.indexOf("## Network");
      assertTrue(networkHeader >= 0, "sidecar records edges but user-story.md has no ## Network");
      assertTrue(
          md.indexOf("graph LR", networkHeader) >= 0,
          "user-story.md Network section has no graph LR block");
      for (String line : NetworkMermaid.lines(report.network())) {
        assertTrue(
            md.indexOf(line, networkHeader) >= 0,
            () -> "network diagram line missing from markdown: " + line);
      }
    }

    if (report.commands() != null) {
      for (UserflowReport.Command command : report.commands()) {
        // the link is by explicit step id, same discipline as a screenshot's, and it must land on
        // a step the command facade actually recorded — not on a click or a note
        UserflowReport.Step linked = stepById(report, command.step());
        assertTrue(linked != null, () -> "command references unknown step id: " + command);
        assertTrue(
            linked != null
                && (linked.line().startsWith("run ") || linked.line().startsWith("sh ")),
            () -> "command not linked to a run/sh step: " + command);
        if (command.outputPath() != null) {
          Path transcript = dir.resolve(command.outputPath());
          assertTrue(Files.isRegularFile(transcript), () -> "missing transcript " + transcript);
        }
      }
    }

    if (report.files() != null) {
      for (UserflowReport.WrittenFile file : report.files()) {
        UserflowReport.Step linked = stepById(report, file.step());
        assertTrue(linked != null, () -> "written file references unknown step id: " + file);
        assertTrue(
            linked != null && linked.line().startsWith("write "),
            () -> "written file not linked to a write step: " + file);
        Path dump = dir.resolve(file.contentPath());
        assertTrue(Files.isRegularFile(dump), () -> "missing file dump " + dump);
      }
    }

    if (report.video() != null) {
      Path webm = dir.resolve(report.video().path());
      assertTrue(sizeOf(webm) > 0, () -> "recording.webm missing or empty: " + webm);
    }
  }

  /**
   * Assert the failure path: {@code outcome: "failed"}, a partial step log (the steps that ran
   * before the failure), and an appended {@code FAILED: …} final step line.
   */
  public static void assertFailedWithPartialLog(String slug) {
    UserflowReport report = read(slug);
    assertEquals(UserflowReport.FAILED, report.outcome(), "outcome");
    assertTrue(
        report.steps().stream().anyMatch(step -> step.line().startsWith("FAILED:")),
        "expected an appended FAILED step line, got: " + report.steps());
    assertTrue(
        report.steps().stream().anyMatch(step -> !step.line().startsWith("FAILED:")),
        "expected at least one recorded step before the failure, got: " + report.steps());
  }

  /**
   * Assert the sidecar records the network edge {@code (kind, from, to, label)} — observed or
   * declared — and that the markdown draws exactly the line {@link MarkdownReportRenderer} owes
   * that edge.
   */
  public static void assertEdge(String slug, String kind, String from, String to, String label) {
    assertEdge("", slug, kind, from, to, label);
  }

  /** The categorized spelling of {@link #assertEdge(String, String, String, String, String)}. */
  public static void assertEdge(
      String categorySlug, String slug, String kind, String from, String to, String label) {
    UserflowReport.NetworkEdge match = findEdge(categorySlug, slug, kind, from, to, label);
    String md = markdown(categorySlug, slug);
    String line = NetworkMermaid.edgeLine(read(categorySlug, slug).network(), match);
    assertTrue(md.contains(line), () -> "edge missing from the mermaid diagram: " + line);
  }

  /**
   * Assert the sidecar records <b>exactly</b> {@code count} network edges. {@link #assertEdge}
   * proves presence; this is the absence half — a stray edge (a probe a tap's skip heuristic
   * missed, a leaked actor) is invisible to presence checks alone, so a story that pins its edges
   * pins the count too.
   */
  public static void assertEdgeCount(String slug, int count) {
    assertEdgeCount("", slug, count);
  }

  /** The categorized spelling of {@link #assertEdgeCount(String, int)}. */
  public static void assertEdgeCount(String categorySlug, String slug, int count) {
    UserflowReport report = read(categorySlug, slug);
    int actual = report.network() == null ? 0 : report.network().size();
    assertEquals(
        count,
        actual,
        () -> "edge count of " + slug + "; recorded: " + report.network());
  }

  /**
   * Assert no recorded edge <b>originates</b> at {@code from} — the direct spelling of "nothing
   * left this process", the sharpest claim a gatekeeping story makes. Unlike a total count it
   * survives unrelated edges being added to the story later.
   */
  public static void assertNoEdgesFrom(String slug, String from) {
    assertNoEdgesFrom("", slug, from);
  }

  /** The categorized spelling of {@link #assertNoEdgesFrom(String, String)}. */
  public static void assertNoEdgesFrom(String categorySlug, String slug, String from) {
    UserflowReport report = read(categorySlug, slug);
    if (report.network() == null) {
      return;
    }
    assertTrue(
        report.network().stream().noneMatch(edge -> edge.from().equals(from)),
        () -> "expected no edges from " + from + " in " + slug + "; recorded: " + report.network());
  }

  /**
   * Assert no recorded edge <b>arrives</b> at {@code to} — the mirror of {@link
   * #assertNoEdgesFrom}, and the direct spelling of "nothing reached the thing being protected".
   * The motivating claim is a story about a boundary rather than about a caller: an
   * unauthenticated request must not have reached the store, a public surface must not have
   * dialled the internal peer. Presence checks cannot make that claim and a total count cannot
   * either — a count says how much happened, not what was spared — so this is the one that
   * survives the story growing unrelated edges later.
   */
  public static void assertNoEdgesTo(String slug, String to) {
    assertNoEdgesTo("", slug, to);
  }

  /** The categorized spelling of {@link #assertNoEdgesTo(String, String)}. */
  public static void assertNoEdgesTo(String categorySlug, String slug, String to) {
    UserflowReport report = read(categorySlug, slug);
    if (report.network() == null) {
      return;
    }
    assertTrue(
        report.network().stream().noneMatch(edge -> edge.to().equals(to)),
        () -> "expected no edges to " + to + " in " + slug + "; recorded: " + report.network());
  }

  /**
   * Assert every recorded edge originates at one of {@code actors} — nobody else initiated
   * anything. The motivating claim is a flow whose request <b>count</b> belongs to the client
   * rather than to this repository (npm's update-notifier fetches a package of its own; a
   * {@code deploy-file} is eleven requests including a checksum sidecar per algorithm), so {@link
   * #assertEdgeCount} would pin a number the story does not promise — while the <i>set of
   * initiators</i> is still exactly the story's promise, and a leaked actor (a tap reading the
   * default {@code "a caller"} because a story forgot to name itself) is precisely what it catches.
   *
   * <p>It states nothing about presence, and a story that recorded no edges at all passes
   * vacuously: pair it with an {@link #assertEdge} on the edge that <i>is</i> the story.
   */
  public static void assertOnlyEdgesFrom(String slug, String... actors) {
    assertOnlyEdgesFrom("", slug, List.of(actors));
  }

  /**
   * The categorized spelling of {@link #assertOnlyEdgesFrom(String, String...)}, taking the actors
   * as a list — two varargs spellings differing only by a leading {@code String} would be
   * ambiguous at every call site, so the categorized one names its actors explicitly.
   */
  public static void assertOnlyEdgesFrom(String categorySlug, String slug, List<String> actors) {
    UserflowReport report = read(categorySlug, slug);
    if (report.network() == null) {
      return;
    }
    for (UserflowReport.NetworkEdge edge : report.network()) {
      assertTrue(
          actors.contains(edge.from()),
          () ->
              "unexpected initiator '"
                  + edge.from()
                  + "' in "
                  + slug
                  + "; expected only "
                  + actors
                  + "; recorded: "
                  + report.network());
    }
  }

  /** Assert the edge exists <b>and</b> is an author-declared one, not an observation. */
  public static void assertDeclaredEdge(
      String categorySlug, String slug, String kind, String from, String to, String label) {
    UserflowReport.NetworkEdge match = findEdge(categorySlug, slug, kind, from, to, label);
    assertTrue(
        Boolean.TRUE.equals(match.declared()),
        () -> "edge is observed, not declared: " + match);
  }

  private static UserflowReport.NetworkEdge findEdge(
      String categorySlug, String slug, String kind, String from, String to, String label) {
    UserflowReport report = read(categorySlug, slug);
    assertTrue(report.network() != null, () -> "no network edges in " + slug + "'s sidecar");
    UserflowReport.NetworkEdge match =
        report.network().stream()
            .filter(
                edge ->
                    edge.kind().equals(kind)
                        && edge.from().equals(from)
                        && edge.to().equals(to)
                        && edge.label().equals(label))
            .findFirst()
            .orElse(null);
    assertTrue(
        match != null,
        () ->
            "no "
                + kind
                + " edge "
                + from
                + " -> "
                + to
                + ": "
                + label
                + " in "
                + slug
                + "; recorded: "
                + read(categorySlug, slug).network());
    return match;
  }

  /**
   * Assert some step carries the explicit id {@code id} (e.g. one assigned via {@code Flow.as}).
   */
  public static void assertStepId(String slug, String id) {
    assertStepId("", slug, id);
  }

  public static void assertStepId(String categorySlug, String slug, String id) {
    UserflowReport report = read(categorySlug, slug);
    assertTrue(
        report.steps().stream().anyMatch(step -> step.id().equals(id)),
        () -> "no step with id '" + id + "' in " + report.steps());
  }

  /**
   * Assert the sidecar records a command whose resolved command line contains {@code
   * commandSubstring} and which exited with {@code exitCode}. The substring — rather than an exact
   * line — is what keeps a story assertion readable when the command carries an absolute scratch
   * path that changes per run.
   */
  public static void assertCommand(String slug, String commandSubstring, int exitCode) {
    assertCommand("", slug, commandSubstring, exitCode);
  }

  /** The categorized spelling of {@link #assertCommand(String, String, int)}. */
  public static void assertCommand(
      String categorySlug, String slug, String commandSubstring, int exitCode) {
    UserflowReport report = read(categorySlug, slug);
    UserflowReport.Command match = findCommand(report, commandSubstring);
    assertTrue(
        match != null,
        () -> "no command containing '" + commandSubstring + "' in " + slug + "'s sidecar");
    assertEquals(
        exitCode,
        match == null ? Integer.MIN_VALUE : match.exitCode(),
        () -> "exit code of the command containing '" + commandSubstring + "'");
  }

  /**
   * Assert the transcript of the command containing {@code commandSubstring} contains {@code
   * outputSubstring} — checked against the <b>emitted artifact</b>, not the inline excerpt, so a
   * long transcript's middle is covered too.
   */
  public static void assertCommandOutputContains(
      String slug, String commandSubstring, String outputSubstring) {
    assertCommandOutputContains("", slug, commandSubstring, outputSubstring);
  }

  /** The categorized spelling of {@link #assertCommandOutputContains(String, String, String)}. */
  public static void assertCommandOutputContains(
      String categorySlug, String slug, String commandSubstring, String outputSubstring) {
    UserflowReport report = read(categorySlug, slug);
    UserflowReport.Command match = findCommand(report, commandSubstring);
    assertTrue(
        match != null,
        () -> "no command containing '" + commandSubstring + "' in " + slug + "'s sidecar");
    assertTrue(
        match != null && match.outputPath() != null,
        () -> "command containing '" + commandSubstring + "' recorded no output at all");
    String transcript =
        readText(
            UserflowPaths.reportDir(categorySlug, slug)
                .resolve(match == null ? "" : match.outputPath()));
    assertTrue(
        transcript.contains(outputSubstring),
        () -> "transcript of '" + commandSubstring + "' lacks '" + outputSubstring + "'");
  }

  /** Assert the story wrote {@code path} and that its content dump landed in the bundle. */
  public static void assertWroteFile(String slug, String path) {
    assertWroteFile("", slug, path);
  }

  /** The categorized spelling of {@link #assertWroteFile(String, String)}. */
  public static void assertWroteFile(String categorySlug, String slug, String path) {
    UserflowReport report = read(categorySlug, slug);
    assertTrue(report.files() != null, () -> "no files recorded in " + slug + "'s sidecar");
    UserflowReport.WrittenFile match =
        report.files() == null
            ? null
            : report.files().stream()
                .filter(file -> file.path().equals(path))
                .findFirst()
                .orElse(null);
    assertTrue(match != null, () -> "no written file '" + path + "' in " + slug);
    Path dump =
        UserflowPaths.reportDir(categorySlug, slug)
            .resolve(match == null ? "" : match.contentPath());
    assertTrue(Files.isRegularFile(dump), () -> "missing content dump for " + path + ": " + dump);
  }

  /**
   * Assert {@code secret} appears in <b>no file at all</b> in the story's bundle — sidecar,
   * markdown, HTML, transcripts, file dumps, and the media beside them. Scanning the whole
   * directory as raw bytes (rather than the three documents we happen to know about) is the point:
   * a leak assertion that only checks the places we remembered proves nothing about the place we
   * forgot.
   */
  public static void assertNotLeaked(String slug, String secret) {
    assertNotLeaked("", slug, secret);
  }

  /** The categorized spelling of {@link #assertNotLeaked(String, String)}. */
  public static void assertNotLeaked(String categorySlug, String slug, String secret) {
    Path dir = UserflowPaths.reportDir(categorySlug, slug);
    byte[] needle = secret.getBytes(StandardCharsets.UTF_8);
    try (var walk = Files.walk(dir)) {
      for (Path path : walk.filter(Files::isRegularFile).toList()) {
        byte[] bytes = Files.readAllBytes(path);
        assertTrue(indexOf(bytes, needle) < 0, () -> "redacted secret leaked into " + path);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("failed to scan " + dir + " for leaks", e);
    }
  }

  /** Assert the markdown contains each of {@code substrings}. */
  public static void assertMarkdownContains(String slug, List<String> substrings) {
    String md = markdown(slug);
    for (String s : substrings) {
      assertTrue(md.contains(s), () -> "user-story.md missing expected text: " + s);
    }
  }

  private static UserflowReport.Step stepById(UserflowReport report, String id) {
    return report.steps().stream()
        .filter(step -> step.id().equals(id))
        .findFirst()
        .orElse(null);
  }

  private static UserflowReport.Command findCommand(UserflowReport report, String substring) {
    if (report.commands() == null) {
      return null;
    }
    return report.commands().stream()
        .filter(command -> command.command().contains(substring))
        .findFirst()
        .orElse(null);
  }

  private static String readText(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("no readable " + path, e);
    }
  }

  /** Naive byte search — a bundle is small and a leak check must not decode anything. */
  private static int indexOf(byte[] haystack, byte[] needle) {
    if (needle.length == 0 || needle.length > haystack.length) {
      return -1;
    }
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private static long sizeOf(Path path) {
    try {
      return Files.isRegularFile(path) ? Files.size(path) : -1;
    } catch (IOException e) {
      return -1;
    }
  }
}
