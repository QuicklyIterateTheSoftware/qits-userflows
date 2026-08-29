package eu.wohlben.qits.userflows.report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Draws a network edge set as an inline SVG fan-in/fan-out graph: initiators in the left column,
 * the service under test in the middle, its callees on the right — with the columns derived
 * <b>structurally</b> (a node with only outgoing edges initiates, one with both sides is the
 * service under test by construction), so no diagram ever has to be told whose story it is.
 *
 * <p>Drawn here, in Java, at emit time, because the HTML report must stay <b>self-contained</b> and
 * script-free — a diagram that needed a renderer on the page would be a diagram the artifact store
 * cannot serve. The markdown report keeps its mermaid block for readers that render mermaid; this
 * SVG is the same data drawn once, for browsers.
 *
 * <p>Two visual axes: <b>provenance</b> is color (observed dark, declared muted — a claim never
 * renders like evidence) and <b>kind</b> is stroke (http solid, event dashed, socket thick; any
 * other kind draws solid with the kind word prefixed into the label). A compact legend always
 * renders, so a reader never has to guess the encoding.
 *
 * <p>Geometry is intentionally simple and fully deterministic: text width is estimated from a
 * character count (the font is monospace), nodes and edges lay out in canonical order, so the
 * same report always produces the same bytes.
 */
final class NetworkDiagramSvg {

  /** Estimated advance width of one monospace character at the font size used below. */
  private static final double CHAR_WIDTH = 7.3;

  private static final int BOX_MIN_HEIGHT = 32;
  private static final int PORT_PITCH = 14;
  private static final int BOX_GAP = 28;
  private static final int MARGIN = 24;
  private static final int TOP = 12;
  private static final int LEGEND_HEIGHT = 26;

  private static final String OBSERVED_COLOR = "#374151";
  private static final String DECLARED_COLOR = "#8b949e";

  private NetworkDiagramSvg() {}

  static String render(List<UserflowReport.NetworkEdge> edges) {
    return render(edges, Map.of());
  }

