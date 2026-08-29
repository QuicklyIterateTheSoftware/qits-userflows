package eu.wohlben.qits.userflows.harness;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.userflows.Commands;
import eu.wohlben.qits.userflows.Flow;
import eu.wohlben.qits.userflows.HarnessResources;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import java.util.List;
import org.junit.jupiter.api.AfterAll;

/**
 * All three recording facades in one story. They share a single {@link
 * eu.wohlben.qits.userflows.UserStoryExtension}-owned step recorder, so a browser step, a narrative
 * note and a shell command interleave in <b>call order</b> into one log and one definition hash — a
 * story is one narrative regardless of how many surfaces it touches.
 *
 * <p>The companion also checks the browser side is untouched by the command machinery: the video
 * and the screenshots still emit in full.
 */
class MixedCommandsHarnessTest {

  private static final String SLUG = "a-story-mixes-browser-services-and-shell";

  @UserStory("A story mixes browser services and shell")
  @UserStoryDescription(
      """
      One narrative across three surfaces: the browser fills a form, the story notes what the
      page did, and a shell command runs — all in one interleaved step log.
      """)
  void mixesAllThree(Flow flow, Interactions interactions, Commands commands) {
    flow.navigate(HarnessResources.classpathUrl("/harness/greeting.html"));
    flow.waitFor("input[name=name]");
    commands.run("echo preparing the fixture").as("prepared");
    interactions.note("the page posts the greeting to its backend");
    flow.fill("input[name=name]", "Ada");
    flow.screenshot("the filled form");
  }

  @AfterAll
  static void oneLogAcrossThreeSurfaces() {
    ReportAssertions.assertComplete(SLUG, UserflowReport.PASSED);
    ReportAssertions.assertCommand(SLUG, "echo preparing the fixture", 0);
    ReportAssertions.assertStepId(SLUG, "prepared");

    UserflowReport report = ReportAssertions.read(SLUG);
    List<String> lines = report.steps().stream().map(UserflowReport.Step::line).toList();
    int waitFor = lines.indexOf("waitFor input[name=name]");
    int command = lines.indexOf("run echo preparing the fixture");
    int note = lines.indexOf("the page posts the greeting to its backend");
    int fill = lines.indexOf("fill input[name=name] \"Ada\"");
    assertTrue(
        waitFor >= 0 && waitFor < command && command < note && note < fill,
        () -> "steps not interleaved in call order: " + lines);

    // the browser side is untouched by the command machinery
    assertNotNull(report.video(), "a mixed story must still record a video");
    assertTrue(!report.screenshots().isEmpty(), "a mixed story must still capture screenshots");
  }
}
