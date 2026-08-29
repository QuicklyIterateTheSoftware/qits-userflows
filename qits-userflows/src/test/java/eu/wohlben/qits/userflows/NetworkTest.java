package eu.wohlben.qits.userflows;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The declared-edge guard. A declaration is not scrubbed — that is what keeps an author's literal
 * readable — so the one thing the framework can do about a generated id inside one is refuse it at
 * the call site. Without the refusal the symptom is a {@code networkHash} that moves on every run
 * and is diagnosable only by diffing two sidecars.
 *
 * <p>Only {@code declare} is exercised here: {@code emit} drains the JVM-global capture registry,
 * which a unit test must never do.
 */
class NetworkTest {

  @Test
  void aTemplateShapedDeclarationIsAccepted() {
    assertDoesNotThrow(
        () ->
            new Network()
                .declare("process", "harness", "a spawned engine", "spawn engine.sh")
                .declare("jdbc", "qits-app", "the store", "reads /var/lib/qits/state")
                .declare("http", "greeting-page", "greeting-backend", "POST /greetings"));
  }

  @Test
  void aGeneratedIdInADeclaredFieldIsRefusedByName() {
    IllegalArgumentException label =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new Network()
                    .declare(
                        "http",
                        "qits-app",
                        "qits-projects",
                        "GET /projects/3f2a1b4c-5d6e-7f80-9a1b-2c3d4e5f6071/tasks"));
    // The message names the field and the shape the author should have written instead.
    assertTrue(label.getMessage().contains("label"), label.getMessage());
    assertTrue(label.getMessage().contains("GET /projects/{id}/tasks"), label.getMessage());

    // Every field is checked, not only the label — a node name carrying an id moves the hash too.
    IllegalArgumentException node =
        assertThrows(
            IllegalArgumentException.class,
            () -> new Network().declare("process", "harness", "worker/7", "spawn worker"));
    assertTrue(node.getMessage().contains("to"), node.getMessage());
    assertTrue(node.getMessage().contains("worker/{id}"), node.getMessage());
  }
}