  /**
   * {@code storiesByEdge} attributes an edge to the stories that produced it (the site index's
   * aggregate view); attributed edges carry a {@code <title>} child — the one tooltip a
   * script-free page can offer.
   */
  static String render(
      List<UserflowReport.NetworkEdge> edges,
      Map<UserflowReport.NetworkEdge, List<String>> storiesByEdge) {
    List<List<Node>> columns = rank(edges);
    Map<String, Node> nodes = new HashMap<>();
    for (List<Node> column : columns) {
      for (Node node : column) {
        nodes.put(node.name, node);
      }
    }

    // One uniform gap between columns, wide enough for the widest label: these graphs are small,
    // and per-column gaps would buy a little space at the cost of a legible, predictable layout.
    double maxLabel = 0;
    for (UserflowReport.NetworkEdge edge : edges) {
      maxLabel = Math.max(maxLabel, displayLabel(edge).length() * CHAR_WIDTH + 24);
    }
    double gap = Math.max(200, maxLabel);

    // Column x extents; boxes vertically centered against the tallest column.
    double x = MARGIN;
    double maxColumnHeight = 0;
    for (List<Node> column : columns) {
      double width = 0;
      double height = -BOX_GAP;
      for (Node node : column) {
        width = Math.max(width, node.width());
        height += node.height() + BOX_GAP;
      }
      for (Node node : column) {
        node.centerX = x + width / 2;
      }
      maxColumnHeight = Math.max(maxColumnHeight, height);
      x += width + gap;
    }
    double width = x - gap + MARGIN;
    for (List<Node> column : columns) {
      double height = -BOX_GAP;
      for (Node node : column) {
        height += node.height() + BOX_GAP;
      }
      double y = TOP + (maxColumnHeight - height) / 2;
      for (Node node : column) {
        node.top = y;
        y += node.height() + BOX_GAP;
      }
    }
    double legendTop = TOP + maxColumnHeight + BOX_GAP;
    double height = legendTop + LEGEND_HEIGHT + MARGIN / 2.0;

    StringBuilder svg = new StringBuilder();
    svg.append("<svg class=\"network\" role=\"img\" xmlns=\"http://www.w3.org/2000/svg\"")
        .append(" viewBox=\"0 0 ")
        .append(Math.round(width))
        .append(' ')
        .append(Math.round(height))
        .append("\" width=\"")
        .append(Math.round(width))
        .append("\">\n<defs>")
        .append(marker("net-arrow", OBSERVED_COLOR))
        .append(marker("net-arrow-declared", DECLARED_COLOR))
        .append("</defs>\n");

    for (List<Node> column : columns) {
      for (Node node : column) {
        svg.append("<rect x=\"")
            .append(fmt(node.centerX - node.width() / 2))
            .append("\" y=\"")
            .append(fmt(node.top))
            .append("\" width=\"")
            .append(fmt(node.width()))
            .append("\" height=\"")
            .append(fmt(node.height()))
            .append("\" rx=\"6\" fill=\"#f3f4f6\" stroke=\"#d0d3d8\"/>\n")
            .append("<text x=\"")
            .append(fmt(node.centerX))
            .append("\" y=\"")
            .append(fmt(node.top + node.height() / 2 + 4))
            .append("\" text-anchor=\"middle\">")
            .append(Html.escape(node.name))
            .append("</text>\n");
      }
    }

    Map<String, Integer> outSeen = new HashMap<>();
    Map<String, Integer> inSeen = new HashMap<>();
    for (UserflowReport.NetworkEdge edge : edges) {
      Node from = nodes.get(edge.from());
      Node to = nodes.get(edge.to());
      int outIndex = outSeen.merge(edge.from(), 1, Integer::sum) - 1;
      int inIndex = inSeen.merge(edge.to(), 1, Integer::sum) - 1;
      double fromY = from.portY(outIndex, from.outCount);
      double toY = to.portY(inIndex, to.inCount);

      boolean declared = Boolean.TRUE.equals(edge.declared());
      String stroke = declared ? DECLARED_COLOR : OBSERVED_COLOR;
      String strokeExtras =
          switch (edge.kind()) {
            case "event" -> " stroke-dasharray=\"5 4\"";
            case "socket" -> " stroke-width=\"2.5\"";
            default -> "";
          };
      String markerRef = declared ? "url(#net-arrow-declared)" : "url(#net-arrow)";

      List<String> stories = storiesByEdge.get(edge);
      svg.append("<g>");
      if (stories != null && !stories.isEmpty()) {
        svg.append("<title>").append(Html.escape(String.join("\n", stories))).append("</title>");
      }
      double labelX;
      double labelY;
      if (from == to) {
        // A self-call: a small out-and-back loop on the right, labelled beside it.
        double startX = from.centerX + from.width() / 2;
        svg.append("<path d=\"M ")
            .append(fmt(startX))
            .append(' ')
            .append(fmt(fromY - 6))
            .append(" h 34 v 12 h -34\" fill=\"none\" stroke=\"")
            .append(stroke)
            .append("\"")
            .append(strokeExtras)
            .append(" marker-end=\"")
            .append(markerRef)
            .append("\"/>");
        labelX = startX + 42 + displayLabel(edge).length() * CHAR_WIDTH / 2;
        labelY = fromY - 2;
      } else if (to.centerX > from.centerX) {
        double startX = from.centerX + from.width() / 2;
        double endX = to.centerX - to.width() / 2;
        svg.append("<line x1=\"")
            .append(fmt(startX))
            .append("\" y1=\"")
            .append(fmt(fromY))
            .append("\" x2=\"")
            .append(fmt(endX))
            .append("\" y2=\"")
            .append(fmt(toY))
            .append("\" stroke=\"")
            .append(stroke)
            .append("\"")
            .append(strokeExtras)
            .append(" marker-end=\"")
            .append(markerRef)
            .append("\"/>");
        labelX = (startX + endX) / 2;
        labelY = (fromY + toY) / 2 - 6;
      } else {
        // Same column (or a right-to-left edge): bulge right so the arc never crosses a box.
        double startX = from.centerX + from.width() / 2;
        double endX = to.centerX + to.width() / 2;
        double bulge = Math.max(startX, endX) + 48;
        svg.append("<path d=\"M ")
            .append(fmt(startX))
            .append(' ')
            .append(fmt(fromY))
            .append(" C ")
            .append(fmt(bulge))
            .append(' ')
            .append(fmt(fromY))
            .append(' ')
            .append(fmt(bulge))
            .append(' ')
            .append(fmt(toY))
            .append(' ')
            .append(fmt(endX))
            .append(' ')
            .append(fmt(toY))
            .append("\" fill=\"none\" stroke=\"")
            .append(stroke)
            .append("\"")
            .append(strokeExtras)
            .append(" marker-end=\"")
            .append(markerRef)
            .append("\"/>");
        labelX = bulge + 8 + displayLabel(edge).length() * CHAR_WIDTH / 2;
        labelY = (fromY + toY) / 2 + 4;
      }
      svg.append("<text x=\"")
          .append(fmt(labelX))
          .append("\" y=\"")
          .append(fmt(labelY))
          .append("\" text-anchor=\"middle\"")
          .append(declared ? " fill=\"" + DECLARED_COLOR + "\"" : "")
          .append(">")
          .append(Html.escape(displayLabel(edge)))
          .append("</text></g>\n");
    }

    appendLegend(svg, legendTop);
    svg.append("</svg>\n");
    return svg.toString();
  }

