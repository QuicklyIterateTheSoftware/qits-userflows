package eu.wohlben.qits.userflows.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.userflows.Commands;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import org.junit.jupiter.api.AfterAll;

/**
 * The mirror of {@link CommandFailureHarnessTest}: the <i>same</i> non-zero exit, this time
 * declared in advance with {@link Commands#expectExit}, so the story passes. That is the shape of
 * every story whose subject is a tool refusing — a linter finding a violation, a CLI rejecting a
 * bad flag — and the report records the real exit code (3) rather than pretending it was 0.
 *
 * <p>Also proves the expectation is <b>one-shot</b>: the command after it is back to expecting
 * success, and the story would fail if it were not.
 */
class ExpectedExitHarnessTest {

  private static final String SLUG = "a-story-expects-a-non-zero-exit";

  @UserStory("A story expects a non-zero exit")
  @UserStoryDescription("The tool is supposed to refuse; the story says so in advance and passes.")
  void expectsRefusal(Commands commands) {
    commands.script(
        "refuse.sh",
        """
        printf 'refusing: not allowed\\n' >&2
        exit 3
        """);
    commands.expectExit(3).run("./refuse.sh").as("refused");
    // one-shot: this one is back to expecting 0, and passing proves the expectation was consumed
    commands.run("echo carried on");
  }

  @AfterAll
  static void theRefusalIsRecordedHonestly() {
    ReportAssertions.assertComplete(SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(SLUG, "refused");
    ReportAssertions.assertCommand(SLUG, "./refuse.sh", 3);
    ReportAssertions.assertCommandOutputContains(SLUG, "./refuse.sh", "refusing: not allowed");
    ReportAssertions.assertCommand(SLUG, "echo carried on", 0);

    UserflowReport report = ReportAssertions.read(SLUG);
    assertEquals(2, report.commands().size(), "both commands ran");
  }
}
