package eu.wohlben.qits.userflows.report;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two negative network assertions, in both directions. A harness story can show one of them
 * passing, and {@code ServiceInteractionHarnessTest} does; what a story cannot show is the
 * assertion <b>failing</b> when the claim is false — and an absence assertion that cannot fail is
 * worth nothing at all, which is exactly the defect these two are meant to catch elsewhere.
 *
 * <p>So the fixture is an emitted bundle written by the real renderers into a temp directory, with
 * {@code qits.userflows.output-dir} pointed at it for the length of one test. That property is
 * JVM-global — the same caution the "never drain from a unit test" rule states — so it is restored
 * in {@code @AfterEach}, whatever the test did.
 */
class ReportAssertionsTest {

  private static final String SLUG = "a-guarded-boundary";

  @TempDir Path outputRoot;

  private String previousOutputDir;

  @BeforeEach
  void pointTheReadersAtTheTempBundle() {
    previousOutputDir = System.getProperty(UserflowPaths.OUTPUT_DIR_PROPERTY);
    System.setProperty(UserflowPaths.OUTPUT_DIR_PROPERTY, outputRoot.toString());
  }

  @AfterEach
  void giveTheOutputDirectoryBack() {
    if (previousOutputDir == null) {
      System.clearProperty(UserflowPaths.OUTPUT_DIR_PROPERTY);
    } else {
      System.setProperty(UserflowPaths.OUTPUT_DIR_PROPERTY, previousOutputDir);
    }
  }

  @Test
  void nothingReachedTheProtectedThing() throws IOException {
    emit(
        List.of(
            edge("http", "an impostor", "qits-app", "POST /admin/settings -> 403"),
            edge("http", "an operator", "qits-app", "GET /admin/settings -> 200")));

    ReportAssertions.assertNoEdgesTo(SLUG, "the store");

    AssertionError reached =
        assertThrows(
            AssertionError.class, () -> ReportAssertions.assertNoEdgesTo(SLUG, "qits-app"));
    assertTrue(
        reached.getMessage().contains("expected no edges to qits-app"), reached.getMessage());
  }

  @Test
  void onlyThePromisedActorsInitiatedAnything() throws IOException {
    emit(
        List.of(
            edge("package", "an operator", "qits-artifacts", "PUT /npm/@qits/thing -> 201"),
            edge("package", "an operator", "qits-artifacts", "GET /npm/npm -> 404")));

    // The count of a package flow belongs to the client — its update-notifier fetched a package
    // nobody asked for — but the set of initiators is still the story's promise.
    ReportAssertions.assertOnlyEdgesFrom(SLUG, "an operator");
    ReportAssertions.assertOnlyEdgesFrom(SLUG, "an operator", "an impostor");

    AssertionError stranger =
        assertThrows(
            AssertionError.class, () -> ReportAssertions.assertOnlyEdgesFrom(SLUG, "an impostor"));
    assertTrue(
        stranger.getMessage().contains("unexpected initiator 'an operator'"),
        stranger.getMessage());

    // The categorized spelling names its actors as a list, and refuses the same edge.
    ReportAssertions.assertOnlyEdgesFrom("", SLUG, List.of("an operator"));
    assertThrows(
        AssertionError.class,
        () -> ReportAssertions.assertOnlyEdgesFrom("", SLUG, List.of("an impostor")));
  }

  private static UserflowReport.NetworkEdge edge(
      String kind, String from, String to, String label) {
    return new UserflowReport.NetworkEdge(kind, from, to, label, null);
  }

  /** A real bundle: the sidecar and markdown the shipped renderers write, nothing hand-rolled. */
  private void emit(List<UserflowReport.NetworkEdge> network) throws IOException {
    Path dir = UserflowPaths.reportDir(SLUG);
    Files.createDirectories(dir);
    UserflowReport report =
        new UserflowReport(
            "A guarded boundary",
            SLUG,
            null,
            null,
            List.of(new UserflowReport.Step("step-00", "the story ran")),
            "sha256:0",
            network,
            Hashing.networkHash(network),
            null,
            null,
            List.of(),
            null,
            UserflowReport.PASSED);
    new JsonReportWriter().render(report, dir);
    new MarkdownReportRenderer().render(report, dir);
  }
}
