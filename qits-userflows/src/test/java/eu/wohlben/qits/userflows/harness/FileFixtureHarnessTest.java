package eu.wohlben.qits.userflows.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.userflows.Commands;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowPaths;
import eu.wohlben.qits.userflows.report.UserflowReport;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterAll;

/**
 * A story that writes a config fixture holding a credential, runs a command that prints it, and
 * {@linkplain Commands#redact redacts} the credential — the shape of every story that authenticates
 * against something.
 *
 * <p>Three things are being proved at once. The fixture's <b>content becomes a report artifact</b>
 * (the step line carries only the path, because a config file is data and a step line is one line),
 * and the secret <b>appears nowhere in the bundle</b> — not in the sidecar, the markdown, the HTML,
 * the transcript or the file dump — while the real file on disk still carried the real value, which
 * is why the command could print it at all. The leak check scans every file in the story directory
 * as raw bytes rather than the places we happened to think of.
 *
 * <p>The third is that redaction reaches the <b>network</b> too. An edge label is whatever a client
 * put on the wire, so a credential in a path would otherwise walk straight into the diagram: the
 * story observes exactly that edge, and the emitted label carries the mask instead.
 */
class FileFixtureHarnessTest {

  private static final String SLUG = "a-story-writes-a-fixture-and-redacts-a-secret";
  private static final String TOKEN = "s3cr3t-harness-token";

  @UserStory("A story writes a fixture and redacts a secret")
  @UserStoryDescription(
      """
      The story writes a config file carrying a credential, reads it back through a
      command, and redacts the credential out of everything the report publishes.
      """)
  void writesAndRedacts(Commands commands) {
    commands.redact(TOKEN);
    commands.file(
        "config/app.conf",
        """
        endpoint = https://example.invalid/api
        token = {}
        """,
        TOKEN);
    commands.run("cat config/app.conf").as("read-back");

    // A tap sees the raw wire, credential and all — recorded here exactly as one would arrive.
    NetworkCapture.observe(
        "http", "harness-client", "harness-server", "GET /secret/" + TOKEN + " -> 200");
  }

  @AfterAll
  static void theFixtureIsEvidenceAndTheSecretIsNot() throws IOException {
    ReportAssertions.assertComplete(SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(SLUG, "read-back");
    ReportAssertions.assertWroteFile(SLUG, "config/app.conf");
    ReportAssertions.assertCommand(SLUG, "cat config/app.conf", 0);

    // The command really did read the real value — the endpoint proves the file was the fixture.
    ReportAssertions.assertCommandOutputContains(SLUG, "cat config/app.conf", "example.invalid");
    // …and every rendering of it is masked, transcript and dump alike.
    ReportAssertions.assertCommandOutputContains(SLUG, "cat config/app.conf", "token = •••");
    // The observed edge label went through the same masker before it reached the sidecar.
    ReportAssertions.assertEdge(
        SLUG, "http", "harness-client", "harness-server", "GET /secret/••• -> 200");
    ReportAssertions.assertNotLeaked(SLUG, TOKEN);

    UserflowReport report = ReportAssertions.read(SLUG);
    assertNotNull(report.files(), "the written fixture must be recorded");
    assertEquals(1, report.files().size(), "one fixture, one record");
    // The step line carries the path only — never the content, never the secret.
    assertTrue(
        report.steps().stream().anyMatch(step -> step.line().equals("write config/app.conf")),
        () -> "expected a path-only write step: " + report.steps());

    String dump =
        Files.readString(
            UserflowPaths.reportDir(SLUG).resolve(report.files().get(0).contentPath()));
    assertTrue(dump.contains("token = •••"), dump);
    assertTrue(dump.contains("example.invalid"), dump);
  }
}
