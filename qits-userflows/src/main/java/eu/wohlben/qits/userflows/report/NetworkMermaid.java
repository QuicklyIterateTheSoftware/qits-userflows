package eu.wohlben.qits.userflows.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The single source of the markdown network diagram's mermaid lines — shared by {@link
 * MarkdownReportRenderer} (which emits them) and {@link ReportAssertions} (which asserts them), so
 * the two can never disagree about what an edge renders as.
 *
 * <p>Nodes are declared as {@code n0["name"]} because observed actor names carry spaces ({@code "a
 * platform service"}), which are not valid bare mermaid identifiers. Ids are assigned over the
 * sorted distinct node names, so the same edge set always renders the same bytes. Arrows encode
 * the kind — {@code -->} http, {@code -.->} event, {@code ==>} socket; any other kind draws {@code
 * -->} with the kind prefixed into the label. A declared edge carries a {@code [declared]} suffix
 * inside its label <b>and</b> a trailing {@code linkStyle} line that draws its arrow muted and
 * dashed — the mermaid half of the rule the SVG renderer keeps in colour, in the same grey: the
 * diagram never passes a claim off as an observation, in either rendering. The suffix stays because
 * a label is readable in a diff and in a terminal, where no arrow is ever drawn.
 *
 * <p>Labels are machine-derived (whatever a client put on the wire), so everything is escaped —
 * {@code "}, {@code <} and {@code >} would otherwise terminate the quoted label or smuggle markup
 * into mermaid's HTML rendering.
 */
final class NetworkMermaid {

  private NetworkMermaid() {}

  /** The muted grey of a declared edge — {@code NetworkDiagramSvg}'s declared colour, exactly. */
  private static final String DECLARED_STYLE = "stroke:#8b949e,stroke-dasharray:3 3";

  /**
   * The full body of the {@code graph LR} block: node declarations, one line per edge, then one
   * {@code linkStyle} line per declared edge.
   *
   * <p>A {@code linkStyle} addresses a link by <b>index</b>, and mermaid counts links in the order
   * they are declared — node declarations create none — so an edge's index is simply its position
   * in {@code edges}. That is what makes the styling deterministic: the edge list arrives already
   * canonical, so the same edge set always emits the same indices.
   *
   * <p>The styles come last, after every edge line, rather than beside the edge they style: an
   * index is only stable once all the links exist, and grouping them keeps the diff of a diagram
   * that gained an edge readable.
   */
  static List<String> lines(List<UserflowReport.NetworkEdge> edges) {
    Map<String, String> ids = nodeIds(edges);
    List<String> lines = new ArrayList<>();
    for (Map.Entry<String, String> node : ids.entrySet()) {
      lines.add("    " + node.getValue() + "[\"" + escape(node.getKey()) + "\"]");
    }
    List<String> styles = new ArrayList<>();
    for (int index = 0; index < edges.size(); index++) {
      UserflowReport.NetworkEdge edge = edges.get(index);
      lines.add(edgeLine(ids, edge));
      if (Boolean.TRUE.equals(edge.declared())) {
        styles.add("    linkStyle " + index + " " + DECLARED_STYLE);
      }
    }
    lines.addAll(styles);
    return lines;
  }

  /** The exact line {@code edge} renders as within the diagram of {@code edges}. */
  static String edgeLine(List<UserflowReport.NetworkEdge> edges, UserflowReport.NetworkEdge edge) {
    return edgeLine(nodeIds(edges), edge);
  }

  private static String edgeLine(Map<String, String> ids, UserflowReport.NetworkEdge edge) {
    String arrow =
        switch (edge.kind()) {
          case "event" -> "-.->";
          case "socket" -> "==>";
          default -> "-->";
        };
    String label =
        switch (edge.kind()) {
          case "http", "event", "socket" -> edge.label();
          default -> edge.kind() + ": " + edge.label();
        };
    if (Boolean.TRUE.equals(edge.declared())) {
      label = label + " [declared]";
    }
    return "    "
        + ids.get(edge.from())
        + " "
        + arrow
        + "|\""
        + escape(label)
        + "\"| "
        + ids.get(edge.to());
  }

  /** {@code name → n0..nN} over the sorted distinct node names — fully canonical. */
  private static Map<String, String> nodeIds(List<UserflowReport.NetworkEdge> edges) {
    TreeSet<String> names = new TreeSet<>();
    for (UserflowReport.NetworkEdge edge : edges) {
      names.add(edge.from());
      names.add(edge.to());
    }
    Map<String, String> ids = new LinkedHashMap<>();
    for (String name : names) {
      ids.put(name, "n" + ids.size());
    }
    return ids;
  }

  private static String escape(String text) {
    return text.replace("\"", "#quot;").replace("<", "#lt;").replace(">", "#gt;");
  }
}
