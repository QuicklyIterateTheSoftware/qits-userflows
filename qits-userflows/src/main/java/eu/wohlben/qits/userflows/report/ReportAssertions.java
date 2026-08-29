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

    if (report.interactions() != null) {
      for (UserflowReport.Interaction interaction : report.interactions()) {
        // the link is by explicit step id, same discipline as a screenshot's
        assertTrue(
            report.steps().stream().anyMatch(step -> step.id().equals(interaction.step())),
            () -> "interaction references unknown step id: " + interaction);
        assertTrue(
            md.contains(mermaidLine(interaction)),
            () -> "interaction missing from the mermaid diagram: " + mermaidLine(interaction));
      }
      assertTrue(md.contains("```mermaid"), "user-story.md has interactions but no mermaid fence");
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
   * Assert the sidecar records the interaction {@code from -> to: description} and that its
   * by-step-id link resolves to a real step.
   */
  public static void assertInteraction(String slug, String from, String to, String description) {
    assertInteraction("", slug, from, to, description);
  }

  /** The categorized spelling of {@link #assertInteraction(String, String, String, String)}. */
  public static void assertInteraction(
      String categorySlug, String slug, String from, String to, String description) {
    UserflowReport report = read(categorySlug, slug);
    assertTrue(
        report.interactions() != null,
        () -> "no interactions recorded in " + slug + "'s sidecar");
    UserflowReport.Interaction match =
        report.interactions().stream()
            .filter(
                interaction ->
                    interaction.from().equals(from)
                        && interaction.to().equals(to)
                        && interaction.description().equals(description))
            .findFirst()
            .orElse(null);
    assertTrue(
        match != null,
        () -> "no interaction " + from + " -> " + to + ": " + description + " in " + slug);
    assertTrue(
        match != null
            && report.steps().stream().anyMatch(step -> step.id().equals(match.step())),
        () -> "interaction references unknown step id: " + match);
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

  /** The exact arrow line {@link MarkdownReportRenderer} draws for {@code interaction}. */
  private static String mermaidLine(UserflowReport.Interaction interaction) {
    return interaction.from() + "->>" + interaction.to() + ": " + interaction.description();
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
