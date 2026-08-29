package eu.wohlben.qits.userflows;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link Commands#run} template into an <b>argv</b> — the exact list {@link ProcessBuilder}
 * executes — and back into a readable one-line rendering for the step log. There is no shell
 * anywhere in the path, so this class is the <i>whole</i> word-splitting story: whatever it decides
 * a token is, is what the child process sees as an argument.
 *
 * <p>The order of the two phases is the load-bearing rule. The <b>template is tokenized first</b>
 * (quote-aware), and only then are the {@code {}} placeholders inside each token substituted. A
 * value therefore always lands as <b>exactly one argv element</b> no matter what it contains —
 * spaces, quotes, newlines, a leading dash — because the tokenizer never sees it. Injection through
 * a value is impossible by construction, which is what lets a story pass a temp path or a
 * user-supplied string without quoting it by hand:
 *
 * <pre>{@code
 * argv("cat {}", "/tmp/my report.txt")   -> ["cat", "/tmp/my report.txt"]
 * argv("--registry={}", "https://r/")    -> ["--registry=https://r/"]   // concatenates in-token
 * argv("git commit -m 'wip'")            -> ["git", "commit", "-m", "wip"]
 * }</pre>
 *
 * <p>Quoting in the template is POSIX-shaped but deliberately minimal: {@code '…'} and {@code "…"}
 * group words and are removed, each is literal inside the other, and <b>backslash is not an escape
 * character</b> — it is an ordinary character, so a Windows-shaped path or a regex in a template
 * survives unmangled. An unterminated quote is a fail-fast authoring error, never a silently
 * different command.
 *
 * <p>Both failure modes ({@linkplain IllegalArgumentException unterminated quote}, placeholder/arg
 * count mismatch) throw <i>before</i> anything is executed or recorded: a malformed template is a
 * bug in the story, not a run to report on.
 */
final class CommandLine {

  private CommandLine() {}

  /**
   * The argv for {@code template} with its {@code {}} placeholders filled from {@code args}, in
   * order. The number of placeholders must equal {@code args.length} exactly — an off-by-one here
   * would silently run a <i>different</i> command, so it is an error rather than a best effort
   * (contrast {@link Flow#navigate(String, Object...)}, where a leftover placeholder only mars a
   * URL).
   */
  static List<String> argv(String template, Object... args) {
    if (template == null || template.isBlank()) {
      throw new IllegalArgumentException("command template must not be blank");
    }
    List<String> tokens = tokenize(template);
    if (tokens.isEmpty()) {
      throw new IllegalArgumentException("command template has no program: " + template);
    }
    int placeholders = 0;
    for (String token : tokens) {
      placeholders += countPlaceholders(token);
    }
    int given = args == null ? 0 : args.length;
    if (placeholders != given) {
      throw new IllegalArgumentException(
          "command template has "
              + placeholders
              + " {} placeholder(s) but "
              + given
              + " argument(s) were given: "
              + template);
    }
    List<String> argv = new ArrayList<>(tokens.size());
    int next = 0;
    for (String token : tokens) {
      StringBuilder element = new StringBuilder();
      int from = 0;
      int at;
      while ((at = token.indexOf("{}", from)) >= 0) {
        element.append(token, from, at).append(String.valueOf(args[next++]));
        from = at + 2;
      }
      argv.add(element.append(token.substring(from)).toString());
    }
    return List.copyOf(argv);
  }

  /**
   * Fill the {@code {}} placeholders of a whole string at once — the non-tokenized counterpart used
   * for a {@link Commands#sh} script body and a {@link Commands#file} content template, where the
   * subject is one blob rather than a word list. Same fail-fast arity rule.
   */
  static String fill(String template, Object... args) {
    if (template == null) {
      throw new IllegalArgumentException("template must not be null");
    }
    int placeholders = countPlaceholders(template);
    int given = args == null ? 0 : args.length;
    if (placeholders != given) {
      throw new IllegalArgumentException(
          "template has "
              + placeholders
              + " {} placeholder(s) but "
              + given
              + " argument(s) were given: "
              + template);
    }
    StringBuilder out = new StringBuilder();
    int next = 0;
    int from = 0;
    int at;
    while ((at = template.indexOf("{}", from)) >= 0) {
      out.append(template, from, at).append(String.valueOf(args[next++]));
      from = at + 2;
    }
    return out.append(template.substring(from)).toString();
  }

