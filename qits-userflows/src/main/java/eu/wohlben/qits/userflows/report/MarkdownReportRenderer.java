package eu.wohlben.qits.userflows.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The default renderer: turns a {@link UserflowReport} into the human-facing {@code user-story.md}
 * — description, the recorded steps with each screenshot, command transcript and written-file dump
 * interleaved beneath its own step, a mermaid sequence diagram of the recorded interactions, and a
 * link to the video. It consumes the model only (never the live run) and never emits frontmatter,
 * so the document stays a portable, readable bundle alongside its relative-linked media.
 *
 * <p>One exception to model-only, and a deliberate one: a written file's <i>content</i> lives in an
 * artifact beside the sidecar rather than in the model (a config file is data, and inlining it
 * would bloat the canonical sidecar reviewers diff), so the dump is read back from {@code
 * reportDir}. The HTML renderer reads the same artifact, which is what keeps the two renderings
 * showing the same text.
 */
public final class MarkdownReportRenderer implements ReportRenderer {

  public static final String FILE_NAME = "user-story.md";

  @Override
  public void render(UserflowReport report, Path reportDir) throws IOException {
    StringBuilder md = new StringBuilder();
    md.append("# ").append(report.story()).append("\n");

    if (report.description() != null && !report.description().isBlank()) {
      md.append("\n## User flow\n\n").append(report.description().strip()).append("\n");
    }

    // Steps render as an indented (code-block) list, with each screenshot interleaved directly
    // beneath the step that produced it — the image follows its own step by construction, so a
    // mid-story screenshot lands in the right place, not lumped at the end.
    Map<String, UserflowReport.Screenshot> byStep = new HashMap<>();
    for (UserflowReport.Screenshot shot : report.screenshots()) {
      byStep.put(shot.step(), shot);
    }
    Map<String, UserflowReport.Command> commandByStep = new HashMap<>();
    if (report.commands() != null) {
      for (UserflowReport.Command command : report.commands()) {
        commandByStep.put(command.step(), command);
      }
    }
    Map<String, UserflowReport.WrittenFile> fileByStep = new HashMap<>();
    if (report.files() != null) {
      for (UserflowReport.WrittenFile file : report.files()) {
        fileByStep.put(file.step(), file);
      }
    }
    md.append("\n## Steps\n\n");
    for (UserflowReport.Step step : report.steps()) {
      md.append("    ").append(step.line()).append("\n"); // 4-space indent = code block
      UserflowReport.Screenshot shot = byStep.get(step.id());
      if (shot != null) {
        // blank lines close the code block before the image and reopen it for the next step
        md.append("\n![").append(shot.label()).append("](").append(shot.path()).append(")\n\n");
      }
      UserflowReport.Command command = commandByStep.get(step.id());
      if (command != null) {
        appendCommand(md, command);
      }
      UserflowReport.WrittenFile file = fileByStep.get(step.id());
      if (file != null) {
        appendWrittenFile(md, file, reportDir);
      }
    }

    if (report.interactions() != null && !report.interactions().isEmpty()) {
      // A mermaid sequence diagram of the recorded service-to-service interactions, in story
      // order. Hyphenated service names are valid mermaid actor identifiers as-is.
      startSection(md).append("## Interactions\n\n```mermaid\nsequenceDiagram\n");
      for (UserflowReport.Interaction interaction : report.interactions()) {
        md.append("    ")
            .append(interaction.from())
            .append("->>")
            .append(interaction.to())
            .append(": ")
            .append(interaction.description())
            .append("\n");
      }
      md.append("```\n");
    }

    if (report.video() != null) {
      startSection(md)
          .append("## Video\n\n[")
          .append(report.video().path())
          .append("](")
          .append(report.video().path())
          .append(")\n");
    }

    if (UserflowReport.FAILED.equals(report.outcome())) {
      startSection(md).append("> **Outcome: failed** — see the final step line above.\n");
    }

    Files.writeString(reportDir.resolve(FILE_NAME), md.toString(), StandardCharsets.UTF_8);
  }

  /**
   * A command's transcript under its own step: the excerpt in a {@code console}-tagged fence, then
   * — <b>outside</b> the fence, where it is readable rather than mistaken for output — the exit
   * code and a link to the full transcript. A silent command gets no fence at all: an empty code
   * block says nothing a reader can use.
   */
  private static void appendCommand(StringBuilder md, UserflowReport.Command command) {
    md.append("\n");
    if (command.output() != null && !command.output().isEmpty()) {
      String fence = Dumps.fence(command.output());
      md.append(fence).append("console\n").append(command.output());
      if (!command.output().endsWith("\n")) {
        md.append("\n");
      }
      md.append(fence).append("\n\n");
    }
    md.append("exit ").append(command.exitCode());
    if (command.outputPath() != null) {
      md.append(" — [full output](").append(command.outputPath()).append(")");
    }
    md.append("\n\n");
  }

  /**
   * A written fixture under its own {@code write} step: the content in an untagged fence (its
   * language is whatever the story wrote, and guessing would be worse than not saying), captioned
   * with the path. Read from the emitted dump; a missing or unreadable artifact renders as nothing
   * rather than failing the report.
   */
  private static void appendWrittenFile(
      StringBuilder md, UserflowReport.WrittenFile file, Path reportDir) {
    String content = Dumps.read(reportDir, file.contentPath());
    md.append("\n");
    if (!content.isEmpty()) {
      String fence = Dumps.fence(content);
      md.append(fence).append("\n").append(content);
      if (!content.endsWith("\n")) {
        md.append("\n");
      }
      md.append(fence).append("\n\n");
    }
    md.append("wrote `").append(file.path()).append("`\n\n");
  }

  /**
   * Trim trailing blank lines and re-add exactly one, so a section starts cleanly after whatever
   * the step interleaving left behind — without a global collapse that would mangle intentional
   * blank runs in the verbatim description.
   */
  private static StringBuilder startSection(StringBuilder md) {
    while (md.length() > 0 && md.charAt(md.length() - 1) == '\n') {
      md.setLength(md.length() - 1);
    }
    return md.append("\n\n");
  }
}
