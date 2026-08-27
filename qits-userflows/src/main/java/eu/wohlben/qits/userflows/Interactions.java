package eu.wohlben.qits.userflows;

import eu.wohlben.qits.userflows.report.UserflowReport;
import java.util.ArrayList;
import java.util.List;

/**
 * The service-level recording facade of a {@link UserStory} — the counterpart to {@link Flow} for
 * stories (or parts of stories) with no browser in them. It records what services <i>did to each
 * other</i>: each {@link #happened} call is both a step in the story's log and a structured
 * interaction that the report renders as a sequence diagram.
 *
 * <p>A story method that declares an {@code Interactions} parameter but no {@link Flow} parameter
 * is a <b>browserless</b> story: no Chromium is launched, no video or screenshots are produced,
 * and the report consists of the steps, the interactions and their diagram.
 *
 * <p>The facade records; it never asserts. A story first proves the interaction on both ends
 * (e.g. the mocked far side recorded serving the request, the near side visibly acted on the
 * response), then records the fact — past tense on purpose:
 *
 * <pre>{@code
 * assertTrue(mockIdp.recordedRequests().stream().anyMatch(r -> r.path().equals("/idp/jwks")));
 * interactions.happened("qits-githost", "qits-platform-idp", "GET /idp/jwks").as("jwks-fetched");
 * }</pre>
 *
 * <p>All three arguments are author-written <b>static</b> strings and enter the fingerprint /
 * definition hash (an interaction describes what the story does, exactly like a {@code click}).
 * Keep descriptions template-shaped — {@code "GET /idp/jwks"}, never ports, timestamps or ids —
 * or the hash stops being stable across runs.
 *
 * <p>Instances are created by {@link UserStoryExtension}; stories only ever receive one as a
 * method parameter. A mixed story may take both {@code (Flow flow, Interactions interactions)} —
 * they share one step log, so browser steps and interactions interleave in call order.
 */
public final class Interactions {

  private final StepRecorder recorder;
  // Interactions are resolved against their owning step at emit time (the PendingShot pattern), so
  // an author's .as(id) rename settles the step id before the by-id link is derived from it.
  private final List<PendingInteraction> pending = new ArrayList<>();

  Interactions(StepRecorder recorder) {
    this.recorder = recorder;
  }

  /**
   * Record an observed service-to-service interaction: {@code from} called {@code to}, described
   * by the static {@code description} (e.g. {@code "GET /idp/jwks"}). Renders in the report's
   * sequence diagram as {@code from ->> to: description}.
   */
  public Interactions happened(String from, String to, String description) {
    String line = "interaction " + from + " -> " + to + ": " + description;
    int stepIndex = recorder.record(line, line);
    pending.add(new PendingInteraction(stepIndex, from, to, description));
    return this;
  }

  /** A narrative step with no interaction (e.g. {@code "the service starts against a dev IdP"}). */
  public Interactions note(String line) {
    recorder.record(line, "note " + line);
    return this;
  }

  /**
   * Give the step just recorded an explicit id instead of the machine-assigned {@code step-NN} —
   * exactly {@link Flow#as}: unique within the story, {@code [A-Za-z0-9] then [A-Za-z0-9._-]*}.
   */
  public Interactions as(String id) {
    recorder.as(id);
    return this;
  }

  // --- consumed by the extension to build the report -----------------------------------------

  /**
   * Resolve each interaction's owning step to its final (possibly {@link #as}-renamed) id and
   * return the records for the report.
   */
  List<UserflowReport.Interaction> emit() {
    List<UserflowReport.Interaction> emitted = new ArrayList<>();
    for (PendingInteraction interaction : pending) {
      emitted.add(
          new UserflowReport.Interaction(
              interaction.from,
              interaction.to,
              interaction.description,
              recorder.steps().get(interaction.stepIndex).id()));
    }
    return emitted;
  }

  /** An interaction awaiting its owning step's final id at emit time. */
  private record PendingInteraction(int stepIndex, String from, String to, String description) {}
}
