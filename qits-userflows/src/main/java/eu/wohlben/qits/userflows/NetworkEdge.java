package eu.wohlben.qits.userflows;

/**
 * One edge of a story's network graph: {@code from} initiated {@code kind} traffic to {@code to},
 * described by the template-shaped {@code label} (e.g. {@code "GET /idp/jwks -> 200"}). Direction
 * is always <b>who initiated</b> — data may flow both ways on a socket, but the dependency is the
 * dial.
 *
 * <p>{@code kind} is an open vocabulary; the constants below cover the platform's known shapes,
 * and an unknown kind still renders (prefixed into the label) rather than failing. Labels must be
 * template-shaped — component names and {@code {id}} placeholders, never hosts, ports or run-local
 * values — because the canonically sorted edge set is hashed into the story's {@code networkHash}.
 * {@link Labels#scrub} is applied to every edge that reaches {@link NetworkCapture}; a source
 * supplying raw paths may rely on it.
 */
public record NetworkEdge(String kind, String from, String to, String label) {

  /** A plain HTTP request/response. */
  public static final String HTTP = "http";

  /** A delivered event / pushed frame (direction: the pusher initiates). */
  public static final String EVENT = "event";

  /** A long-lived socket connection (websocket, unix socket); direction is the dial. */
  public static final String SOCKET = "socket";

  /** A package-manager upload or download (npm, maven, OCI …), whatever its transport. */
  public static final String PACKAGE = "package";

  /** A spawned process talked to over its pipes. */
  public static final String PROCESS = "process";

  /** A database the component talks to directly. */
  public static final String JDBC = "jdbc";

  public NetworkEdge {
    requireText("kind", kind);
    requireText("from", from);
    requireText("to", to);
    requireText("label", label);
  }

  /** Sugar for the overwhelmingly common case — an observed HTTP edge. */
  public static NetworkEdge http(String from, String to, String label) {
    return new NetworkEdge(HTTP, from, to, label);
  }

  private static void requireText(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("network edge " + field + " must not be blank");
    }
  }
}
