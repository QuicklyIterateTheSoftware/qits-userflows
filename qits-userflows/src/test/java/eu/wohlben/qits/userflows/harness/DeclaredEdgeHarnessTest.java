package eu.wohlben.qits.userflows.harness;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.Network;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.JsonReportWriter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowPaths;
import eu.wohlben.qits.userflows.report.UserflowReport;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterAll;

/**
 * The declared-edge escape hatch: a dependency reached over pipes has no tap that could observe it,
 * so the story <b>says</b> it — and the report is required to keep saying so. The sidecar carries
 * {@code "declared": true} and both renderings mark the edge, because a diagram that let a claim
 * render like an observation would quietly turn documentation into evidence.
 *
 * <p>The narrative half of the same story stays where it belongs: an absence claim is an assertion
 * the story proves and then {@code note}s, never an edge.
 */
class DeclaredEdgeHarnessTest {

  private static final String SLUG = "a-story-declares-an-unobservable-dependency";

  @UserStory("A story declares an unobservable dependency")
  @UserStoryDescription(
      """
      The harness talks to a spawned engine over its pipes — traffic no proxy and no mock can
      see. The story declares the edge, and the report marks it as a claim.
      """)
  void declaresWhatNoTapCanSee(Interactions story, Network network) {
    story.note("nothing crosses a socket here — the engine is a child process");
    network.declare("process", "harness", "a spawned engine", "spawn engine.sh");
  }

  @AfterAll
  static void theClaimIsMarkedAsOne() throws IOException {
    ReportAssertions.assertComplete(SLUG, UserflowReport.PASSED);
    ReportAssertions.assertDeclaredEdge(
        "", SLUG, "process", "harness", "a spawned engine", "spawn engine.sh");

    // The raw sidecar, not the parsed model: the flag has to be ON THE WIRE for a later consumer.
    String json =
        Files.readString(
            UserflowPaths.reportDir(SLUG).resolve(JsonReportWriter.FILE_NAME));
    assertTrue(json.contains("\"declared\" : true"), json);

    // …and the markdown says it in words, inside the edge's own label.
    String md = ReportAssertions.markdown(SLUG);
    assertTrue(md.contains("\"process: spawn engine.sh [declared]\""), md);
  }
}
