package eu.wohlben.qits.userflows.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The browser-facing renderer: turns a {@link UserflowReport} into the story's {@code index.html}
 * — the same content as {@code user-story.md}, drawn for the one consumer the markdown cannot
 * reach. The docs reader ({@code qits-docs}) frames a published bundle at its {@code index.html}
 * and renders nothing itself, so a bundle of bare markdown is <i>cataloged but unreadable</i>
 * there; this file is what makes a userflow site open like every other docs site.
 *
 * <p><b>Self-contained by rule.</b> Styling is inline, the sequence diagram is
 * {@link SequenceDiagramSvg} rather than a script, and every reference (screenshots, the video,
 * the site index) is relative — the page must render byte-for-byte the same from a local {@code
 * target/userstories/} directory and from the artifact store, with no network beyond the bundle.
 *
 * <p>Like the markdown renderer it consumes the model only; {@code userflow.json} stays the
 * canonical sidecar and the two human renderings never read each other.
 */
public final class HtmlReportRenderer implements ReportRenderer {

  public static final String FILE_NAME = "index.html";

  /** The shared look, kept small enough to inline into every page of the bundle. */
  static final String CSS =
      """
      :root{color-scheme:light}
      body{margin:0 auto;max-width:60rem;padding:2rem 1.5rem 4rem;background:#fff;color:#1f2328;\
      font:16px/1.55 system-ui,-apple-system,'Segoe UI',sans-serif}
      a{color:#0757ba;text-decoration:none}a:hover{text-decoration:underline}
      h1{font-size:1.6rem;line-height:1.25;margin:.2rem 0 1rem}
      h2{font-size:1.05rem;margin:2.2rem 0 .8rem;border-bottom:1px solid #e4e7eb;\
      padding-bottom:.35rem}
      .crumbs{font-size:.85rem;color:#59636e}
      .lede{white-space:pre-line;color:#3a434c}
      .badge{display:inline-block;font-size:.72rem;font-weight:600;letter-spacing:.02em;\
      padding:.14rem .55rem;border-radius:99px;vertical-align:.22em;margin-left:.55rem}
      .passed{background:#dcfce7;color:#14652f}
      .failed{background:#fee2e2;color:#9a1c1c}
      ol.steps{margin:0;padding-left:1.4rem}
      ol.steps li{margin:.3rem 0}
      ol.steps code{font:.86em/1.5 ui-monospace,'Cascadia Code',Menlo,monospace;\
      background:#f5f6f8;border:1px solid #e4e7eb;border-radius:5px;padding:.1rem .4rem}
      figure{margin:.9rem 0 1.2rem}
      figure img{max-width:100%;height:auto;border:1px solid #d9dde2;border-radius:8px;\
      box-shadow:0 1px 4px rgba(31,35,40,.08)}
      figcaption{font-size:.85rem;color:#59636e;margin-top:.35rem}
      video{max-width:100%;border:1px solid #d9dde2;border-radius:8px}
      .diagram{overflow-x:auto}
      svg.sequence text{font:12px ui-monospace,'Cascadia Code',Menlo,monospace;fill:#1f2328}
      footer{margin-top:3rem;font-size:.8rem;color:#8b949e;\
      font-family:ui-monospace,'Cascadia Code',Menlo,monospace}
      .fail-note{background:#fee2e2;color:#9a1c1c;border-radius:8px;padding:.7rem 1rem}
      """;

  @Override
  public void render(UserflowReport report, Path reportDir) throws IOException {
    // The story sits one level under the site root, or two when categorized; the crumb must climb
    // exactly as far as the directory layout put it.
    String toRoot =
        report.category() == null || report.category().isBlank() ? "../" : "../../";

    StringBuilder html = new StringBuilder();
    html.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
        .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        .append("<title>")
        .append(Html.escape(report.story()))
        .append("</title>\n<style>")
        .append(CSS)
        .append("</style>\n</head>\n<body>\n");

    html.append("<nav class=\"crumbs\"><a href=\"")
        .append(toRoot)
        .append("index.html\">User flows</a>");
    if (report.category() != null && !report.category().isBlank()) {
      html.append(" / ").append(Html.escape(report.category()));
    }
    html.append("</nav>\n");

    html.append("<h1>")
        .append(Html.escape(report.story()))
        .append("<span class=\"badge ")
        .append(UserflowReport.FAILED.equals(report.outcome()) ? "failed" : "passed")
        .append("\">")
        .append(Html.escape(report.outcome()))
        .append("</span></h1>\n");

    if (report.description() != null && !report.description().isBlank()) {
      html.append("<p class=\"lede\">")
          .append(Html.escape(report.description().strip()))
          .append("</p>\n");
    }

    // Steps as an ordered list, each screenshot under the step that took it — the markdown
    // renderer's interleaving rule, kept identical so the two renderings never disagree about
    // where an image belongs.
    Map<String, UserflowReport.Screenshot> byStep = new HashMap<>();
    for (UserflowReport.Screenshot shot : report.screenshots()) {
      byStep.put(shot.step(), shot);
    }
    html.append("<h2>Steps</h2>\n<ol class=\"steps\">\n");
    for (UserflowReport.Step step : report.steps()) {
      html.append("<li><code>").append(Html.escape(step.line())).append("</code>");
      UserflowReport.Screenshot shot = byStep.get(step.id());
      if (shot != null) {
        html.append("\n<figure><img src=\"")
            .append(Html.escape(shot.path()))
            .append("\" alt=\"")
            .append(Html.escape(shot.label()))
            .append("\" width=\"")
            .append(shot.width())
            .append("\" height=\"")
            .append(shot.height())
            .append("\" loading=\"lazy\"><figcaption>")
            .append(Html.escape(shot.label()))
            .append("</figcaption></figure>\n");
      }
      html.append("</li>\n");
    }
    html.append("</ol>\n");

    if (report.interactions() != null && !report.interactions().isEmpty()) {
      html.append("<h2>Interactions</h2>\n<div class=\"diagram\">\n")
          .append(SequenceDiagramSvg.render(report.interactions()))
          .append("</div>\n");
    }

    if (report.video() != null) {
      html.append("<h2>Video</h2>\n<video controls preload=\"metadata\" src=\"")
          .append(Html.escape(report.video().path()))
          .append("\" width=\"")
          .append(report.video().width())
          .append("\" height=\"")
          .append(report.video().height())
          .append("\"></video>\n");
    }

    if (UserflowReport.FAILED.equals(report.outcome())) {
      html.append("<p class=\"fail-note\"><strong>Outcome: failed</strong> — see the final step")
          .append(" line above.</p>\n");
    }

    html.append("<footer>")
        .append(Html.escape(report.slug()))
        .append(" · definition ")
        .append(Html.escape(report.definitionHash()))
        .append("</footer>\n</body>\n</html>\n");

    Files.writeString(reportDir.resolve(FILE_NAME), html.toString(), StandardCharsets.UTF_8);
  }
}
