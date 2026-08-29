package eu.wohlben.qits.userflows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The single place that decides what a command's bytes <i>look like</i> in a report: capturing,
 * sanitizing, capping, excerpting and masking. Every text that reaches a reader through {@link
 * Commands} — a transcript file, the inline excerpt in the sidecar, a written-file dump, a step
 * display line — goes through here, so the four renderings can never disagree about how a control
 * character, an over-long stream or a redacted secret is shown.
 *
 * <p><b>Capping is head + rolling tail, never a prefix.</b> A build log's interesting part is
 * usually its <i>end</i> (the error) while its identity is its <i>beginning</i> (the command
 * banner), so a plain {@code head -c} cap throws away exactly what a reader came for. The {@link
 * Sink} keeps the first half and the last half of the cap with an explicit {@code … N bytes omitted
 * …} marker between them — an honest report never silently shortens output.
 *
 * <p><b>Draining continues past the cap.</b> The cap bounds what we <i>keep</i>, not what we read:
 * a process whose stdout pipe fills up blocks forever, so the drain thread must keep consuming even
 * when nothing more will be retained. That is why {@link Sink#write} is total and cheap rather than
 * a short-circuit.
 *
 * <p>Sanitizing normalizes a terminal stream into text a document can hold: ANSI CSI sequences are
 * stripped (colour codes are noise in a transcript), {@code \r\n} becomes {@code \n}, and a bare
 * {@code \r} — a progress bar redrawing one line in place — collapses to the line that survived the
 * redraw, so a {@code curl} download does not paint two hundred near-identical lines into the
 * report. Bytes decode as UTF-8 with replacement: a truncation that lands mid-codepoint must
 * produce a replacement character, never an exception on the reporting path.
 *
 * <p>No timing is captured anywhere. A wall-clock duration would make the canonical sidecar differ
 * on every run — the same reason the report's video record carries no length.
 */
final class CommandOutput {

  /** What a {@linkplain Commands#redact redacted} secret is replaced with, everywhere. */
  static final String MASK = "•••";

  /** Byte cap for one command's captured output; see {@link #maxOutputBytes()}. */
  static final String MAX_OUTPUT_BYTES_PROPERTY = "qits.userflows.command.max-output-bytes";

  static final int DEFAULT_MAX_OUTPUT_BYTES = 1024 * 1024;

  /** The inline excerpt kept in the sidecar (and rendered by both human renderers). */
  static final int EXCERPT_HEAD_CHARS = 2000;

  static final int EXCERPT_TAIL_CHARS = 2000;

  /**
   * ANSI CSI: {@code ESC [}, parameter bytes, intermediate bytes, one final byte. Covers colour,
   * cursor movement and erase sequences — everything a well-behaved CLI emits when it thinks it is
   * talking to a terminal.
   */
  private static final Pattern ANSI_CSI = Pattern.compile("\\x1b\\[[0-?]*[ -/]*[@-~]");

  private CommandOutput() {}

  /**
   * The configured capture cap in bytes — {@value #DEFAULT_MAX_OUTPUT_BYTES} unless {@code
   * qits.userflows.command.max-output-bytes} overrides it. Read per command rather than cached, so
   * a story (or a self-test) can lower it for one class without restarting the JVM.
   */
  static int maxOutputBytes() {
    String raw = System.getProperty(MAX_OUTPUT_BYTES_PROPERTY);
    if (raw == null || raw.isBlank()) {
      return DEFAULT_MAX_OUTPUT_BYTES;
    }
    try {
      return Math.max(2, Integer.parseInt(raw.strip()));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          MAX_OUTPUT_BYTES_PROPERTY + " must be a byte count: " + raw, e);
    }
  }

  /** Strip ANSI CSI sequences, normalize newlines, and collapse bare-{@code \r} redraws. */
  static String sanitize(String raw) {
    String text = ANSI_CSI.matcher(raw).replaceAll("");
    text = text.replace("\r\n", "\n");
    if (text.indexOf('\r') < 0) {
      return text;
    }
    StringBuilder out = new StringBuilder(text.length());
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) {
        out.append('\n');
      }
      int lastReturn = lines[i].lastIndexOf('\r');
      out.append(lastReturn < 0 ? lines[i] : lines[i].substring(lastReturn + 1));
    }
    return out.toString();
  }

  /**
   * Replace every occurrence of every registered secret with {@link #MASK}. Applied at <i>emit</i>
   * time to everything the report will carry, so a secret that only appears in output produced
   * before {@code redact()} was called is still masked.
   */
  static String mask(String text, List<String> secrets) {
    if (text == null || text.isEmpty() || secrets.isEmpty()) {
      return text;
    }
    String masked = text;
    for (String secret : secrets) {
      if (secret != null && !secret.isEmpty()) {
        masked = masked.replace(secret, MASK);
      }
    }
    return masked;
  }

  /**
   * The inline excerpt for the sidecar: the first {@value #EXCERPT_HEAD_CHARS} and last {@value
   * #EXCERPT_TAIL_CHARS} characters with an omission marker between them. Same head+tail discipline
   * as the byte cap, one level down — the sidecar stays diff-friendly while the full text remains
   * one click away in the transcript artifact beside it.
   */
  static String excerpt(String text) {
    int limit = EXCERPT_HEAD_CHARS + EXCERPT_TAIL_CHARS;
    if (text == null || text.length() <= limit) {
      return text;
    }
    return text.substring(0, EXCERPT_HEAD_CHARS)
        + "\n"
        + omission(text.length() - limit, "characters")
        + "\n"
        + text.substring(text.length() - EXCERPT_TAIL_CHARS);
  }

  /** The one omission marker spelling, so a reader (and a test) recognizes it anywhere. */
  static String omission(long amount, String unit) {
    return "… " + amount + " " + unit + " omitted …";
  }

  /**
   * The bounded byte sink a command's merged stdout+stderr drains into: a head buffer that fills
   * once and a ring buffer that keeps rolling, together holding at most the cap. Synchronized
   * because the drain thread writes while the story thread reads the result after {@code waitFor}
   * (or after a forcible destroy, where the partial transcript is exactly what we want).
   */
  static final class Sink {

    private final ByteArrayOutputStream head = new ByteArrayOutputStream();
    private final int headLimit;
    private final byte[] tail;
    private int tailStart;
    private int tailHeld;
    private long total;

    Sink(int maxBytes) {
      int cap = Math.max(2, maxBytes);
      this.headLimit = cap / 2;
      this.tail = new byte[cap - headLimit];
    }

    /** Consume {@code length} bytes: fill the head first, then roll them through the tail. */
    synchronized void write(byte[] buffer, int length) {
      total += length;
      int index = 0;
      int toHead = Math.min(length, headLimit - head.size());
      if (toHead > 0) {
        head.write(buffer, 0, toHead);
        index = toHead;
      }
      for (; index < length; index++) {
        tail[(tailStart + tailHeld) % tail.length] = buffer[index];
        if (tailHeld < tail.length) {
          tailHeld++;
        } else {
          tailStart = (tailStart + 1) % tail.length;
        }
      }
    }

    /** How many bytes the cap dropped; {@code 0} when everything the process wrote is held. */
    synchronized long omitted() {
      return total - head.size() - tailHeld;
    }

    synchronized boolean truncated() {
      return omitted() > 0;
    }

    /**
     * The sanitized text: head, the omission marker when anything was dropped, then tail. The two
     * halves are sanitized separately so the marker itself can never be mangled by a redraw
     * collapse spanning the seam.
     */
    synchronized String text() {
      String headText = sanitize(new String(head.toByteArray(), StandardCharsets.UTF_8));
      byte[] tailBytes = new byte[tailHeld];
      for (int i = 0; i < tailHeld; i++) {
        tailBytes[i] = tail[(tailStart + i) % tail.length];
      }
      String tailText = sanitize(new String(tailBytes, StandardCharsets.UTF_8));
      long omitted = omitted();
      if (omitted <= 0) {
        return headText + tailText;
      }
      return headText + "\n" + omission(omitted, "bytes") + "\n" + tailText;
    }
  }
}
