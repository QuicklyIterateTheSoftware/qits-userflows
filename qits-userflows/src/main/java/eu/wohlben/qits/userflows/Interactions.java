package eu.wohlben.qits.userflows;

/**
 * The narrative facade of a browserless {@link UserStory} — the counterpart to {@link Flow} for
 * stories (or parts of stories) with no browser in them. A story method that declares an {@code
 * Interactions} parameter but no {@link Flow} parameter is a <b>browserless</b> story: no Chromium
 * is launched, no video or screenshots are produced.
 *
 * <p>It records narrative {@link #note}s only. Service-to-service traffic is no longer narrated
 * here: it is <i>observed</i> — taps and mock recordings feed {@link NetworkCapture}, the
 * extension drains them at story end, and the report renders the resulting edge set as the
 * story's network diagram. The one deliberate exception, an edge no tap can see, is declared via
 * {@link Network#declare}. An absence claim ("…and nothing was pushed") is an assertion the story
 * proves and then {@link #note}s — it is not an edge.
 *
 * <p>Note lines are author-written <b>static</b> strings and enter the fingerprint / definition
 * hash. Keep them template-shaped — never ports, timestamps or ids — or the hash stops being
 * stable across runs.
 *
 * <p>Instances are created by {@link UserStoryExtension}; stories only ever receive one as a
 * method parameter. A mixed story may take both {@code (Flow flow, Interactions interactions)} —
 * they share one step log, so browser steps and notes interleave in call order.
 */
public final class Interactions {

  private final StepRecorder recorder;

  Interactions(StepRecorder recorder) {
    this.recorder = recorder;
  }

  /** A narrative step (e.g. {@code "the service starts against a dev IdP"}). */
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
}
