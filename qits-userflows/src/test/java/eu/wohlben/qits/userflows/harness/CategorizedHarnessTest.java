package eu.wohlben.qits.userflows.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowPaths;
import eu.wohlben.qits.userflows.report.UserflowReport;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterAll;

/**
 * A categorized story: the report lands under {@code <category-slug>/<story-slug>/} — the
 * directory layout is what carries the grouping to a reader — and the sidecar records the
 * category's display name.
 */
class CategorizedHarnessTest {

  private static final String CATEGORY_SLUG = "harness-grouping";
  private static final String SLUG = "a-story-belongs-to-a-category";

  @UserStory(value = "A story belongs to a category", category = "Harness grouping")
  @UserStoryDescription("Covers the categorized report layout and the sidecar's category field.")
  void categorizedStory(Interactions interactions) {
    interactions.note("a categorized story records like any other");
  }

  @AfterAll
  static void reportLandsUnderItsCategory() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, SLUG, UserflowReport.PASSED);
    UserflowReport report = ReportAssertions.read(CATEGORY_SLUG, SLUG);
    assertEquals("Harness grouping", report.category(), "the display name rides the sidecar");
    assertTrue(
        Files.isDirectory(UserflowPaths.outputRoot().resolve(CATEGORY_SLUG).resolve(SLUG)),
        "the category is a directory level");
    assertTrue(
        !Files.exists(UserflowPaths.outputRoot().resolve(SLUG)),
        "a categorized story must not also emit at the flat location");
  }
}
