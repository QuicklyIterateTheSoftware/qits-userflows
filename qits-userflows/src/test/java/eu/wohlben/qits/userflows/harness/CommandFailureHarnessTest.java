package eu.wohlben.qits.userflows.harness;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.userflows.Commands;
import eu.wohlben.qits.userflows.ExpectedFailure;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowPaths;
import eu.wohlben.qits.userflows.report.UserflowReport;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterAll;

/**
 * The load-bearing guarantee of {@link Commands}: <b>a failing command's transcript survives into
 * the report</b>. The script prints a diagnosis and exits 3 while nothing lowered the default
 * expectation of 0, so the story fails on that line — and the step, the exit code and the printed
 * output must still be in the bundle, because that output is the only reason anyone opens the
 * report of a failed run.
 *
 * <p>{@link ExpectedFailure} keeps the suite green; the companion then asserts the failure was
 * reported honestly (partial log, appended {@code FAILED:} line naming the exit code, and the
 * command itself recorded with {@code exitCode: 3}).
 */
class CommandFailureHarnessTest {

  private static final String SLUG = "a-command-fails-and-still-reports";

  @UserStory("A command fails and still reports")
  @UserStoryDescription(
      "A command exits non-zero under the default expectation; the report keeps its transcript.")
  @ExpectedFailure
  void commandExitsNonZero(Commands commands) {
    commands.script(
        "fail.sh",
        """
        printf 'checking the thing\\n'
        printf 'the thing is broken\\n' >&2
        exit 3
        """);
    commands.run("./fail.sh"); // expects 0 by default → the story fails HERE
    commands.run("echo unreachable");
  }

  @AfterAll
  static void theFailingTranscriptSurvives() throws java.io.IOException {
    ReportAssertions.assertFailedWithPartialLog(SLUG);

    UserflowReport report = ReportAssertions.read(SLUG);
    assertNotNull(report.commands(), "the failing command must still be recorded");
    ReportAssertions.assertCommand(SLUG, "./fail.sh", 3);
    ReportAssertions.assertCommandOutputContains(SLUG, "./fail.sh", "the thing is broken");
    assertTrue(
        report.commands().size() == 1,
        () -> "the story stopped at the failing command: " + report.commands());

    // The appended FAILED: line names the exit code, so the step log alone explains the outcome.
    assertTrue(
        report.steps().stream()
            .anyMatch(
                step ->
                    step.line().startsWith("FAILED:")
                        && step.line().contains("expected exit 0 but got 3")),
        () -> "the FAILED line must name the exit code: " + report.steps());

    // The transcript artifact is on disk and the HTML flags it as the failing block.
    String html =
        Files.readString(UserflowPaths.reportDir(SLUG).resolve("index.html"));
    assertTrue(html.contains("<pre class=\"terminal failed\">"), html);
    assertTrue(html.contains("<span class=\"exit-bad\">exit 3</span>"), html);
  }
}