  /**
   * The one-line, human-readable rendering of an argv for the step log — the resolved command a
   * reader would retype. An element is quoted only when it needs to be (empty, or carrying
   * whitespace / a quote / a backslash), and inside those quotes control characters are escaped so
   * a value containing a newline can never break the <b>one step, one line</b> invariant the step
   * log and the markdown Steps block depend on.
   *
   * <p>This is a rendering, not a shell quoting: it is never fed back to a shell, and the argv —
   * not this string — is what ran.
   */
  static String display(List<String> argv) {
    StringBuilder out = new StringBuilder();
    for (String element : argv) {
      if (out.length() > 0) {
        out.append(' ');
      }
      out.append(quoteForDisplay(element));
    }
    return out.toString();
  }

  /**
   * Flatten {@code text} onto a single line for a step line, escaping the characters that would
   * otherwise split it (used for the {@code sh -c "…"} script body, which is routinely multi-line).
   */
  static String oneLine(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      appendEscaped(out, text.charAt(i));
    }
    return out.toString();
  }

  // --- internals -------------------------------------------------------------------------------

  private static List<String> tokenize(String template) {
    List<String> tokens = new ArrayList<>();
    StringBuilder token = new StringBuilder();
    boolean started = false;
    boolean inSingle = false;
    boolean inDouble = false;
    for (int i = 0; i < template.length(); i++) {
      char c = template.charAt(i);
      if (c == '\'' && !inDouble) {
        inSingle = !inSingle;
        started = true; // '' is an empty token, not an absent one
        continue;
      }
      if (c == '"' && !inSingle) {
        inDouble = !inDouble;
        started = true;
        continue;
      }
      if (!inSingle && !inDouble && Character.isWhitespace(c)) {
        if (started) {
          tokens.add(token.toString());
          token.setLength(0);
          started = false;
        }
        continue;
      }
      token.append(c);
      started = true;
    }
    if (inSingle || inDouble) {
      throw new IllegalArgumentException(
          "unterminated " + (inSingle ? "'" : "\"") + " quote in command template: " + template);
    }
    if (started) {
      tokens.add(token.toString());
    }
    return tokens;
  }

  private static int countPlaceholders(String text) {
    int count = 0;
    int from = 0;
    int at;
    while ((at = text.indexOf("{}", from)) >= 0) {
      count++;
      from = at + 2;
    }
    return count;
  }

  private static String quoteForDisplay(String element) {
    boolean plain = !element.isEmpty();
    for (int i = 0; plain && i < element.length(); i++) {
      char c = element.charAt(i);
      plain = c > ' ' && c != 127 && c != '"' && c != '\'' && c != '\\';
    }
    if (plain) {
      return element;
    }
    StringBuilder out = new StringBuilder(element.length() + 2).append('"');
    for (int i = 0; i < element.length(); i++) {
      appendEscaped(out, element.charAt(i));
    }
    return out.append('"').toString();
  }

  private static void appendEscaped(StringBuilder out, char c) {
    switch (c) {
      case '"' -> out.append("\\\"");
      case '\\' -> out.append("\\\\");
      case '\n' -> out.append("\\n");
      case '\r' -> out.append("\\r");
      case '\t' -> out.append("\\t");
      default -> {
        if (c < ' ' || c == 127) {
          out.append(String.format("\\u%04x", (int) c));
        } else {
          out.append(c);
        }
      }
    }
  }
}
