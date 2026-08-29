package eu.wohlben.qits.userflows.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * SHA-256 helpers producing the {@code sha256:<hex>} form used throughout the report — the
 * definition hash (over the step lines) and each screenshot's content hash. The prefixed form is
 * what the future artifacts uploader stamps as {@code qits.userflow.hash} / {@code qits.diff.hash}.
 */
public final class Hashing {

  private Hashing() {}

  /**
   * {@code sha256:<hex>} over the joined step lines — the story's deterministic definition hash.
   */
  public static String definitionHash(List<String> steps) {
    return sha256(String.join("\n", steps).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * {@code sha256:<hex>} over the canonically sorted, deduplicated network edge set. Each edge
   * contributes its {@code (kind, from, to, label)} quadruple joined by tabs; edges join by
   * newlines. The {@code declared} flag is deliberately excluded — the hash states <i>which
   * dependencies exist</i>, and provenance changing (a declared edge becoming observable) should
   * not read as the network changing. Callers pass the list exactly as the sidecar carries it
   * (already sorted and deduplicated), which is what lets an assertion recompute and compare.
   */
  public static String networkHash(List<UserflowReport.NetworkEdge> edges) {
    StringBuilder canonical = new StringBuilder();
    for (UserflowReport.NetworkEdge edge : edges) {
      if (canonical.length() > 0) {
        canonical.append('\n');
      }
      canonical
          .append(edge.kind())
          .append('\t')
          .append(edge.from())
          .append('\t')
          .append(edge.to())
          .append('\t')
          .append(edge.label());
    }
    return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  /** {@code sha256:<hex>} over raw bytes — a screenshot's content hash. */
  public static String sha256(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e); // never on a standard JRE
    }
  }
}
