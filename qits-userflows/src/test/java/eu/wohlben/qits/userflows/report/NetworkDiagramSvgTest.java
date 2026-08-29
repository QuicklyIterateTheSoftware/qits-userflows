package eu.wohlben.qits.userflows.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The three properties of the drawn diagram that a reader silently depends on and no story
 * assertion can see: it is <b>byte-deterministic</b> (the same edge set draws the same page, or
 * every report re-renders as a spurious diff), its columns are derived <b>structurally</b> (nobody
 * tells the diagram whose story it is), and its two visual axes — provenance by colour, kind by
 * stroke — actually reach the markup, with a legend that always says what they mean.
 */
class NetworkDiagramSvgTest {

  /** A {@code <rect>} and the {@code <text>} naming it — how a node's geometry is recovered. */
  private static final Pattern NODE =
      Pattern.compile(
          "<rect x=\"([0-9.]+)\" y=\"([0-9.]+)\"[^>]*/>\\s*<text[^>]*>([^<]*)</text>");

  private record Box(double x, double y) {}

  private static final List<UserflowReport.NetworkEdge> EDGES =
      List.of(
          new UserflowReport.NetworkEdge("event", "a broker", "qits-app", "task.finished", null),
          new UserflowReport.NetworkEdge("http", "a caller", "qits-app", "GET /health", null),
          new UserflowReport.NetworkEdge(
              "process", "qits-app", "an engine", "spawn engine.sh", Boolean.TRUE),
          new UserflowReport.NetworkEdge("socket", "qits-app", "the docker engine", "dial", null));

  @Test
  void theSameEdgeSetAlwaysDrawsTheSameBytes() {
    assertEquals(NetworkDiagramSvg.render(EDGES), NetworkDiagramSvg.render(EDGES));
  }

  @Test
  void columnsAreRankedStructurallyFromTheEdgesAlone() {
    Map<String, Box> boxes = nodes(NetworkDiagramSvg.render(EDGES));

    // Out-only nodes initiate, so they sit left of the node with traffic in BOTH directions, which
    // in turn sits left of the in-only callees. Nothing named the service under test.
    assertTrue(boxes.get("a broker").x() < boxes.get("qits-app").x(), boxes::toString);
    assertTrue(boxes.get("a caller").x() < boxes.get("qits-app").x(), boxes::toString);
    assertTrue(boxes.get("qits-app").x() < boxes.get("an engine").x(), boxes::toString);
    assertTrue(boxes.get("qits-app").x() < boxes.get("the docker engine").x(), boxes::toString);
    // Within a column the vertical order is alphabetical — canonical, like the edge order itself.
    assertTrue(boxes.get("a broker").y() < boxes.get("a caller").y(), boxes::toString);
    assertTrue(boxes.get("an engine").y() < boxes.get("the docker engine").y(), boxes::toString);
  }

  @Test
  void provenanceIsColourAndKindIsStroke() {
    String svg = NetworkDiagramSvg.render(EDGES);

    // observed: the dark stroke and the plain arrowhead
    assertTrue(svg.contains("stroke=\"#374151\""), svg);
    assertTrue(svg.contains("marker-end=\"url(#net-arrow)\""), svg);
    // declared: muted, with its own arrowhead, and a muted label to match
    assertTrue(svg.contains("stroke=\"#8b949e\" marker-end=\"url(#net-arrow-declared)\""), svg);
    assertTrue(svg.contains("fill=\"#8b949e\">process: spawn engine.sh</text>"), svg);
    // kinds the colour does not encode: event dashes, socket thickens
    assertTrue(svg.contains("stroke=\"#374151\" stroke-dasharray=\"5 4\""), svg);
    assertTrue(svg.contains("stroke=\"#374151\" stroke-width=\"2.5\""), svg);
  }

  @Test
  void theLegendAlwaysRendersAndThePageCarriesNoScript() {
    // Even a single plain http edge gets the full key: a reader must never have to guess whether
    // the diagram simply had nothing declared, or was drawn by a version that could not say so.
    String svg =
        NetworkDiagramSvg.render(
            List.of(
                new UserflowReport.NetworkEdge(
                    "http", "a caller", "qits-app", "GET /health", null)));

    assertTrue(svg.contains(">observed</text>"), svg);
    assertTrue(svg.contains(">declared</text>"), svg);
    assertTrue(svg.contains(">event</text>"), svg);
    assertTrue(svg.contains(">socket</text>"), svg);
    assertFalse(svg.contains("<script"), svg);
  }

  @Test
  void anAttributedEdgeCarriesItsStoriesAsTheOneScriptFreeTooltip() {
    UserflowReport.NetworkEdge edge =
        new UserflowReport.NetworkEdge("http", "a caller", "qits-app", "GET /health", null);
    String svg =
        NetworkDiagramSvg.render(List.of(edge), Map.of(edge, List.of("greeting", "sign-in")));

    assertTrue(svg.contains("<title>greeting\nsign-in</title>"), svg);
  }

  private static Map<String, Box> nodes(String svg) {
    Map<String, Box> boxes = new LinkedHashMap<>();
    Matcher matcher = NODE.matcher(svg);
    while (matcher.find()) {
      boxes.put(
          matcher.group(3),
          new Box(Double.parseDouble(matcher.group(1)), Double.parseDouble(matcher.group(2))));
    }
    return boxes;
  }
}
