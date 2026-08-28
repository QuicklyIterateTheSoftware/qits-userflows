package eu.wohlben.qits.userflows.report;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SiteIndexWriterTest {

  @TempDir Path root;

  private void sidecar(Path dir, String story, String category, String outcome)
      throws IOException {
    Files.createDirectories(dir);
    String categoryMember = category == null ? "" : "\"category\": \"" + category + "\",";
    Files.writeString(
        dir.resolve(JsonReportWriter.FILE_NAME),
        "{\"story\": \"" + story + "\"," + categoryMember + "\"outcome\": \"" + outcome + "\"}");
  }

  @Test
  void listsBothLayoutsGroupedByCategoryWithTheFlatTailLast() throws IOException {
    sidecar(
        root.resolve("authentication").resolve("token-accepted"),
        "A token opens the door",
        "authentication",
        UserflowReport.PASSED);
    sidecar(
        root.resolve("authentication").resolve("token-denied"),
        "A stranger is refused",
        "authentication",
        UserflowReport.FAILED);
    sidecar(root.resolve("greeting"), "Create a greeting", null, UserflowReport.PASSED);

    SiteIndexWriter.rewrite(root);

    String html = Files.readString(root.resolve("index.html"));
    assertTrue(html.contains("<h2>authentication</h2>"), html);
    // Stories link into their own directories, category-deep where categorized.
    assertTrue(html.contains("href=\"authentication/token-accepted/index.html\""), html);
    assertTrue(html.contains("href=\"greeting/index.html\""), html);
    // Within a category the order is by title, and the outcome rides as a badge.
    assertTrue(
        html.indexOf("A stranger is refused") < html.indexOf("A token opens the door"), html);
    assertTrue(html.contains("badge failed"), html);
    // The category-less story lists after the named categories, under its own heading.
    assertTrue(html.indexOf("<h2>authentication</h2>") < html.indexOf("<h2>stories</h2>"), html);
    assertTrue(html.indexOf("<h2>stories</h2>") < html.indexOf("Create a greeting"), html);
  }

  @Test
  void anEmptyRootWritesNothing() throws IOException {
    SiteIndexWriter.rewrite(root.resolve("does-not-exist"));
    // No directory, no index — and no exception: the writer runs after every story emit, and the
    // first story is what creates the root.
    assertTrue(Files.notExists(root.resolve("does-not-exist").resolve("index.html")));
  }
}
