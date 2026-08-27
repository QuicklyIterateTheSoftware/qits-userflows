package eu.wohlben.qits.userflows;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.WaitForSelectorState;
import eu.wohlben.qits.userflows.report.Hashing;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * The step-recording facade over a Playwright {@link Page} that a {@link UserStory} drives. Its
 * verbs map 1:1 onto Playwright operations, and <b>every call appends a step line</b> to the
 * story's log — which is the whole point: Playwright-Java has no built-in step notion, so the
 * facade <i>is</i> the step mechanism. A story must therefore never touch the raw {@link Page}
 * except through {@link #page()} (which deliberately records nothing) — otherwise the report goes
 * blind.
 *
 * <p>Every step gets a stable string id: machine-assigned {@code step-NN} by default, or an
 * explicit author id via {@link #as(String)} ({@code flow.click("…").as("open-project")}) for a
 * meaningful, reorder-proof name. Screenshots link back to their step <b>by that id</b>.
 *
 * <p>Two parallel logs are kept (in the story's shared {@link StepRecorder}): the <b>display</b>
 * lines (with typed values) that become {@code steps[]} in the sidecar and the indented block in
 * the markdown, and a <b>fingerprint</b> (verbs + selectors + labels, no dynamic values, no failure
 * line) hashed into the deterministic definition hash — the future {@code qits.userflow.hash},
 * computed from what the story <i>does</i> rather than from its source text. Step <i>ids</i> are
 * labels, not part of that hash.
 *
 * <p>Instances are created by {@link UserStoryExtension}; stories only ever receive one as a method
 * parameter.
 */
public final class Flow {

  private final Page page;
  private final Path reportDir;
  private final String baseUrl;

  private final StepRecorder recorder;
  // Screenshots are captured in memory and their files written at emit time (below), so an author's
  // .as(id) rename settles the owning step's id before the file name / link are derived from it.
  private final List<PendingShot> pendingShots = new ArrayList<>();

  Flow(Page page, Path reportDir, String baseUrl, StepRecorder recorder) {
    this.page = page;
    this.reportDir = reportDir;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.recorder = recorder;
  }

  // --- verbs ---------------------------------------------------------------------------------

  /**
   * Navigate to {@code urlOrPath}; a relative path resolves against the base URL, absolute (any
   * scheme, incl. {@code file://}) passes through unchanged.
   */
  public Flow navigate(String urlOrPath) {
    page.navigate(resolve(urlOrPath));
    return record("navigate " + urlOrPath, "navigate " + urlOrPath);
  }

  /**
   * Navigate to a path with {@code {}} placeholders filled by {@code args} — for dependent flows
   * that visit a dynamic id (e.g. {@code navigate("/projects/{}/edit", id)}). The recorded step
   * shows the real URL, but the <b>fingerprint keeps the template</b>, so the {@link
   * #definitionHash()} stays stable across runs even though the id changes.
   */
  public Flow navigate(String pathTemplate, Object... args) {
    String actual = applyTemplate(pathTemplate, args);
    page.navigate(resolve(actual));
    return record("navigate " + actual, "navigate " + pathTemplate);
  }

  /** Wait for {@code selector} to appear (Playwright's default timeout). */
  public Flow waitFor(String selector) {
    page.waitForSelector(selector);
    return record("waitFor " + selector, "waitFor " + selector);
  }

  /** Wait for {@code selector} to appear, failing after {@code timeoutMillis}. */
  public Flow waitFor(String selector, double timeoutMillis) {
    page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(timeoutMillis));
    return record("waitFor " + selector, "waitFor " + selector);
  }

  /** Wait until no element matches {@code selector} (passes immediately if already absent). */
  public Flow expectAbsent(String selector) {
    page.waitForSelector(
        selector, new Page.WaitForSelectorOptions().setState(WaitForSelectorState.DETACHED));
    return record("expectAbsent " + selector, "expectAbsent " + selector);
  }

  /** Click the first element matching {@code selector}. */
  public Flow click(String selector) {
    page.click(selector);
    return record("click " + selector, "click " + selector);
  }

  /**
   * Fill {@code selector} with {@code value} (the value shows in the report but not in the hash).
   */
  public Flow fill(String selector, String value) {
    page.fill(selector, value);
    return record("fill " + selector + " \"" + value + "\"", "fill " + selector);
  }

  /** Assert the element at {@code selector} contains {@code expected} text. */
  public Flow expectText(String selector, String expected) {
    PlaywrightAssertions.assertThat(page.locator(selector)).containsText(expected);
    return record("expectText " + selector + " \"" + expected + "\"", "expectText " + selector);
  }

  /** Capture the element matching {@code selector} into the report, labelled {@code label}. */
  public Flow screenshot(String selector, String label) {
    byte[] png = page.locator(selector).first().screenshot();
    capture(png, label);
    return record(
        "screenshot " + selector + " \"" + label + "\"",
        "screenshot " + selector + " \"" + label + "\"");
  }

  /** Capture the full page into the report, labelled {@code label}. */
  public Flow screenshot(String label) {
    byte[] png = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
    capture(png, label);
    return record("screenshot \"" + label + "\"", "screenshot \"" + label + "\"");
  }

  /**
   * Give the step just recorded an explicit id instead of the machine-assigned {@code step-NN} —
   * {@code flow.click("…").as("open-project")}. The id is what a screenshot on this step links to
   * and (for a screenshot step) its file-name prefix, so a meaningful id makes the report
   * self-describing and survives step reordering. Ids must be unique within the story and
   * file-name-safe ({@code [A-Za-z0-9] then [A-Za-z0-9._-]*}).
   */
  public Flow as(String id) {
    recorder.as(id);
    return this;
  }

  /**
   * The raw Playwright {@link Page} — the documented escape hatch for genuinely exotic
   * interactions. Anything done through it leaves <b>no step record</b>; prefer the recording verbs
   * above.
   */
  public Page page() {
    return page;
  }

  /**
   * The current page URL — a read that records no step. Use it to extract produced state for a
   * dependent flow (e.g. the id in {@code /projects/<id>}), typically via {@link
   * Urls#lastPathSegment}.
   */
  public String currentUrl() {
    return page.url();
  }

  // --- consumed by the extension to build the report -----------------------------------------

  /**
   * Write each captured screenshot into the report dir and return the records — deferred to here so
   * every step id (including {@link #as(String)} overrides) is final before the file name and the
   * by-id link are derived from it.
   */
  List<UserflowReport.Screenshot> emitScreenshots() {
    List<UserflowReport.Screenshot> emitted = new ArrayList<>();
    for (PendingShot shot : pendingShots) {
      String stepId = recorder.steps().get(shot.stepIndex).id();
      String fileName = stepId + "-" + Slugs.slug(shot.label) + ".png";
      try {
        Files.write(reportDir.resolve(fileName), shot.png);
      } catch (IOException e) {
        throw new UncheckedIOException("failed to write screenshot " + fileName, e);
      }
      emitted.add(
          new UserflowReport.Screenshot(
              fileName, shot.label, stepId, shot.width, shot.height, shot.contentHash));
    }
    return emitted;
  }

  // --- internals -----------------------------------------------------------------------------

  private Flow record(String displayLine, String fingerprintLine) {
    recorder.record(displayLine, fingerprintLine);
    return this;
  }

  private void capture(byte[] png, String label) {
    // capture() runs before the screenshot step is recorded, so the recorder's current size is the
    // index that step will occupy; the file name + link are resolved from that step's (possibly
    // .as()-renamed) id at emitScreenshots() time.
    int width = 0;
    int height = 0;
    var image = readImage(png);
    if (image != null) {
      width = image.getWidth();
      height = image.getHeight();
    }
    pendingShots.add(
        new PendingShot(
            recorder.steps().size(), label, png, width, height, Hashing.sha256(png)));
  }

  private static java.awt.image.BufferedImage readImage(byte[] png) {
    try {
      return ImageIO.read(new ByteArrayInputStream(png));
    } catch (IOException e) {
      return null; // dimensions fall back to 0; the file is still written
    }
  }

  private static String applyTemplate(String template, Object... args) {
    StringBuilder out = new StringBuilder();
    int arg = 0;
    int from = 0;
    int at;
    while ((at = template.indexOf("{}", from)) >= 0) {
      out.append(template, from, at).append(arg < args.length ? String.valueOf(args[arg++]) : "{}");
      from = at + 2;
    }
    return out.append(template.substring(from)).toString();
  }

  private String resolve(String urlOrPath) {
    if (urlOrPath.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
      return urlOrPath; // absolute (http:, https:, file:, …)
    }
    return baseUrl + (urlOrPath.startsWith("/") ? "" : "/") + urlOrPath;
  }

  /** A screenshot captured in memory, awaiting its owning step's final id at emit time. */
  private record PendingShot(
      int stepIndex, String label, byte[] png, int width, int height, String contentHash) {}
}
