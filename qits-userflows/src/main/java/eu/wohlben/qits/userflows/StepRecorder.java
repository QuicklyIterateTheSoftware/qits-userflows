package eu.wohlben.qits.userflows;

import eu.wohlben.qits.userflows.report.Hashing;
import eu.wohlben.qits.userflows.report.UserflowReport;
import java.util.ArrayList;
import java.util.List;

/**
 * The single ordered step log of a story, shared by every recording facade ({@link Flow},
 * {@link Interactions}) the story received. One recorder per story is what keeps a mixed
 * browser-and-service story a <i>single</i> narrative: steps interleave in call order and hash
 * into one {@link #definitionHash()}.
 *
 * <p>Keeps the two parallel logs documented on {@link Flow}: the <b>display</b> lines that become
 * {@code steps[]}, and the <b>fingerprint</b> (no dynamic values, no failure line) hashed into the
 * deterministic definition hash. Step ids are labels, not part of that hash.
 */
final class StepRecorder {

  private final List<UserflowReport.Step> steps = new ArrayList<>();
  private final List<String> fingerprint = new ArrayList<>();

  List<UserflowReport.Step> steps() {
    return steps;
  }

  /** Append a step; returns the index it occupies (what a pending screenshot/interaction keys). */
  int record(String displayLine, String fingerprintLine) {
    int index = steps.size();
    steps.add(new UserflowReport.Step(stepId(index), displayLine));
    fingerprint.add(fingerprintLine);
    return index;
  }

  /** Rename the step just recorded — the shared implementation behind {@code .as(id)}. */
  void as(String id) {
    if (steps.isEmpty()) {
      throw new IllegalStateException("as() called before any step was recorded");
    }
    if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
      throw new IllegalArgumentException("step id must match [A-Za-z0-9][A-Za-z0-9._-]*: " + id);
    }
    // step-<n> is reserved for auto-assignment; allowing it would let a rename collide with the id
    // a LATER step will auto-receive (the uniqueness scan below can't see future steps).
    if (id.matches("step-\\d+")) {
      throw new IllegalArgumentException(
          "step id 'step-<n>' is reserved for auto-assignment: " + id);
    }
    int last = steps.size() - 1;
    for (int i = 0; i < steps.size(); i++) {
      if (i != last && steps.get(i).id().equals(id)) {
        throw new IllegalArgumentException("duplicate step id: " + id);
      }
    }
    steps.set(last, new UserflowReport.Step(id, steps.get(last).line()));
  }

  /** Append the terminal failure as a final display step (never part of the fingerprint/hash). */
  void recordFailure(String message) {
    steps.add(new UserflowReport.Step(stepId(steps.size()), "FAILED: " + message));
  }

  String definitionHash() {
    return Hashing.definitionHash(fingerprint);
  }

  /** The default id for the step at {@code index}, e.g. {@code "step-05"}. */
  private static String stepId(int index) {
    return String.format("step-%02d", index);
  }
}
