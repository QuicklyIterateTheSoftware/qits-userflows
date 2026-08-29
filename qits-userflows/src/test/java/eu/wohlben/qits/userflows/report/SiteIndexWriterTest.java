package eu.wohlben.qits.userflows.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SiteIndexWriterTest {

  @TempDir Path root;

  private void sidecar(Path dir, String story, String category, String outcome)
      throws IOException {
    sidecar(dir, story, category, outcome, null);
  }

  /** {@code network} goes in verbatim — hand-written sidecars are the point of this test. */
  private void sidecar(Path dir, String story, String category, String outcome, String network)
      throws IOException {
    Files.createDirectories(dir);
    String categoryMember = category == null ? "" : "\"category\": \"" + category + "\",";
    String networkMember = network == null ? "" : "\"network\": [" + network + "],";
    Files.writeString(
        dir.resolve(JsonReportWriter.FILE_NAME),
        "{\"story\": \""
            + story
            + "\","
            + categoryMember
            + networkMember
            + "\"outcome\": \""
            + outcome
            + "\"}");
  }

  private static String edge(String kind, String from, String to, String label, boolean declared) {
    return "{\"kind\": \""
        + kind
        + "\", \"from\": \""
        + from
        + "\", \"to\": \""
        + to
        + "\", \"label\": \""
        + label
        + "\""
        + (declared ? ", \"declared\": true" : "")
        + "}";
  }

  @Test
  void listsBothLayoutsGroupedByCategoryWithTheFlatTailLast() throws IOException {
    sidecar(
        root.resolve("authentication").resolve("token-accepted"),
        "A token opens the door",
        "authentication",
        UserflowReport.PASSED);
    sidecar(
        root.resolve("authentication").resolve("token-denied"),
        "A stranger is refused",
        "authentication",
        UserflowReport.FAILED);
    sidecar(root.resolve("greeting"), "Create a greeting", null, UserflowReport.PASSED);

    SiteIndexWriter.rewrite(root);

    String html = Files.readString(root.resolve("index.html"));
    assertTrue(html.contains("<h2>authentication</h2>"), html);
    // Stories link into their own directories, category-deep where categorized.
    assertTrue(html.contains("href=\"authentication/token-accepted/index.html\""), html);
    assertTrue(html.contains("href=\"greeting/index.html\""), html);
    // Within a category the order is by title, and the outcome rides as a badge.
    assertTrue(
        html.indexOf("A stranger is refused") < html.indexOf("A token opens the door"), html);
    assertTrue(html.contains("badge failed"), html);
    // The category-less story lists after the named categories, under its own heading.
    assertTrue(html.indexOf("<h2>authentication</h2>") < html.indexOf("<h2>stories</h2>"), html);
    assertTrue(html.indexOf("<h2>stories</h2>") < html.indexOf("Create a greeting"), html);
    // Sidecars from before the network existed still list, and draw no aggregate at all.
    assertFalse(html.contains("Service network"), html);
  }

  /**
   * The aggregate is a <b>union over the quadruple</b>, not over the record: the same dependency
   * seen in two stories is one edge, and an observation anywhere makes it observed — otherwise a
   * service that one story declares and another proves would draw twice, once as a claim, and the
   * whole page would stop being a dependency map.
   */
  @Test
  void theAggregateUnionsEdgesAcrossStoriesWithObservationWinning() throws IOException {
    sidecar(
        root.resolve("greeting"),
        "Create a greeting",
        null,
        UserflowReport.PASSED,
        edge("http", "browser", "qits-app", "POST /greetings", false));
    sidecar(
        root.resolve("authentication").resolve("token-accepted"),
        "A token opens the door",
        "authentication",
        UserflowReport.PASSED,
        edge("http", "browser", "qits-app", "POST /greetings", true)
            + ","
            + edge("process", "qits-app", "an engine", "spawn engine.sh", true));

    SiteIndexWriter.rewrite(root);

    String html = Files.readString(root.resolve("index.html"));
    assertTrue(html.contains("<h2>Service network</h2>"), html);
    // …and it leads the page: the map first, the stories that produced it after.
    assertTrue(html.indexOf("<h2>Service network</h2>") < html.indexOf("<h2>authentication</h2>"));

    // One edge, not two — the declaration merged into the observation.
    assertEquals(1, countOccurrences(html, ">POST /greetings</text>"), html);
    String observed = groupOf(html, ">POST /greetings</text>");
    assertTrue(observed.contains("stroke=\"#374151\""), observed);
    assertTrue(observed.contains("marker-end=\"url(#net-arrow)\""), observed);
    assertFalse(observed.contains("#8b949e"), observed);
    // Both stories are attributed, in path order — the one tooltip a script-free page can offer.
    assertTrue(
        observed.contains("<title>authentication/token-accepted\ngreeting</title>"), observed);

    // An edge only ever declared stays declared, and names only the story that declared it.
    String declared = groupOf(html, ">process: spawn engine.sh</text>");
    assertTrue(declared.contains("stroke=\"#8b949e\""), declared);
    assertTrue(declared.contains("marker-end=\"url(#net-arrow-declared)\""), declared);
    assertTrue(declared.contains("<title>authentication/token-accepted</title>"), declared);
  }

  /** The {@code <g>…} of the edge whose label element is {@code labelElement}. */
  private static String groupOf(String html, String labelElement) {
    int at = html.indexOf(labelElement);
    assertTrue(at >= 0, () -> "no edge labelled " + labelElement + " in " + html);
    return html.substring(html.lastIndexOf("<g>", at), at);
  }

  private static int countOccurrences(String text, String needle) {
    int count = 0;
    int from = 0;
    int at;
    while ((at = text.indexOf(needle, from)) >= 0) {
      count++;
      from = at + needle.length();
    }
    return count;
  }

  @Test
  void anEmptyRootWritesNothing() throws IOException {
    SiteIndexWriter.rewrite(root.resolve("does-not-exist"));
    // No directory, no index — and no exception: the writer runs after every story emit, and the
    // first story is what creates the root.
    assertTrue(Files.notExists(root.resolve("does-not-exist").resolve("index.html")));
  }
}
