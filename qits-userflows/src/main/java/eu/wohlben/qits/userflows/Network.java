package eu.wohlben.qits.userflows;

import eu.wohlben.qits.userflows.report.UserflowReport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The per-story network facade. Almost everything in a story's network section arrives
 * <i>passively</i> — taps and recordings feed {@link NetworkCapture}, and the extension drains
 * them at story end. This facade exists for the two things passivity cannot do:
 *
 * <ul>
 *   <li>{@link #declare} an edge no tap can observe — a spawned process talked to over pipes, a
 *       JDBC store, a docker socket. Declared edges render visually distinct from observed ones
 *       and carry {@code "declared": true} in the sidecar: the diagram never passes a claim off
 *       as evidence.
 *   <li>{@link #actor} names the narrative initiator for the incoming traffic that follows —
 *       sugar for {@link NetworkCapture#actor}.
 * </ul>
 *
 * <p>Neither records a step: edges describe what traffic happened around the story, not what the
 * author did, so they live outside the step log and outside the {@code definitionHash}. Their own
 * stability contract is the {@code networkHash} over the canonically sorted edge set. An absence
 * claim ("…and nothing was pushed") is an assertion, not a dependency — record it with
 * {@link Interactions#note}, never here.
 *
 * <p>Instances are created by {@link UserStoryExtension}; stories receive one as a method
 * parameter. Declaring {@code Network} (like {@code Interactions} or {@code Commands}) without a
 * {@link Flow} keeps a story browserless.
 */
public final class Network {

  private final List<NetworkEdge> declared = new ArrayList<>();

  Network() {}

  /**
   * Record an edge the story <b>knows</b> but no tap can see, e.g. {@code declare("process",
   * "qits-stt", "the speech engine", "spawn venv/bin/python transcribe.py")}. All four fields are
   * author-written literals — template-shaped, never ports or generated ids — and are not scrubbed.
   *
   * <p>They are <b>checked</b> instead: a field {@link Labels#scrub} would rewrite is an {@link
   * IllegalArgumentException} naming the field and the shape it should have had. Unscrubbed is what
   * makes a declaration readable ({@code "/dev/log"} stays {@code "/dev/log"}); it is also what
   * would let a generated id through, and one interpolated id moves the story's {@code networkHash}
   * on every run — a failure whose only symptom is a hash that never settles, diagnosable by
   * diffing sidecars and by nothing else. The check turns that into a message at the call site.
   */
  public Network declare(String kind, String from, String to, String label) {
    NetworkEdge edge = new NetworkEdge(kind, from, to, label);
    requireTemplateShaped("kind", edge.kind());
    requireTemplateShaped("from", edge.from());
    requireTemplateShaped("to", edge.to());
    requireTemplateShaped("label", edge.label());
    declared.add(edge);
    return this;
  }

  /** Refuse a declared field the default scrubber would have rewritten, and say what into. */
  private static void requireTemplateShaped(String field, String value) {
    String scrubbed = Labels.scrub(value);
    if (!scrubbed.equals(value)) {
      throw new IllegalArgumentException(
          "declared edge labels must be template-shaped; scrub would rewrite "
              + field
              + " \""
              + value
              + "\" to \""
              + scrubbed
              + "\"");
    }
  }

  /** Name the narrative initiator for subsequent captured incoming traffic. */
  public Network actor(String name) {
    NetworkCapture.actor(name);
    return this;
  }

  // --- consumed by the extension to build the report -----------------------------------------

  /**
   * Drain the capture registry, merge in the declared edges, mask every field through {@code
   * masker}, dedupe on the full {@code (kind, from, to, label)} quadruple (an edge that is both
   * observed and declared counts as observed — evidence beats declaration), and return the set
   * sorted canonically. The order and the dedup are what make {@code networkHash} stable across
   * retries and nondeterministic arrival.
   */
  List<UserflowReport.NetworkEdge> emit(UnaryOperator<String> masker) {
    Map<String, UserflowReport.NetworkEdge> deduped = new LinkedHashMap<>();
    for (NetworkEdge edge : NetworkCapture.drain()) {
      UserflowReport.NetworkEdge masked = masked(edge, masker, null);
      deduped.put(key(masked), masked);
    }
    for (NetworkEdge edge : declared) {
      UserflowReport.NetworkEdge masked = masked(edge, masker, true);
      deduped.putIfAbsent(key(masked), masked);
    }
    return deduped.values().stream()
        .sorted(
            Comparator.comparing(UserflowReport.NetworkEdge::kind)
                .thenComparing(UserflowReport.NetworkEdge::from)
                .thenComparing(UserflowReport.NetworkEdge::to)
                .thenComparing(UserflowReport.NetworkEdge::label))
        .toList();
  }

  private static UserflowReport.NetworkEdge masked(
      NetworkEdge edge, UnaryOperator<String> masker, Boolean declared) {
    return new UserflowReport.NetworkEdge(
        masker.apply(edge.kind()),
        masker.apply(edge.from()),
        masker.apply(edge.to()),
        masker.apply(edge.label()),
        declared);
  }

  private static String key(UserflowReport.NetworkEdge edge) {
    return edge.kind() + "\t" + edge.from() + "\t" + edge.to() + "\t" + edge.label();
  }
}
