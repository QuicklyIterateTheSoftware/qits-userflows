package eu.wohlben.qits.userflows.harness;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.userflows.Flow;
import eu.wohlben.qits.userflows.HarnessResources;
import eu.wohlben.qits.userflows.Network;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import org.junit.jupiter.api.AfterAll;

/**
 * A mixed story taking both {@link Flow} and {@link Network}: the browser side emits in full
 * (video, screenshots, an interleaved step log) while the network section is built from an edge the
 * story <b>declares</b> — the two live in different places on purpose. An edge is not an event in
 * the narrative, so it records no step and never enters the definition hash; it renders as its own
 * section, after the steps.
 */
class MixedFlowInteractionHarnessTest {

  private static final String SLUG = "a-browser-story-also-records-an-interaction";

  @UserStory("A browser story also records an interaction")
  @UserStoryDescription(
      """
      A story that drives the browser AND names a dependency no tap in this harness can see:
      the video, the screenshots and the network diagram all land in one report.
      """)
  void browserStoryRecordsInteraction(Flow flow, Network network) {
    flow.navigate(HarnessResources.classpathUrl("/harness/greeting.html"));
    flow.waitFor("input[name=name]");
    network.declare("http", "greeting-page", "greeting-backend", "POST /greetings");
    flow.fill("input[name=name]", "Ada");
    flow.screenshot("the filled form");
  }

  @AfterAll
  static void theBrowserSideAndTheNetworkSectionCoexist() {
    ReportAssertions.assertComplete(SLUG, UserflowReport.PASSED);
    ReportAssertions.assertDeclaredEdge(
        "", SLUG, "http", "greeting-page", "greeting-backend", "POST /greetings");

    // the browser side is untouched by the network machinery
    UserflowReport report = ReportAssertions.read(SLUG);
    assertNotNull(report.video(), "mixed story must still record a video");
    assertTrue(!report.screenshots().isEmpty(), "mixed story must still capture screenshots");

    // …and the diagram is a section of its own, drawn after the steps and before the video
    String md = ReportAssertions.markdown(SLUG);
    int steps = md.indexOf("## Steps");
    int network = md.indexOf("## Network");
    int video = md.indexOf("## Video");
    assertTrue(steps >= 0 && steps < network && network < video, md);
  }
}
