package eu.wohlben.qits.userflows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The tokenizer is the riskiest code in the command facade — it alone decides what the child
 * process receives as arguments, and a mistake here is a <i>different command</i> running quietly
 * rather than an error. It is also pure, so unlike the rest of the framework it is worth testing
 * directly instead of only through a story.
 */
class CommandLineTest {

  @Test
  void splitsOnWhitespaceAndStripsQuotes() {
    assertEquals(List.of("git", "status"), CommandLine.argv("git status"));
    assertEquals(
        List.of("git", "commit", "-m", "first cut"),
        CommandLine.argv("git commit -m 'first cut'"));
    assertEquals(
        List.of("git", "commit", "-m", "it's fine"),
        CommandLine.argv("git commit -m \"it's fine\""));
    // adjacent quoted and unquoted runs are ONE token, exactly like a shell
    assertEquals(List.of("--msg=a b"), CommandLine.argv("--msg='a b'"));
    // a run of whitespace is one separator, and leading/trailing whitespace makes no empty token
    assertEquals(List.of("a", "b"), CommandLine.argv("  a \t\n b  "));
    // backslash is an ordinary character: no escape processing to mangle a path or a regex
    assertEquals(List.of("grep", "a\\.b"), CommandLine.argv("grep a\\.b"));
  }

  @Test
  void aSubstitutedValueIsAlwaysExactlyOneArgument() {
    // The template is tokenized BEFORE substitution, so nothing in a value can split it — this is
    // the property that makes injection through a value impossible.
    assertEquals(
        List.of("cat", "/tmp/my report.txt"), CommandLine.argv("cat {}", "/tmp/my report.txt"));
    assertEquals(
        List.of("echo", "a; rm -rf /"), CommandLine.argv("echo {}", "a; rm -rf /"));
    assertEquals(List.of("echo", "line1\nline2"), CommandLine.argv("echo {}", "line1\nline2"));
    assertEquals(List.of("echo", "has \"quotes\""), CommandLine.argv("echo {}", "has \"quotes\""));
    // an empty value still occupies its argument slot rather than vanishing
    assertEquals(List.of("echo", ""), CommandLine.argv("echo {}", ""));
    // and an explicitly empty token survives even with no placeholder
    assertEquals(List.of("echo", ""), CommandLine.argv("echo ''"));
  }

  @Test
  void placeholdersFillWithinTheirOwnToken() {
    assertEquals(
        List.of("npm", "install", "--registry=https://r/"),
        CommandLine.argv("npm install --registry={}", "https://r/"));
    assertEquals(
        List.of("cp", "a.txt", "b.txt"), CommandLine.argv("cp {}.txt {}.txt", "a", "b"));
    // a placeholder inside quotes is still a placeholder — the quotes are gone by then
    assertEquals(List.of("echo", "hi there"), CommandLine.argv("echo '{}'", "hi there"));
    // a value that itself contains {} is not re-scanned
    assertEquals(List.of("echo", "{}"), CommandLine.argv("echo {}", "{}"));
  }

  @Test
  void aMismatchOrAnUnterminatedQuoteFailsBeforeAnythingRuns() {
    IllegalArgumentException tooFew =
        assertThrows(IllegalArgumentException.class, () -> CommandLine.argv("cp {} {}", "a"));
    assertTrue(tooFew.getMessage().contains("2 {} placeholder(s) but 1"), tooFew.getMessage());

    assertThrows(IllegalArgumentException.class, () -> CommandLine.argv("cp {}", "a", "b"));
    assertThrows(IllegalArgumentException.class, () -> CommandLine.argv("echo hi", "a"));

    IllegalArgumentException unterminated =
        assertThrows(IllegalArgumentException.class, () -> CommandLine.argv("echo 'oops"));
    assertTrue(unterminated.getMessage().contains("unterminated"), unterminated.getMessage());
    assertThrows(IllegalArgumentException.class, () -> CommandLine.argv("echo \"oops"));

    assertThrows(IllegalArgumentException.class, () -> CommandLine.argv("   "));
  }

  @Test
  void displayQuotesOnlyWhatItMustAndNeverBreaksTheLine() {
    assertEquals("git status", CommandLine.display(List.of("git", "status")));
    assertEquals("cat \"my report.txt\"", CommandLine.display(List.of("cat", "my report.txt")));
    assertEquals("echo \"\"", CommandLine.display(List.of("echo", "")));
    // a newline in a value must not split the one-step-one-line invariant
    assertEquals("echo \"a\\nb\"", CommandLine.display(List.of("echo", "a\nb")));
    assertEquals("echo \"say \\\"hi\\\"\"", CommandLine.display(List.of("echo", "say \"hi\"")));
  }

  @Test
  void fillIsTheWholeBlobCounterpart() {
    assertEquals("token = abc\n", CommandLine.fill("token = {}\n", "abc"));
    assertEquals("nothing to fill", CommandLine.fill("nothing to fill"));
    assertThrows(IllegalArgumentException.class, () -> CommandLine.fill("a {} b"));
  }
}
