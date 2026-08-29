package eu.wohlben.qits.userflows.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the markdown rules that are easy to get subtly wrong and impossible to notice by eye: a
 * command's transcript must be fenced <b>under its own step</b> with the exit line <i>outside</i>
 * the fence, the fence must be long enough that the content cannot close it early, and the network
 * block must escape everything a label might carry. A transcript containing a triple backtick is
 * not exotic — any story that runs a tool printing markdown produces one — and an escaped fence
 * corrupts every heading after it.
 */
class MarkdownReportRendererTest {

  @TempDir Path reportDir;

  private static UserflowReport report(
      List<UserflowReport.Command> commands, List<UserflowReport.WrittenFile> files) {
    return report(commands, files, null);
  }

  private static UserflowReport report(
      List<UserflowReport.Command> commands,
      List<UserflowReport.WrittenFile> files,
      List<UserflowReport.NetworkEdge> network) {
    return new UserflowReport(
        "Run a tool",
        "run-a-tool",
        null,
        null,
        List.of(
            new UserflowReport.Step("step-00", "write notes.md"),
            new UserflowReport.Step("ran", "run tool --print"),
            new UserflowReport.Step("step-02", "run tool --quiet")),
        "sha256:0",
        network,
        network == null ? null : Hashing.networkHash(network),
        commands,
        files,
        List.of(),
        null,
        UserflowReport.PASSED);
  }

  @Test
  void aTranscriptSitsUnderItsStepWithTheExitLineOutsideTheFence() throws IOException {
    new MarkdownReportRenderer()
        .render(
            report(
                List.of(
                    new UserflowReport.Command(
                        "ran", "tool --print", 0, "all good\n", "ran-tool-print.txt", null),
                    // a silent command: no fence at all, but still an exit line
                    new UserflowReport.Command("step-02", "tool --quiet", 2, null, null, null)),
                null),
            reportDir);

    String md = Files.readString(reportDir.resolve(MarkdownReportRenderer.FILE_NAME));
    int step = md.indexOf("    run tool --print");
    int fence = md.indexOf("```console", step);
    int exit = md.indexOf("exit 0 — [full output](ran-tool-print.txt)", step);
    int nextStep = md.indexOf("    run tool --quiet", step);
    assertTrue(step >= 0 && fence > step, md);
    // the exit line comes after the closing fence, so it reads as prose, not as output
    assertTrue(exit > md.indexOf("```", fence + 10), md);
    assertTrue(exit < nextStep, "the transcript must stay under its own step: " + md);
    // a silent command gets an exit line and no empty code block
    assertTrue(md.contains("exit 2\n"), md);
    assertEquals(1, countOccurrences(md, "```console"), md);
  }

  @Test
  void theFenceGrowsPastTheLongestBacktickRunInTheContent() throws IOException {
    String output = "here is a fence:\n```\nnested\n```\nand a ```` quad ````\n";
    new MarkdownReportRenderer()
        .render(
            report(
                List.of(
                    new UserflowReport.Command("ran", "tool --print", 0, output, null, null)),
                null),
            reportDir);

    String md = Files.readString(reportDir.resolve(MarkdownReportRenderer.FILE_NAME));
    // longest run inside is four, so the fence must be five
    assertTrue(md.contains("`````console\n"), md);
    assertTrue(md.contains("\n`````\n"), md);
  }

  @Test
  void aWrittenFileDumpsItsContentAndNamesItsPath() throws IOException {
    Files.writeString(reportDir.resolve("step-00-notes-md.txt"), "# notes\n");

    new MarkdownReportRenderer()
        .render(
            report(
                null,
                List.of(
                    new UserflowReport.WrittenFile(
                        "step-00", "notes.md", "step-00-notes-md.txt"))),
            reportDir);

    String md = Files.readString(reportDir.resolve(MarkdownReportRenderer.FILE_NAME));
    int step = md.indexOf("    write notes.md");
    assertTrue(step >= 0, md);
    assertTrue(md.indexOf("# notes", step) > step, md);
    assertTrue(md.indexOf("wrote `notes.md`", step) > step, md);
    // an untagged fence: the content's language is whatever the story wrote, and guessing is worse
    assertTrue(md.contains("```\n# notes\n```"), md);
  }

  @Test
  void aStoryWithNoCommandsRendersExactlyAsBefore() throws IOException {
    new MarkdownReportRenderer().render(report(null, null), reportDir);

    String md = Files.readString(reportDir.resolve(MarkdownReportRenderer.FILE_NAME));
    assertTrue(md.contains("    run tool --print\n    run tool --quiet\n"), md);
    assertTrue(!md.contains("```"), md);
    // No edges, no section: a story that captured nothing keeps a fence-free markdown.
    assertTrue(!md.contains("## Network"), md);
  }

  /**
   * The network block, byte for byte. Node ids run over the <i>sorted</i> distinct names so the
   * same edge set always renders the same bytes; the arrow encodes the kind, an unencoded kind
   * prefixes its label instead, a declared edge says so inside the label <i>and</i> gets a muted
   * dashed {@code linkStyle} addressing it by link index (node declarations create no links, so
   * the index is the edge's position), and everything mermaid could misread — a quote, an angle
   * bracket — is escaped on the way out.
   */
  @Test
  void theNetworkBlockIsCanonicalAndFullyEscaped() throws IOException {
    new MarkdownReportRenderer()
        .render(
            report(
                null,
                null,
                List.of(
                    new UserflowReport.NetworkEdge(
                        "http", "a caller", "qits-app", "GET /q?f=\"<x>\"", null),
                    new UserflowReport.NetworkEdge(
                        "process", "qits-app", "an engine", "spawn engine.sh", Boolean.TRUE))),
            reportDir);

    String md = Files.readString(reportDir.resolve(MarkdownReportRenderer.FILE_NAME));
    assertTrue(
        md.contains(
            """
            ## Network

            ```mermaid
            graph LR
                n0["a caller"]
                n1["an engine"]
                n2["qits-app"]
                n0 -->|"GET /q?f=#quot;#lt;x#gt;#quot;"| n2
                n2 -->|"process: spawn engine.sh [declared]"| n1
                linkStyle 1 stroke:#8b949e,stroke-dasharray:3 3
            ```
            """),
        md);
    // The section follows the steps and is the only fenced block in a story that ran no commands.
    assertTrue(md.indexOf("## Steps") < md.indexOf("## Network"), md);
    assertEquals(1, countOccurrences(md, "```mermaid"), md);
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
}
