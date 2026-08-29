package eu.wohlben.qits.userflows.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HtmlReportRendererTest {

  @TempDir Path reportDir;

  private static UserflowReport report(String category, String outcome) {
    return new UserflowReport(
        "Create a <greeting>",
        "create-a-greeting",
        category,
        "A visitor & their greeting.",
        List.of(
            new UserflowReport.Step("step-01", "navigate /"),
            new UserflowReport.Step("wrote", "write config.txt"),
            new UserflowReport.Step("ran", "run echo <hi>"),
            new UserflowReport.Step("shot", "screenshot \"result\"")),
        "abc123",
        List.of(
            new UserflowReport.NetworkEdge("http", "browser", "qits-app", "POST /greetings", null),
            new UserflowReport.NetworkEdge(
                "process", "qits-app", "an engine", "spawn <engine>", Boolean.TRUE)),
        "sha256:net",
        List.of(
            new UserflowReport.Command(
                "ran", "echo <hi>", 3, "boom & <fail>", "ran-echo-hi.txt", Boolean.TRUE)),
        List.of(new UserflowReport.WrittenFile("wrote", "config.txt", "wrote-config-txt.txt")),
        List.of(new UserflowReport.Screenshot("shot-result.png", "the result", "shot", 12, 8, "h")),
        new UserflowReport.Video("recording.webm", 1280, 720),
        outcome);
  }

  @Test
  void rendersASelfContainedStoryPage() throws IOException {
    new HtmlReportRenderer().render(report("authentication", UserflowReport.PASSED), reportDir);

    String html = Files.readString(reportDir.resolve("index.html"));
    // Escaped title, both in <title> and the heading — the angle brackets must never parse.
    assertTrue(html.contains("<title>Create a &lt;greeting&gt;</title>"), html);
    assertTrue(html.contains("&amp; their greeting"), html);
    // A categorized story climbs two levels back to the site index.
    assertTrue(html.contains("href=\"../../index.html\""), html);
    // The screenshot sits under its step, relatively linked with its dimensions.
    assertTrue(html.contains("src=\"shot-result.png\" alt=\"the result\" width=\"12\""), html);
    // The network draws as inline SVG — nodes, labels and both arrowheads, no script anywhere.
    assertTrue(html.contains("<h2>Network</h2>"), html);
    assertTrue(html.contains("<svg class=\"network\""), html);
    assertTrue(html.contains("<marker id=\"net-arrow\""), html);
    assertTrue(html.contains("<marker id=\"net-arrow-declared\""), html);
    assertTrue(html.contains(">qits-app</text>"), html);
    assertTrue(html.contains(">POST /greetings</text>"), html);
    // A declared edge is muted and carries the declared arrowhead — a claim never renders like
    // evidence — and its label is escaped like every other machine-derived string on the page.
    assertTrue(html.contains("stroke=\"#8b949e\" marker-end=\"url(#net-arrow-declared)\""), html);
    assertTrue(
        html.contains("fill=\"#8b949e\">process: spawn &lt;engine&gt;</text>"), html);
    assertFalse(html.contains("<script"), html);
    // The video is a native element over the bundled webm.
    assertTrue(html.contains("<video controls preload=\"metadata\" src=\"recording.webm\""), html);
    assertTrue(html.contains("badge passed"), html);
    // A command draws as a terminal under its own step: escaped output, a badged exit code and a
    // link to the full transcript. A non-zero exit accents the block.
    assertTrue(html.contains("<pre class=\"terminal failed\">boom &amp; &lt;fail&gt;</pre>"), html);
    assertTrue(html.contains("<span class=\"exit-bad\">exit 3</span>"), html);
    assertTrue(html.contains("<a href=\"ran-echo-hi.txt\">full output</a>"), html);
    // A written file draws as a light dump captioned with its path; its content comes from the
    // artifact beside the sidecar, which this fixture deliberately does not create — a missing
    // dump must render as an empty figure, never blow up the report.
    assertTrue(html.contains("wrote <code>config.txt</code>"), html);
    assertFalse(html.contains("<pre class=\"filedump\">"), html);
  }

  @Test
  void aWrittenFileShowsTheDumpBesideTheSidecar() throws IOException {
    Files.writeString(reportDir.resolve("wrote-config-txt.txt"), "token = <s3cret>\n");

    new HtmlReportRenderer().render(report(null, UserflowReport.PASSED), reportDir);

    String html = Files.readString(reportDir.resolve("index.html"));
    assertTrue(html.contains("<pre class=\"filedump\">token = &lt;s3cret&gt;\n</pre>"), html);
    assertFalse(html.contains("<script"), html);
  }

  @Test
  void aFlatStoryClimbsOneLevelAndAFailureIsBadgedAndNoted() throws IOException {
    new HtmlReportRenderer().render(report(null, UserflowReport.FAILED), reportDir);

    String html = Files.readString(reportDir.resolve("index.html"));
    assertTrue(html.contains("href=\"../index.html\""), html);
    assertTrue(html.contains("badge failed"), html);
    assertTrue(html.contains("Outcome: failed"), html);
  }
}
