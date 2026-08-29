package eu.wohlben.qits.userflows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The JVM-wide registry of <b>passively observed</b> network traffic — the far end of every
 * generated network diagram. Test infrastructure feeds it two ways:
 *
 * <ul>
 *   <li>{@link #source} registers a <b>cumulative</b> recording (a mock's request log, a parsed
 *       access log): the supplier is read lazily at story end, and a per-source cursor ensures
 *       each recorded entry lands in exactly one story — traffic produced before the first story
 *       (a startup JWKS fetch) attaches to the first story that drains.
 *   <li>{@link #observe} appends one edge immediately — for taps that see requests as they happen
 *       (the shipped {@link NetworkTaps#restAssured(String) RestAssured tap}, an instrumented
 *       websocket client).
 * </ul>
 *
 * <p>Taps cannot know narrative roles, so {@link #actor} keeps a sticky initiator name (
 * {@code "a platform service"}, {@code "an impostor"}) a story sets before acting; a tap reads it
 * via {@link #actor()} when it shapes an edge's {@code from}.
 *
 * <p>Every label that passes through here is scrubbed by {@link Labels#scrub} and then by the
 * optional {@link #labelNormalizer} — observed labels must be template-shaped because the drained
 * edge set is hashed into the story's {@code networkHash}. Callers therefore never need to scrub
 * themselves: {@link Labels} is public only so a source that <i>builds</i> a label by hand can
 * pre-shape it. Author-<i>declared</i> edges bypass this class entirely ({@link Network#declare});
 * declarations are already literals.
 *
 * <p><b>Attribution rides story order.</b> Traffic recorded before the first drain (a startup
 * JWKS fetch) lands in whichever story drains first, and nothing but the author's assertions can
 * notice a misattribution. A class whose first story owns startup traffic must pin its method
 * order (e.g. {@code @TestMethodOrder}) and {@code assertEdge} that edge on that story.
 *
 * <p>State is static on purpose: stories run sequentially in one JVM, but the code that records
 * (a test profile, a filter, a subscriber thread) and the story method live in different scopes,
 * and a static registry is the one thing they all share. Registrations survive across stories —
 * re-registering under the same id replaces the supplier but keeps its cursor.
 */
public final class NetworkCapture {

  private static final String DEFAULT_ACTOR = "a caller";

  private static final Object LOCK = new Object();
  private static final Map<String, Source> SOURCES = new LinkedHashMap<>();
  private static final List<NetworkEdge> OBSERVED = new ArrayList<>();
  private static int observedCursor = 0;
  private static String actor = DEFAULT_ACTOR;
  private static UnaryOperator<String> labelNormalizer = UnaryOperator.identity();

  private NetworkCapture() {}

  /**
   * Register (or replace) the cumulative edge source {@code id}. The supplier must return the
   * <b>whole</b> recording each time — the registry remembers how much of it previous stories
   * consumed. It is only invoked at drain time, so registering in {@code @BeforeAll} is safe even
   * while the recorded system is still starting.
   */
  public static void source(String id, Supplier<List<NetworkEdge>> supplier) {
    synchronized (LOCK) {
      Source existing = SOURCES.get(id);
      SOURCES.put(id, new Source(supplier, existing == null ? 0 : existing.cursor));
    }
  }

  /** Append one observed edge now; the label is scrubbed and normalized on the way in. */
  public static void observe(String kind, String from, String to, String label) {
    synchronized (LOCK) {
      OBSERVED.add(new NetworkEdge(kind, from, to, normalize(label)));
    }
  }

  /**
   * Set the sticky narrative initiator taps use as {@code from} for incoming traffic. Sticky
   * <b>within</b> a story only: the extension resets it to the default at every story start, so a
   * story can never silently inherit the previous story's actor — each one names its own
   * initiators before its first call.
   */
  public static void actor(String name) {
    synchronized (LOCK) {
      actor = name;
    }
  }

  /** Story-start reset — called by the extension so actor stickiness ends at the story border. */
  static void resetActor() {
    synchronized (LOCK) {
      actor = DEFAULT_ACTOR;
    }
  }

  /** The current narrative initiator (default {@code "a caller"}). */
  public static String actor() {
    synchronized (LOCK) {
      return actor;
    }
  }

  /**
   * Compose {@code normalizer} over the default {@link Labels#scrub} for every subsequently
   * observed or drained label — the escape hatch for run-local values the default misses.
   */
  public static void labelNormalizer(UnaryOperator<String> normalizer) {
    synchronized (LOCK) {
      labelNormalizer = normalizer;
    }
  }

  /**
   * Everything recorded since the previous drain, in registration/arrival order — consumed once
   * per story by the extension. Source cursors clamp back to zero if a recording shrank (a mock
   * reset), so a reset costs re-attribution, never a crash.
   */
  static List<NetworkEdge> drain() {
    synchronized (LOCK) {
      List<NetworkEdge> drained = new ArrayList<>();
      for (Source source : SOURCES.values()) {
        List<NetworkEdge> all = source.supplier.get();
        if (source.cursor > all.size()) {
          source.cursor = 0;
        }
        for (NetworkEdge edge : all.subList(source.cursor, all.size())) {
          drained.add(
              new NetworkEdge(edge.kind(), edge.from(), edge.to(), normalize(edge.label())));
        }
        source.cursor = all.size();
      }
      drained.addAll(OBSERVED.subList(observedCursor, OBSERVED.size()));
      observedCursor = OBSERVED.size();
      return drained;
    }
  }

  private static String normalize(String label) {
    return labelNormalizer.apply(Labels.scrub(label));
  }

  /** A registered supplier plus how much of its cumulative recording is already attributed. */
  private static final class Source {
    final Supplier<List<NetworkEdge>> supplier;
    int cursor;

    Source(Supplier<List<NetworkEdge>> supplier, int cursor) {
      this.supplier = supplier;
      this.cursor = cursor;
    }
  }
}
