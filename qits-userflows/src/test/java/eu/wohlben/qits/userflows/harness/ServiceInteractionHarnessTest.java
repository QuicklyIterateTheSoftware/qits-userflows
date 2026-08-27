package eu.wohlben.qits.userflows.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.userflows.HarnessHttpServer;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * The canonical browserless <b>service story</b>: no {@code Flow} parameter, so no Chromium ever
 * launches — the story calls a harness HTTP server directly, proves the call on both ends, and
 * records it as an interaction. The {@code @AfterAll} companion asserts the report came out
 * browserless (no video, no screenshots) and that the interaction rendered into the sidecar and
 * the markdown's mermaid sequence diagram.
 */
class ServiceInteractionHarnessTest {

  private static final String SLUG = "a-service-fetches-from-its-neighbour";

  private static HarnessHttpServer server;

  @BeforeAll
  static void startServer() {
    server = new HarnessHttpServer();
  }

  @UserStory("A service fetches from its neighbour")
  @UserStoryDescription(
      """
      A service-level story with no browser in it: the near side calls the far side over HTTP,
      the far side records having served the call, and the story records the interaction —
      which the report renders as a sequence diagram.
      """)
  void serviceFetchesFromNeighbour(Interactions interactions) throws Exception {
    interactions.note("the harness server is listening");

    HttpResponse<String> response;
    try (HttpClient client = HttpClient.newHttpClient()) {
      response =
          client.send(
              HttpRequest.newBuilder(URI.create(server.baseUrl() + "/ping")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
    }

    // both ends before recording: the near side got an answer, the far side saw the request
    assertEquals(200, response.statusCode());
    assertEquals("pong", response.body());
    assertTrue(server.served().contains("/ping"), "server never saw /ping: " + server.served());

    interactions.happened("harness-client", "harness-server", "GET /ping").as("ping-served");
  }

  @AfterAll
  static void reportIsCompleteAndBrowserless() {
    ReportAssertions.assertComplete(SLUG, UserflowReport.PASSED);
    ReportAssertions.assertInteraction(SLUG, "harness-client", "harness-server", "GET /ping");
    ReportAssertions.assertStepId(SLUG, "ping-served");
    ReportAssertions.assertMarkdownContains(
        SLUG,
        List.of(
            "# A service fetches from its neighbour",
            "the harness server is listening",
            "## Interactions",
            "```mermaid",
            "sequenceDiagram",
            "harness-client->>harness-server: GET /ping"));

    UserflowReport report = ReportAssertions.read(SLUG);
    assertNull(report.video(), "browserless story must not record a video");
    assertTrue(
        report.screenshots().isEmpty(), "browserless story must not capture screenshots");

    server.close();
  }
}
