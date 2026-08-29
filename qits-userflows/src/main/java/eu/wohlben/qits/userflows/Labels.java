package eu.wohlben.qits.userflows;

import java.util.regex.Pattern;

/**
 * The default label scrubber: rewrites run-local values — UUIDs, digests, bare numeric ids — into
 * stable placeholders, so an observed edge's label is template-shaped and the story's {@code
 * networkHash} does not move with every generated id. Public so a consumer-registered source can
 * scrub raw recorded paths itself before shaping a label.
 *
 * <p>Two positions are rewritten, and both are places a value can only have been <i>generated</i>:
 * a whole <b>path segment</b> (plus {@code sha256:…} digest literals anywhere), and a <b>query
 * value</b> that is a UUID or a long hex run. Everything else is deliberately left alone — an
 * ordinary query value is authored (a branch name, a version, {@code limit=10}) and is exactly the
 * shape the diagram exists to show. That is why pure digits after {@code =} are <b>not</b>
 * scrubbed although a bare numeric path segment is: {@code /tasks/42} is a row this run created,
 * {@code ?limit=10} is a number the story typed.
 *
 * <p>Deliberately conservative otherwise. A value that slips through still renders — it just moves
 * the hash, which is the visible symptom to fix with {@link NetworkCapture#labelNormalizer} or an
 * author-shaped label.
 */
public final class Labels {

  /** Shared by the path-segment and the query-value rule — one spelling, two positions. */
  private static final String UUID_FORM =
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

  private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-fA-F]{16,}");
  private static final Pattern UUID_SEGMENT =
      Pattern.compile("(?<=/)" + UUID_FORM + "(?=[/\\s?#]|$)");
  private static final Pattern HEX_SEGMENT =
      Pattern.compile("(?<=/)[0-9a-f]{32,}(?=[/\\s?#]|$)");
  private static final Pattern NUMERIC_SEGMENT = Pattern.compile("(?<=/)[0-9]+(?=[/\\s?#]|$)");

  // A query value runs from '=' to the next '&', whitespace or fragment — so the terminator set
  // differs from a path segment's, and the two cannot share a pattern.
  private static final Pattern UUID_VALUE =
      Pattern.compile("(?<==)" + UUID_FORM + "(?=[&\\s#]|$)");
  private static final Pattern HEX_VALUE = Pattern.compile("(?<==)[0-9a-f]{32,}(?=[&\\s#]|$)");

  private Labels() {}

  /**
   * {@code "GET /projects/3f2a…-…/tasks -> 200"} → {@code "GET /projects/{id}/tasks -> 200"}, and
   * {@code "GET /tasks?project=3f2a…-… -> 200"} → {@code "GET /tasks?project={id} -> 200"}.
   */
  public static String scrub(String label) {
    String scrubbed = DIGEST.matcher(label).replaceAll("{digest}");
    scrubbed = UUID_SEGMENT.matcher(scrubbed).replaceAll("{id}");
    scrubbed = HEX_SEGMENT.matcher(scrubbed).replaceAll("{digest}");
    scrubbed = UUID_VALUE.matcher(scrubbed).replaceAll("{id}");
    scrubbed = HEX_VALUE.matcher(scrubbed).replaceAll("{digest}");
    return NUMERIC_SEGMENT.matcher(scrubbed).replaceAll("{id}");
  }
}
