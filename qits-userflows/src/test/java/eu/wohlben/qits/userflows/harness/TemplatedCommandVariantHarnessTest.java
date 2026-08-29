package eu.wohlben.qits.userflows.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import eu.wohlben.qits.userflows.Commands;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import java.util.List;
import org.junit.jupiter.api.AfterAll;

/**
 * The other half of the definition-hash pair: the <b>same</b> command template with a different
 * value. Ordered after {@link TemplatedCommandHarnessTest} (ordering only — nothing here depends on
 * that story <i>passing</i>) so both sidecars exist when the companion compares them.
 *
 * <p>What it proves is the whole point of keeping two parallel logs: the two stories' step lines
 * differ (each shows its own resolved command) while their {@code definitionHash} is
 * <b>identical</b> — the hash describes what a story <i>does</i>, not the data it does it with. A
 * per-run value leaking into the fingerprint would make every story's hash move on every run, and
 * the future {@code qits.userflow.hash} meaningless.
 */
class TemplatedCommandVariantHarnessTest {

  private static final String SLUG = "a-templated-command-runs-with-another-value";

  @UserStory("A templated command runs with another value")
  @UserStoryDescription("The same command template as its sibling, run against a different value.")
  @UserflowRunsAfter(TemplatedCommandHarnessTest.class)
  void runsWithAnotherValue(Commands commands) {
    commands.script("echo-arg.sh", TemplatedCommandHarnessTest.ECHO_SCRIPT);
    commands.run("./echo-arg.sh {}", "omega");
  }

  @AfterAll
  static void sameTemplateSameHashDifferentLines() {
    ReportAssertions.assertComplete(SLUG, UserflowReport.PASSED);
    ReportAssertions.assertCommand(SLUG, "./echo-arg.sh omega", 0);

    UserflowReport one = ReportAssertions.read(TemplatedCommandHarnessTest.SLUG);
    UserflowReport other = ReportAssertions.read(SLUG);
    assertEquals(
        one.definitionHash(),
        other.definitionHash(),
        "the same command template must hash the same however it is filled");

    List<String> oneLines = one.steps().stream().map(UserflowReport.Step::line).toList();
    List<String> otherLines = other.steps().stream().map(UserflowReport.Step::line).toList();
    assertNotEquals(
        oneLines, otherLines, "the display lines must still show each story's own resolved value");
  }
}
