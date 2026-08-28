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
            new UserflowReport.Step("shot", "screenshot \"result\"")),
        "abc123",
        List.of(
            new UserflowReport.Interaction("browser", "qits-app", "POST /greetings", "step-01")),
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
    // The interactions draw as inline SVG — actors and the message, no script anywhere.
    assertTrue(html.contains("<svg class=\"sequence\""), html);
    assertTrue(html.contains(">qits-app</text>"), html);
    assertTrue(html.contains(">POST /greetings</text>"), html);
    assertFalse(html.contains("<script"), html);
    // The video is a native element over the bundled webm.
    assertTrue(html.contains("<video controls preload=\"metadata\" src=\"recording.webm\""), html);
    assertTrue(html.contains("badge passed"), html);
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
