package eu.wohlben.qits.userflows.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.userflows.Commands;
import eu.wohlben.qits.userflows.ExpectedFailure;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;

/**
 * The timeout path: a command that prints and then hangs is destroyed, and <b>what it printed
 * before it stopped printing still reaches the report</b>. A hung build step's last few lines are
 * usually the only clue to where it hung, so losing them would make the timeout unreportable.
 *
 * <p>The script {@code exec}s the sleeper rather than spawning it, so the process we kill is the
 * one holding the pipe — a grandchild inheriting the pipe would keep the reader blocked until the
 * bounded join gives up, which is correct but slow, and not what this story is measuring.
 */
class CommandTimeoutHarnessTest {

  private static final String SLUG = "a-command-outruns-its-timeout";

  @UserStory("A command outruns its timeout")
  @UserStoryDescription("A hung command is destroyed, and its partial transcript still reports.")
  @ExpectedFailure
  void hangsPastTheTimeout(Commands commands) {
    commands.script(
        "hang.sh",
        """
        printf 'started and about to hang\\n'
        exec sleep 30
        """);
    commands.timeout(Duration.ofMillis(500)).run("./hang.sh");
  }

  @AfterAll
  static void thePartialTranscriptSurvivesTheKill() {
    ReportAssertions.assertFailedWithPartialLog(SLUG);
    // 124 is GNU timeout(1)'s convention, so the sidecar reads the way a shell would report it.
    ReportAssertions.assertCommand(SLUG, "./hang.sh", 124);
    ReportAssertions.assertCommandOutputContains(SLUG, "./hang.sh", "started and about to hang");

    UserflowReport report = ReportAssertions.read(SLUG);
    assertEquals(1, report.commands().size(), "one command ran");
    assertTrue(
        report.steps().stream()
            .anyMatch(
                step ->
                    step.line().startsWith("FAILED:")
                        && step.line().contains("timed out")),
        () -> "the FAILED line must say it timed out: " + report.steps());
  }
}
