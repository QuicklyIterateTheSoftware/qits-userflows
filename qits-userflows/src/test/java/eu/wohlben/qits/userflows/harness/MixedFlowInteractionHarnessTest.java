package eu.wohlben.qits.userflows.harness;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * A mixed story taking both {@link Flow} and {@link Interactions}: the two facades share one step
 * recorder, so browser steps and interactions interleave in call order in a single log — and the
 * browser side (video, screenshots) still emits in full.
 */
class MixedFlowInteractionHarnessTest {

  private static final String SLUG = "a-browser-story-also-records-an-interaction";

  @UserStory("A browser story also records an interaction")
  @UserStoryDescription(
      """
      A story that drives the browser AND records a service-to-service interaction mid-way:
      both land in one interleaved step log, and the interaction still gets its diagram.
      """)
  void browserStoryRecordsInteraction(Flow flow, Interactions interactions) {
    flow.navigate(HarnessResources.classpathUrl("/harness/greeting.html"));
    flow.waitFor("input[name=name]");
    interactions
        .happened("greeting-page", "greeting-backend", "POST /greetings")
        .as("greeting-submitted");
    flow.fill("input[name=name]", "Ada");
    flow.screenshot("the filled form");
  }

  @AfterAll
  static void reportInterleavesBothFacades() {
    ReportAssertions.assertComplete(SLUG, UserflowReport.PASSED);
    ReportAssertions.assertInteraction(
        SLUG, "greeting-page", "greeting-backend", "POST /greetings");
    ReportAssertions.assertStepId(SLUG, "greeting-submitted");

    UserflowReport report = ReportAssertions.read(SLUG);
    List<String> lines = report.steps().stream().map(UserflowReport.Step::line).toList();
    // the interaction sits exactly where it was recorded: between waitFor and fill
    int waitFor = lines.indexOf("waitFor input[name=name]");
    int interaction =
        lines.indexOf("interaction greeting-page -> greeting-backend: POST /greetings");
    int fill = lines.indexOf("fill input[name=name] \"Ada\"");
    assertTrue(
        waitFor >= 0 && waitFor < interaction && interaction < fill,
        () -> "steps not interleaved in call order: " + lines);

    // the browser side is untouched by the interaction machinery
    assertNotNull(report.video(), "mixed story must still record a video");
    assertTrue(!report.screenshots().isEmpty(), "mixed story must still capture screenshots");
  }
}
