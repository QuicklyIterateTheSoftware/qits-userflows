package eu.wohlben.qits.userflows.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sidecar's <b>shape</b>, which is a contract rather than an implementation detail: field order
 * is fixed so a reviewer diffs a story and not a serializer, an absent component is absent (never
 * {@code null}, never {@code []}) so growing the model cannot rewrite an unrelated story's bytes,
 * and {@code declared} is present only when it is {@code true} — an observed edge carries no flag
 * at all, which is what keeps a claim visibly different from evidence on the wire.
 */
class JsonReportWriterTest {

  @TempDir Path reportDir;

  private static UserflowReport report(List<UserflowReport.NetworkEdge> network) {
    return new UserflowReport(
        "Create a greeting",
        "create-a-greeting",
        null,
        null,
        List.of(new UserflowReport.Step("step-00", "navigate /")),
        "sha256:0",
        network,
        network == null ? null : Hashing.networkHash(network),
        null,
        null,
        List.of(),
        null,
        UserflowReport.PASSED);
  }

  @Test
  void aStoryThatCapturedNothingCarriesNeitherNetworkField() throws IOException {
    new JsonReportWriter().render(report(null), reportDir);

    String json = Files.readString(reportDir.resolve(JsonReportWriter.FILE_NAME));
    assertFalse(json.contains("\"network\""), json);
    assertFalse(json.contains("\"networkHash\""), json);
  }

  @Test
  void edgesKeepTheContractOrderAndObservedOnesOmitTheFlag() throws IOException {
    new JsonReportWriter()
        .render(
            report(
                List.of(
                    new UserflowReport.NetworkEdge(
                        "http", "a caller", "qits-app", "GET /health", null),
                    new UserflowReport.NetworkEdge(
                        "process", "qits-app", "an engine", "spawn engine.sh", Boolean.TRUE))),
            reportDir);

    JsonNode root =
        new ObjectMapper().readTree(reportDir.resolve(JsonReportWriter.FILE_NAME).toFile());

    // The pair sits directly after the definition hash: the network is the story's second
    // fingerprint, and the two read together.
    List<String> fields = fieldNames(root);
    assertEquals(fields.indexOf("definitionHash") + 1, fields.indexOf("network"), fields::toString);
    assertEquals(fields.indexOf("network") + 1, fields.indexOf("networkHash"), fields::toString);

    assertEquals(List.of("kind", "from", "to", "label"), fieldNames(root.get("network").get(0)));
    assertEquals(
        List.of("kind", "from", "to", "label", "declared"),
        fieldNames(root.get("network").get(1)));
    assertTrue(root.get("network").get(1).get("declared").asBoolean(), root::toString);
    // The hash is recomputable from the emitted edges — and excludes the flag by construction.
    assertEquals(
        Hashing.networkHash(
            List.of(
                new UserflowReport.NetworkEdge("http", "a caller", "qits-app", "GET /health", null),
                new UserflowReport.NetworkEdge(
                    "process", "qits-app", "an engine", "spawn engine.sh", null))),
        root.get("networkHash").asText());
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }
}
