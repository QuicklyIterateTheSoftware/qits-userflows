package eu.wohlben.qits.userflows;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.restassured.RestAssured;
import io.restassured.filter.Filter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a unit test can honestly say about the shipped tap: that this class <b>links</b> against the
 * optional RestAssured dependency, and that its install guard holds. Everything the filter does
 * afterwards needs a request going through RestAssured to a real endpoint, which is a service
 * story's job in a service repository — a harness story faking one here would prove the fake.
 *
 * <p>The guard is the half worth pinning: {@link RestAssured#filters} <i>appends</i>, so a lost
 * guard draws every edge twice, and doubled edges dedupe away in the sidecar (same quadruple) while
 * an {@code assertEdgeCount} somewhere else in the platform starts failing for no visible reason.
 */
class NetworkTapsTest {

  @Test
  void aServiceIsTappedOnceHoweverOftenItIsInstalled() {
    List<Filter> before = List.copyOf(RestAssured.filters());
    try {
      NetworkTaps.restAssured("a-tapped-service");
      NetworkTaps.restAssured("a-tapped-service");
      assertEquals(
          before.size() + 1,
          RestAssured.filters().size(),
          () -> "a second install must add nothing: " + RestAssured.filters());

      // A different service is a different tap — the guard is keyed by service, not by class.
      NetworkTaps.restAssured("another-tapped-service", path -> path.startsWith("/internal/"));
      assertEquals(
          before.size() + 2,
          RestAssured.filters().size(),
          () -> "a second service must get its own tap: " + RestAssured.filters());
    } finally {
      // RestAssured's filter list is JVM-global, exactly as the tap's javadoc says. Nothing else in
      // this suite drives RestAssured, but leaving a filter installed for the rest of the fork
      // would be this test deciding what later stories observe.
      RestAssured.reset();
    }
  }
}
