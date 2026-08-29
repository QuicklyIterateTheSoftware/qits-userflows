package eu.wohlben.qits.userflows.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The three rules the two human renderers must agree on when they show a command's output or a
 * written file's content: how a dump is read back, how much of it is shown, and how a code fence is
 * chosen so the content cannot escape it.
 *
 * <p>They live here rather than in either renderer because <b>the markdown and the HTML must show
 * the same text</b>. A reader who compares the two renderings of one story and finds different
 * excerpts has been given two different reports; making that impossible is worth one small shared
 * class.
 *
 * <p>Reading is the one place a renderer touches the report directory rather than the model. A
 * written file's content is an artifact, not a field — inlining a config file would bloat the
 * canonical sidecar reviewers diff — so the dump is read back beside the sidecar. An unreadable
 * dump renders as nothing: a report must never fail to render over a missing attachment.
 */
final class Dumps {

  /** Matches the model's inline command excerpt, so both kinds of block obey one rule. */
  private static final int EXCERPT_HEAD_CHARS = 2000;

  private static final int EXCERPT_TAIL_CHARS = 2000;

  private Dumps() {}

  /** The excerpted content of an emitted dump; empty when there is nothing readable to show. */
  static String read(Path reportDir, String contentPath) {
    if (contentPath == null || contentPath.isBlank()) {
      return "";
    }
    try {
      return excerpt(Files.readString(reportDir.resolve(contentPath), StandardCharsets.UTF_8));
    } catch (IOException e) {
      return "";
    }
  }

  /** Head + tail with an explicit omission marker — never a silent shortening. */
  static String excerpt(String text) {
    int limit = EXCERPT_HEAD_CHARS + EXCERPT_TAIL_CHARS;
    if (text == null || text.length() <= limit) {
      return text == null ? "" : text;
    }
    return text.substring(0, EXCERPT_HEAD_CHARS)
        + "\n… "
        + (text.length() - limit)
        + " characters omitted …\n"
        + text.substring(text.length() - EXCERPT_TAIL_CHARS);
  }

  /**
   * The shortest fence that cannot be closed early by {@code content}: three backticks, or one more
   * than the longest backtick run inside it. Command output routinely contains backticks (a shell
   * error quoting a token, a markdown file being written), and a fence that ends mid-transcript
   * corrupts every heading and link after it.
   */
  static String fence(String content) {
    int longest = 0;
    int run = 0;
    for (int i = 0; content != null && i < content.length(); i++) {
      if (content.charAt(i) == '`') {
        run++;
        longest = Math.max(longest, run);
      } else {
        run = 0;
      }
    }
    return "`".repeat(Math.max(3, longest + 1));
  }
}
