package eu.wohlben.qits.userflows.harness;

import eu.wohlben.qits.userflows.Commands;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import org.junit.jupiter.api.AfterAll;

/**
 * Half of the definition-hash pair: this story runs a templated command with one value, {@link
 * TemplatedCommandVariantHarnessTest} runs the <b>same template</b> with another, and that class's
 * companion asserts the two definition hashes are equal. Kept as two classes because a hash is only
 * interesting across separately-recorded stories.
 *
 * <p>The template is what enters the fingerprint ({@code run ./echo-arg.sh {}}) while the resolved
 * command is what enters the step line — the same fill rule {@code Flow.navigate(template, args)}
 * follows, for the same reason: a story's <i>definition</i> must not move when its data does.
 */
class TemplatedCommandHarnessTest {

  static final String SLUG = "a-templated-command-runs-with-one-value";

  /** The script body both halves of the pair write — identical, or the hashes could not match. */
  static final String ECHO_SCRIPT =
      """
      printf 'value is %s\\n' "$1"
      """;

  @UserStory("A templated command runs with one value")
  @UserStoryDescription(
      "Records a templated command; its sibling records the same one differently.")
  void runsWithOneValue(Commands commands) {
    commands.script("echo-arg.sh", ECHO_SCRIPT);
    commands.run("./echo-arg.sh {}", "alpha");
  }

  @AfterAll
  static void theResolvedValueRidesTheStepLine() {
    ReportAssertions.assertComplete(SLUG, UserflowReport.PASSED);
    ReportAssertions.assertCommand(SLUG, "./echo-arg.sh alpha", 0);
    ReportAssertions.assertCommandOutputContains(SLUG, "./echo-arg.sh", "value is alpha");
  }
}
