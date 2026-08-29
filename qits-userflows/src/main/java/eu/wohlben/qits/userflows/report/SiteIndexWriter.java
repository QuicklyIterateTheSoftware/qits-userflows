package eu.wohlben.qits.userflows.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Writes the bundle's entry point — {@code <root>/index.html}, listing every emitted story grouped
 * by category — by <b>rescanning the sidecars on disk</b> rather than accumulating in memory:
 * stories emit one class at a time across the whole test run, and a rescan after each emit means
 * the index is complete whenever the JVM stops, with no end-of-run hook to miss. The last story to
 * finish writes the index that ships.
 *
 * <p>This file is what the docs reader opens: qits-docs frames a bundle at its {@code index.html}
 * and has no notion of a site without one, so before this writer existed a published userflow
 * bundle was cataloged but answered 404 to every reader.
 *
 * <p>Reads {@code userflow.json} (the canonical sidecar) and never the markdown or the HTML —
 * lenient about unknown fields, so an index over sidecars from a newer framework still lists them.
 */
public final class SiteIndexWriter {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** A category-less story sorts under this heading, listed after the named categories. */
  private static final String UNCATEGORIZED = "";

  private record Entry(String story, String href, String outcome) {}

  /**
   * The identity of an edge across stories: the {@code (kind, from, to, label)} quadruple, exactly
   * what a single story dedupes on and what {@code networkHash} covers. Provenance is deliberately
   * <b>not</b> part of it — the same dependency observed in one story and declared in another is
   * one dependency, and keying on the whole record would draw it twice.
   */
  private record EdgeKey(String kind, String from, String to, String label) {}

  /**
   * What the union knows about one edge: which stories produced it, and whether <i>every</i>
   * occurrence was a declaration. One observation anywhere makes the aggregate edge observed —
   * evidence beats declaration, the same rule a story's own merge applies.
   */
  private static final class Aggregate {
    final TreeSet<String> stories = new TreeSet<>();
    boolean declaredEverywhere = true;
  }

  private SiteIndexWriter() {}

  /** Rescan {@code outputRoot} and (re)write its {@code index.html}. */
  public static void rewrite(Path outputRoot) throws IOException {
    if (!Files.isDirectory(outputRoot)) {
      return;
    }
    // <slug>/userflow.json (flat) and <category>/<slug>/userflow.json (categorized): the two
    // layouts UserflowPaths can produce, and nothing deeper.
    Map<String, List<Entry>> byCategory = new TreeMap<>();
    // The union of every story's network edges, each attributed to the stories that produced it —
    // the aggregate diagram is what turns per-story evidence into the service's dependency map.
    Map<EdgeKey, Aggregate> byEdge = new LinkedHashMap<>();
    try (Stream<Path> sidecars = Files.find(
        outputRoot,
        3,
        (path, attributes) ->
            attributes.isRegularFile()
                && path.getFileName().toString().equals(JsonReportWriter.FILE_NAME))) {
      for (Path sidecar : sidecars.sorted().toList()) {
        JsonNode report = MAPPER.readTree(sidecar.toFile());
        Path relative = outputRoot.relativize(sidecar.getParent());
        String href = relative.toString().replace('\\', '/') + "/" + HtmlReportRenderer.FILE_NAME;
        String category = report.path("category").asText(UNCATEGORIZED);
        byCategory
            .computeIfAbsent(category, unused -> new ArrayList<>())
            .add(
                new Entry(
                    report.path("story").asText(relative.getFileName().toString()),
                    href,
                    report.path("outcome").asText(UserflowReport.PASSED)));
        for (JsonNode edge : report.path("network")) {
          // Read by path, like every field here: a sidecar without a network section contributes
          // nothing, an old sidecar still lists.
          EdgeKey key =
              new EdgeKey(
                  edge.path("kind").asText(),
                  edge.path("from").asText(),
                  edge.path("to").asText(),
                  edge.path("label").asText());
          Aggregate aggregate = byEdge.computeIfAbsent(key, unused -> new Aggregate());
          aggregate.stories.add(relative.toString().replace('\\', '/'));
          aggregate.declaredEverywhere &= edge.path("declared").asBoolean(false);
        }
      }
    }

    // Named categories in order, the category-less tail last — a TreeMap sorts "" first, and the
    // reorder here is what keeps the flat layout from leading the page.
    Map<String, List<Entry>> ordered = new LinkedHashMap<>();
    byCategory.forEach(
        (category, entries) -> {
          if (!UNCATEGORIZED.equals(category)) {
            ordered.put(category, entries);
          }
        });
    if (byCategory.containsKey(UNCATEGORIZED)) {
      ordered.put(UNCATEGORIZED, byCategory.get(UNCATEGORIZED));
    }

    StringBuilder html = new StringBuilder();
    html.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
        .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        .append("<title>User flows</title>\n<style>")
        .append(HtmlReportRenderer.CSS)
        .append("ul.stories{list-style:none;margin:0;padding:0}\n")
        .append("ul.stories li{margin:.45rem 0}\n")
        .append("</style>\n</head>\n<body>\n<h1>User flows</h1>\n")
        .append("<p class=\"lede\">Executable user stories: every page below was generated by a")
        .append(" passing test run against the packaged service, screenshots and network")
        .append(" diagrams included.</p>\n");
    if (!byEdge.isEmpty()) {
      // The aggregate before the lists: the fan-in/fan-out of the whole suite, every edge
      // hoverable to the stories that observed it.
      Map<UserflowReport.NetworkEdge, List<String>> attribution = new LinkedHashMap<>();
      List<UserflowReport.NetworkEdge> union = new ArrayList<>();
      for (Map.Entry<EdgeKey, Aggregate> entry : byEdge.entrySet()) {
        EdgeKey key = entry.getKey();
        UserflowReport.NetworkEdge edge =
            new UserflowReport.NetworkEdge(
                key.kind(),
                key.from(),
                key.to(),
                key.label(),
                entry.getValue().declaredEverywhere ? Boolean.TRUE : null);
        union.add(edge);
        attribution.put(edge, List.copyOf(entry.getValue().stories));
      }
      union.sort(
          Comparator.comparing(UserflowReport.NetworkEdge::kind)
              .thenComparing(UserflowReport.NetworkEdge::from)
              .thenComparing(UserflowReport.NetworkEdge::to)
              .thenComparing(UserflowReport.NetworkEdge::label));
      html.append("<h2>Service network</h2>\n<div class=\"diagram\">\n")
          .append(NetworkDiagramSvg.render(union, attribution))
          .append("</div>\n");
    }

    for (Map.Entry<String, List<Entry>> group : ordered.entrySet()) {
      if (!UNCATEGORIZED.equals(group.getKey())) {
        html.append("<h2>").append(Html.escape(group.getKey())).append("</h2>\n");
      } else if (ordered.size() > 1) {
        html.append("<h2>stories</h2>\n");
      }
      html.append("<ul class=\"stories\">\n");
      group.getValue().sort(Comparator.comparing(Entry::story));
      for (Entry entry : group.getValue()) {
        html.append("<li><a href=\"")
            .append(Html.escape(entry.href()))
            .append("\">")
            .append(Html.escape(entry.story()))
            .append("</a><span class=\"badge ")
            .append(UserflowReport.FAILED.equals(entry.outcome()) ? "failed" : "passed")
            .append("\">")
            .append(Html.escape(entry.outcome()))
            .append("</span></li>\n");
      }
      html.append("</ul>\n");
    }

    Files.writeString(
        outputRoot.resolve(HtmlReportRenderer.FILE_NAME),
        html.toString(),
        StandardCharsets.UTF_8);
  }
}
