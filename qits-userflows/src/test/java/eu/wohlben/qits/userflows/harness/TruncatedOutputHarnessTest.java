package eu.wohlben.qits.userflows.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.userflows.Commands;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowPaths;
import eu.wohlben.qits.userflows.report.UserflowReport;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * A command that prints far more than the capture cap allows. The cap is lowered to a few kilobytes
 * for this class only (via the documented system property) so the behaviour can be proven in a
 * second instead of by generating a megabyte.
 *
 * <p>What must hold is that truncation is <b>head + tail with an explicit marker</b>, never a
 * silent prefix: the first line (which identifies the run) and the last line (which is usually the
 * error) both survive, and the report says in so many words how much it dropped. The sidecar
 * additionally flags {@code truncated: true}, so a consumer never mistakes an excerpt for the
 * whole story.
 */
class TruncatedOutputHarnessTest {

  private static final String SLUG = "a-command-prints-more-than-the-cap";
  private static final String CAP_PROPERTY = "qits.userflows.command.max-output-bytes";
  private static final int CAP = 4096;

  @BeforeAll
  static void lowerTheCap() {
    System.setProperty(CAP_PROPERTY, Integer.toString(CAP));
  }

  @UserStory("A command prints more than the cap")
  @UserStoryDescription("Bulk output is kept as head plus tail with an explicit omission marker.")
  void printsTooMuch(Commands commands) {
    // POSIX awk only — no seq, no bash-isms, so this runs anywhere /bin/sh does.
    commands.script(
        "bulk.sh",
        """
        awk 'BEGIN { for (i = 1; i <= 400; i++) printf "line %04d padded with filler text\\n", i }'
        """);
    commands.run("./bulk.sh").as("bulk");
  }

  @AfterAll
  static void headAndTailBothSurvive() throws IOException {
    try {
      ReportAssertions.assertComplete(SLUG, UserflowReport.PASSED);

      UserflowReport report = ReportAssertions.read(SLUG);
      UserflowReport.Command command = report.commands().get(0);
      assertEquals(Boolean.TRUE, command.truncated(), "the cap dropped bytes and must say so");

      String transcript =
          Files.readString(UserflowPaths.reportDir(SLUG).resolve(command.outputPath()));
      assertTrue(transcript.contains("line 0001"), "the head must survive the cap");
      assertTrue(transcript.contains("line 0400"), "the tail must survive the cap");
      assertTrue(transcript.contains(" bytes omitted …"), "the artifact must mark the omission");
      assertTrue(
          transcript.length() < 400 * 40,
          () -> "the artifact was not capped at all: " + transcript.length());

      // The inline excerpt obeys the same rule, so a sidecar reader sees the same two ends.
      assertTrue(command.output().contains("line 0001"), command.output());
      assertTrue(command.output().contains("line 0400"), command.output());
      assertTrue(command.output().contains(" omitted …"), command.output());

      ReportAssertions.assertMarkdownContains(SLUG, java.util.List.of("omitted …"));
    } finally {
      // Cleared here rather than in a second @AfterAll: the order between two @AfterAll methods is
      // unspecified, and clearing before the assertions read the report would be a coin flip.
      System.clearProperty(CAP_PROPERTY);
    }
  }
}
