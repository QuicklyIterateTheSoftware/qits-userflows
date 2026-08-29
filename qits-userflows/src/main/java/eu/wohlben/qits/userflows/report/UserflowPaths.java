package eu.wohlben.qits.userflows.report;

import java.nio.file.Path;

/**
 * Resolves where reports land — {@code target/userstories/<slug>/} by default, overridable with the
 * {@code qits.userflows.output-dir} system property. Shared by the extension (which writes) and
 * {@link ReportAssertions} (which reads), so both agree on the layout.
 *
 * <p>It also resolves the <i>other</i> per-story directory: the scratch space a shell-running story
 * works in ({@link #workDir}). The two are deliberately separate trees — the report directory is
 * evidence that gets published, the work directory is throwaway state that never does — so a
 * story's temp files can never end up in a shipped bundle by accident.
 */
public final class UserflowPaths {

  public static final String OUTPUT_DIR_PROPERTY = "qits.userflows.output-dir";
  private static final String DEFAULT_OUTPUT_DIR = "target/userstories";

  /** Where a shell-running story's scratch directory lives; see {@link #workDir}. */
  public static final String WORK_DIR_PROPERTY = "qits.userflows.work-dir";

  private static final String DEFAULT_WORK_DIR = "target/userstories-work";

  private UserflowPaths() {}

  public static Path outputRoot() {
    return Path.of(System.getProperty(OUTPUT_DIR_PROPERTY, DEFAULT_OUTPUT_DIR));
  }

  public static Path reportDir(String slug) {
    return outputRoot().resolve(slug);
  }

  /**
   * A categorized story's directory: {@code <root>/<category-slug>/<story-slug>/}. A blank
   * category is the flat layout — the two spellings agree by construction.
   */
  public static Path reportDir(String categorySlug, String slug) {
    return categorySlug == null || categorySlug.isBlank()
        ? reportDir(slug)
        : outputRoot().resolve(categorySlug).resolve(slug);
  }

  /**
   * A story's private scratch directory — {@code target/userstories-work/[<category-slug>/]<slug>/}
   * by default. The category segment is omitted for an uncategorized story, mirroring {@link
   * #reportDir(String, String)} exactly, so the two trees stay walkable with the same rule.
   *
   * <p>Created and wiped by the facade that needs it, never here and never up front: a story that
   * runs no commands must leave no directory behind.
   */
  public static Path workDir(String categorySlug, String slug) {
    Path root = Path.of(System.getProperty(WORK_DIR_PROPERTY, DEFAULT_WORK_DIR));
    return categorySlug == null || categorySlug.isBlank()
        ? root.resolve(slug)
        : root.resolve(categorySlug).resolve(slug);
  }
}
