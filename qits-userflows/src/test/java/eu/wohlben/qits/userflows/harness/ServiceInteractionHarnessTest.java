package eu.wohlben.qits.userflows.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.userflows.HarnessHttpServer;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The canonical <b>passively captured</b> service story: no {@code Flow} parameter, so no Chromium
 * ever launches, and — the point — no story verb records the traffic either. The story registers
 * the harness server's request log as a {@link NetworkCapture} source once, then simply makes a
 * real HTTP call; the extension drains what the far side recorded at story end and the network
 * section appears without the author narrating a single edge.
 *
 * <p>Two stories run here, in order, because one alone cannot prove the <b>cursor</b>: a cumulative
 * recording is read whole every time, and each entry must land in exactly one story. The second
 * story calls a different path and its report must carry that edge <i>only</i> — the first story's
 * request already belongs to the first story.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServiceInteractionHarnessTest {

  private static final String SLUG = "a-service-fetches-from-its-neighbour";
  private static final String SECOND_SLUG = "a-second-call-belongs-to-its-own-story";

  private static HarnessHttpServer server;

  @BeforeAll
  static void startServerAndRegisterItAsASource() {
    server = new HarnessHttpServer();
    // Registered once, read lazily at every story end: the supplier hands over the WHOLE recording
    // and the registry remembers how much of it earlier stories already consumed.
    NetworkCapture.source(
        "harness-server",
        () ->
            server.servedRequests().stream()
                .map(line -> NetworkEdge.http("harness-client", "harness-server", line))
                .toList());
  }

  @UserStory("A service fetches from its neighbour")
  @UserStoryDescription(
      """
      A service-level story with no browser in it: the near side calls the far side over HTTP
      and the far side records having served the call. Nothing narrates the edge — the report's
      network diagram is drawn from what was observed.
      """)
  @Order(1)
  void serviceFetchesFromNeighbour(Interactions interactions) throws Exception {
    interactions.note("the harness server is listening");

    HttpResponse<String> response = get("/ping");

    // both ends: the near side got an answer, the far side saw the request
    assertEquals(200, response.statusCode());
    assertEquals("pong", response.body());
    assertTrue(
        server.servedRequests().contains("GET /ping"),
        "server never saw GET /ping: " + server.servedRequests());
  }

  @UserStory("A second call belongs to its own story")
  @UserStoryDescription(
      """
      A second story against the same cumulative recording: it calls a different path, and its
      report carries that edge alone — the first story's request is already attributed.
      """)
  @Order(2)
  void aSecondCallLandsInItsOwnStory(Interactions interactions) throws Exception {
    interactions.note("the same server is still listening");

    HttpResponse<String> response = get("/status");

    assertEquals(200, response.statusCode());
    assertTrue(
        server.servedRequests().contains("GET /status"),
        "server never saw GET /status: " + server.servedRequests());
  }

  @AfterAll
  static void bothReportsAreCompleteBrowserlessAndSeparatelyAttributed() {
    ReportAssertions.assertComplete(SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(SLUG, "http", "harness-client", "harness-server", "GET /ping");
    ReportAssertions.assertMarkdownContains(
        SLUG,
        List.of(
            "# A service fetches from its neighbour",
            "the harness server is listening",
            "## Network",
            "```mermaid",
            "graph LR",
            "    n0[\"harness-client\"]",
            "    n1[\"harness-server\"]",
            "    n0 -->|\"GET /ping\"| n1"));

    // The two negative claims, against a real emitted bundle: the client is the only initiator in
    // this story, and nothing dialled it back. Both survive the story growing an edge later, which
    // an edge count would not — how many requests a call *is* is not always this story's promise.
    ReportAssertions.assertOnlyEdgesFrom(SLUG, "harness-client");
    ReportAssertions.assertNoEdgesTo(SLUG, "harness-client");

    UserflowReport report = ReportAssertions.read(SLUG);
    assertNull(report.video(), "browserless story must not record a video");
    assertTrue(report.screenshots().isEmpty(), "browserless story must not capture screenshots");

    // The cursor: the second story drained only what arrived while it ran.
    ReportAssertions.assertComplete(SECOND_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        SECOND_SLUG, "http", "harness-client", "harness-server", "GET /status");
    UserflowReport second = ReportAssertions.read(SECOND_SLUG);
    assertEquals(
        1,
        second.network().size(),
        () -> "a drained entry must land in exactly one story: " + second.network());
    assertTrue(
        second.network().stream().noneMatch(edge -> edge.label().equals("GET /ping")),
        () -> "the first story's request was re-attributed: " + second.network());

    server.close();
  }

  private static HttpResponse<String> get(String path) throws Exception {
    try (HttpClient client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create(server.baseUrl() + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
