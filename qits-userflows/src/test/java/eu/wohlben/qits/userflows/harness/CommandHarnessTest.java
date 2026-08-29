package eu.wohlben.qits.userflows.harness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.userflows.Commands;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowPaths;
import eu.wohlben.qits.userflows.report.UserflowReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;

/**
 * The shell-command smoke story: it writes an executable script, runs it with a templated value,
 * and runs a pipeline through {@code sh} — covering {@link Commands}'s three verbs end to end on a
 * plain POSIX box (no running qits, {@code /bin/sh} and {@code tr} only).
 *
 * <p>The {@code @AfterAll} companion proves what the facade promises: the transcripts reach the
 * bundle as artifacts, <b>stderr is merged into stdout</b> in the order the process wrote it, and
 * both human renderings carry the same excerpt — the markdown as a {@code console} fence, the HTML
 * as an escaped terminal block with no script anywhere.
 */
class CommandHarnessTest {

  private static final String SLUG = "a-story-runs-shell-commands";

  @UserStory("A story runs shell commands")
  @UserStoryDescription(
      """
      A story whose product is a CLI: it writes a script, runs it against a value,
      and pipes output through a second command — the transcript is the evidence.
      """)
  void runsCommands(Commands commands) {
    commands.script(
        "greet.sh",
        """
        printf 'hello %s\\n' "$1"
        printf 'a warning followed\\n' >&2
        """);
    commands.run("./greet.sh {}", "Ada").as("greeted");
    commands.sh("printf 'from a pipeline\\n' | tr a-z A-Z");
  }

  @AfterAll
  static void transcriptsReachTheBundle() throws IOException {
    ReportAssertions.assertComplete(SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(SLUG, "greeted");

    // The resolved command rides the sidecar; the value went in as ONE argv element.
    ReportAssertions.assertCommand(SLUG, "./greet.sh Ada", 0);
    ReportAssertions.assertCommandOutputContains(SLUG, "./greet.sh", "hello Ada");
    // stderr merged into the same transcript — the whole point of redirectErrorStream(true)
    ReportAssertions.assertCommandOutputContains(SLUG, "./greet.sh", "a warning followed");
    ReportAssertions.assertCommand(SLUG, "tr a-z A-Z", 0);
    ReportAssertions.assertCommandOutputContains(SLUG, "tr a-z A-Z", "FROM A PIPELINE");

    UserflowReport report = ReportAssertions.read(SLUG);
    assertNotNull(report.commands(), "commands must be recorded");
    assertNull(report.video(), "a shell-only story launches no browser");
    Path dir = UserflowPaths.reportDir(SLUG);
    for (UserflowReport.Command command : report.commands()) {
      assertNull(command.truncated(), () -> "short output must not be flagged: " + command);
      assertTrue(
          Files.size(dir.resolve(command.outputPath())) > 0,
          () -> "empty transcript artifact for " + command);
    }

    // The markdown fences the excerpt as a console block and puts the exit line OUTSIDE the fence.
    ReportAssertions.assertMarkdownContains(
        SLUG,
        List.of(
            "    run ./greet.sh Ada",
            "```console",
            "hello Ada",
            "exit 0 — [full output]("));

    // The HTML draws the same excerpt as an escaped terminal, and stays script-free.
    String html = Files.readString(dir.resolve("index.html"));
    assertTrue(html.contains("<pre class=\"terminal\">"), html);
    assertTrue(html.contains("hello Ada"), html);
    assertTrue(html.contains("<span class=\"exit-ok\">exit 0</span>"), html);
    assertFalse(html.contains("<script"), html);
  }
}
