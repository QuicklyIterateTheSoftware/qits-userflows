package eu.wohlben.qits.userflows.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the recorded interactions as an inline SVG sequence diagram — actors across the top,
 * dashed lifelines, one labelled arrow per interaction in story order.
 *
 * <p>Drawn here, in Java, at emit time, because the alternative is a JavaScript diagram library
 * inside every published bundle: the HTML report must stay <b>self-contained</b> (the docs reader
 * serves it from an artifact store, and a bundle that dials a CDN is a bundle that renders only
 * where the internet does). The markdown report keeps its mermaid block for readers that render
 * mermaid; this SVG is the same data drawn once, for browsers.
 *
 * <p>Geometry is intentionally simple and fully deterministic: text width is estimated from a
 * character count (the font is monospace), so the same report always produces the same bytes.
 */
final class SequenceDiagramSvg {

  /** Estimated advance width of one monospace character at the font size used below. */
  private static final double CHAR_WIDTH = 7.3;

  private static final int ACTOR_BOX_HEIGHT = 32;
  private static final int ROW_HEIGHT = 40;
  private static final int MARGIN = 24;
  private static final int TOP = 12;

  private SequenceDiagramSvg() {}

  static String render(List<UserflowReport.Interaction> interactions) {
    // Actors in encounter order — the caller of the first interaction leads, as in mermaid.
    Map<String, Integer> actors = new LinkedHashMap<>();
    for (UserflowReport.Interaction interaction : interactions) {
      actors.putIfAbsent(interaction.from(), actors.size());
      actors.putIfAbsent(interaction.to(), actors.size());
    }

    // One spacing for every gap, wide enough for the widest actor box and the widest message —
    // uniform because most diagrams here are two or three actors, and a per-gap fit would buy
    // little for the irregularity it costs.
    double maxActor = 0;
    for (String actor : actors.keySet()) {
      maxActor = Math.max(maxActor, actor.length() * CHAR_WIDTH + 24);
    }
    double maxMessage = 0;
    for (UserflowReport.Interaction interaction : interactions) {
      maxMessage = Math.max(maxMessage, interaction.description().length() * CHAR_WIDTH + 24);
    }
    double spacing = Math.max(180, Math.max(maxActor + 16, maxMessage));

    int count = actors.size();
    double width = 2 * MARGIN + maxActor + (count <= 1 ? 0 : (count - 1) * spacing);
    double height = TOP + ACTOR_BOX_HEIGHT + interactions.size() * ROW_HEIGHT + MARGIN;

    double[] x = new double[count];
    for (int i = 0; i < count; i++) {
      x[i] = MARGIN + maxActor / 2 + i * spacing;
    }

    StringBuilder svg = new StringBuilder();
    svg.append("<svg class=\"sequence\" role=\"img\" xmlns=\"http://www.w3.org/2000/svg\"")
        .append(" viewBox=\"0 0 ")
        .append(Math.round(width))
        .append(' ')
        .append(Math.round(height))
        .append("\" width=\"")
        .append(Math.round(width))
        .append("\">\n")
        .append("<defs><marker id=\"arrow\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\"")
        .append(" markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\">")
        .append("<path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"#374151\"/></marker></defs>\n");

    // Lifelines first, so every arrow draws over them.
    double lifelineTop = TOP + ACTOR_BOX_HEIGHT;
    for (int i = 0; i < count; i++) {
      svg.append("<line x1=\"")
          .append(fmt(x[i]))
          .append("\" y1=\"")
          .append(fmt(lifelineTop))
          .append("\" x2=\"")
          .append(fmt(x[i]))
          .append("\" y2=\"")
          .append(fmt(height - MARGIN / 2.0))
          .append("\" stroke=\"#c3c8d0\" stroke-dasharray=\"4 4\"/>\n");
    }

    // Actor boxes.
    int index = 0;
    for (String actor : actors.keySet()) {
      double boxWidth = actor.length() * CHAR_WIDTH + 24;
      svg.append("<rect x=\"")
          .append(fmt(x[index] - boxWidth / 2))
          .append("\" y=\"")
          .append(TOP)
          .append("\" width=\"")
          .append(fmt(boxWidth))
          .append("\" height=\"")
          .append(ACTOR_BOX_HEIGHT)
          .append("\" rx=\"6\" fill=\"#f3f4f6\" stroke=\"#d0d3d8\"/>\n")
          .append("<text x=\"")
          .append(fmt(x[index]))
          .append("\" y=\"")
          .append(TOP + 21)
          .append("\" text-anchor=\"middle\">")
          .append(Html.escape(actor))
          .append("</text>\n");
      index++;
    }

    // Messages, in story order.
    int row = 0;
    for (UserflowReport.Interaction interaction : interactions) {
      double y = lifelineTop + (row + 1) * ROW_HEIGHT - 10;
      double from = x[actors.get(interaction.from())];
      double to = x[actors.get(interaction.to())];
      if (Double.compare(from, to) == 0) {
        // A self-message: a small out-and-back loop, labelled beside it.
        svg.append("<path d=\"M ")
            .append(fmt(from))
            .append(' ')
            .append(fmt(y - 12))
            .append(" h 34 v 12 h -34\" fill=\"none\" stroke=\"#374151\"")
            .append(" marker-end=\"url(#arrow)\"/>\n")
            .append("<text x=\"")
            .append(fmt(from + 42))
            .append("\" y=\"")
            .append(fmt(y - 8))
            .append("\">")
            .append(Html.escape(interaction.description()))
            .append("</text>\n");
      } else {
        svg.append("<line x1=\"")
            .append(fmt(from))
            .append("\" y1=\"")
            .append(fmt(y))
            .append("\" x2=\"")
            .append(fmt(to))
            .append("\" y2=\"")
            .append(fmt(y))
            .append("\" stroke=\"#374151\" marker-end=\"url(#arrow)\"/>\n")
            .append("<text x=\"")
            .append(fmt((from + to) / 2))
            .append("\" y=\"")
            .append(fmt(y - 6))
            .append("\" text-anchor=\"middle\">")
            .append(Html.escape(interaction.description()))
            .append("</text>\n");
      }
      row++;
    }

    svg.append("</svg>\n");
    return svg.toString();
  }

  /** No trailing zeros, no scientific notation — the same double always prints the same way. */
  private static String fmt(double value) {
    return value == Math.rint(value)
        ? Long.toString((long) value)
        : String.valueOf(Math.round(value * 10) / 10.0);
  }
}