  /** Kind prefixed for the kinds the strokes don't encode — mirror of the mermaid rule. */
  private static String displayLabel(UserflowReport.NetworkEdge edge) {
    return switch (edge.kind()) {
      case "http", "event", "socket" -> edge.label();
      default -> edge.kind() + ": " + edge.label();
    };
  }

  /**
   * Columns in left-to-right order, empty ones dropped: initiators (outgoing only), the service
   * under test (both directions, or a self-edge), callees (incoming only). Nodes sort
   * alphabetically within a column — canonical, like the edge order.
   */
  private static List<List<Node>> rank(List<UserflowReport.NetworkEdge> edges) {
    Map<String, Node> nodes = new LinkedHashMap<>();
    for (String name : new TreeSet<>(names(edges))) {
      nodes.put(name, new Node(name));
    }
    for (UserflowReport.NetworkEdge edge : edges) {
      nodes.get(edge.from()).outCount++;
      nodes.get(edge.to()).inCount++;
    }
    List<Node> initiators = new ArrayList<>();
    List<Node> center = new ArrayList<>();
    List<Node> callees = new ArrayList<>();
    for (Node node : nodes.values()) {
      if (node.outCount > 0 && node.inCount > 0) {
        center.add(node);
      } else if (node.outCount > 0) {
        initiators.add(node);
      } else {
        callees.add(node);
      }
    }
    List<List<Node>> columns = new ArrayList<>();
    for (List<Node> column : List.of(initiators, center, callees)) {
      if (!column.isEmpty()) {
        columns.add(column);
      }
    }
    return columns;
  }

  private static TreeSet<String> names(List<UserflowReport.NetworkEdge> edges) {
    TreeSet<String> names = new TreeSet<>();
    for (UserflowReport.NetworkEdge edge : edges) {
      names.add(edge.from());
      names.add(edge.to());
    }
    return names;
  }

  private static String marker(String id, String color) {
    return "<marker id=\""
        + id
        + "\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"7\" markerHeight=\"7\""
        + " orient=\"auto-start-reverse\"><path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\""
        + color
        + "\"/></marker>";
  }

  /** The always-rendered encoding key: provenance by color, kind by stroke. */
  private static void appendLegend(StringBuilder svg, double top) {
    double y = top + LEGEND_HEIGHT / 2.0;
    double x = MARGIN;
    x = legendEntry(svg, x, y, "observed", OBSERVED_COLOR, "", 1);
    x = legendEntry(svg, x, y, "declared", DECLARED_COLOR, "", 1);
    x = legendEntry(svg, x, y, "event", OBSERVED_COLOR, " stroke-dasharray=\"5 4\"", 1);
    legendEntry(svg, x, y, "socket", OBSERVED_COLOR, "", 2.5);
  }

  private static double legendEntry(
      StringBuilder svg, double x, double y, String label, String color, String extras,
      double strokeWidth) {
    svg.append("<line x1=\"")
        .append(fmt(x))
        .append("\" y1=\"")
        .append(fmt(y))
        .append("\" x2=\"")
        .append(fmt(x + 26))
        .append("\" y2=\"")
        .append(fmt(y))
        .append("\" stroke=\"")
        .append(color)
        .append("\"")
        .append(extras);
    if (strokeWidth != 1) {
      svg.append(" stroke-width=\"").append(fmt(strokeWidth)).append("\"");
    }
    svg.append("/>\n<text x=\"")
        .append(fmt(x + 32))
        .append("\" y=\"")
        .append(fmt(y + 4))
        .append("\" fill=\"#59636e\">")
        .append(label)
        .append("</text>\n");
    return x + 32 + label.length() * CHAR_WIDTH + 22;
  }

  private static String fmt(double value) {
    return value == Math.rint(value)
        ? Long.toString((long) value)
        : String.valueOf(Math.round(value * 10) / 10.0);
  }

  /** One box in the graph; geometry is filled in during layout. */
  private static final class Node {
    final String name;
    int outCount;
    int inCount;
    double centerX;
    double top;

    Node(String name) {
      this.name = name;
    }

    double width() {
      return name.length() * CHAR_WIDTH + 24;
    }

    double height() {
      return Math.max(BOX_MIN_HEIGHT, Math.max(outCount, inCount) * PORT_PITCH + 10);
    }

    /** The y of port {@code index} of {@code count} — evenly pitched around the box middle. */
    double portY(int index, int count) {
      return top + height() / 2 + (index - (count - 1) / 2.0) * PORT_PITCH;
    }
  }
}
