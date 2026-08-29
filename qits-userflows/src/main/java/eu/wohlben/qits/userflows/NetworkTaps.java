package eu.wohlben.qits.userflows;

import io.restassured.RestAssured;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The taps the framework ships, so a story class never writes one. Today that is one: the
 * <b>RestAssured</b> tap, which every service story driving its own service through RestAssured
 * needs and which four repositories had hand-copied verbatim before it lived here.
 *
 * <p>What the tap observes is the <b>incoming</b> half of a story's diagram — every request the
 * story makes becomes {@code <actor> -> <service>}, labelled with the method, the {@link
 * Labels#scrub scrubbed} path and the status the service actually answered. The outgoing half (what
 * the service called) is somebody else's recording, registered as a {@link NetworkCapture#source}.
 *
 * <p><b>RestAssured must be on the consumer's classpath.</b> The framework declares it {@code
 * optional}, so it is not transitive: a story module that uses no RestAssured resolves this jar
 * exactly as it did before the tap existed, and only a module that <i>calls</i> this class without
 * the library fails — at link time, on the call, naming {@code io.restassured}.
 *
 * <p><b>Only a story class may install a tap.</b> {@link RestAssured#filters} is JVM-global and
 * appends rather than replaces, so an installation from anywhere else has no story border around
 * it: traffic from a test that ran earlier in the same failsafe fork was never observed, and
 * traffic from one that runs later drains into no story at all. Installing from the owning story
 * class's {@code @BeforeAll} is what bounds the tap to the stories it belongs to — and a story
 * class that installs one must pin at least one edge ({@code assertEdge} / {@code
 * assertEdgeCount}), or a {@code @BeforeAll} dropped in a later edit silently empties every
 * diagram in the class.
 *
 * <p><b>The actor is read here, on the story thread.</b> {@link NetworkCapture#actor()} is the
 * sticky narrative initiator the story set before acting, and a RestAssured filter runs
 * synchronously on the thread that made the call — which is the one place the rule in {@link
 * NetworkCapture} allows it to be read. A tap that deferred the read to a callback thread would
 * inherit whatever actor is current when the response lands, which is a different story's.
 */
public final class NetworkTaps {

  /**
   * The default skip: any path carrying a {@code /q/} segment. Quarkus serves health, metrics and
   * openapi under {@code quarkus.http.non-application-root-path}, a story readily calls readiness
   * to say the service is up, and a diagram in which every node hangs off {@code /q/health/ready}
   * documents nothing. It matches the <i>segment</i> rather than a prefix because a service's
   * application root ({@code /artifacts/q}, {@code /projects/q}) sits in front of it.
   */
  private static final Predicate<String> QUARKUS_PROBES = path -> path.contains("/q/");

  /**
   * Which services already carry a tap. {@link RestAssured#filters} appends rather than replaces,
   * so without this guard a second install would draw every edge twice.
   */
  private static final Set<String> INSTALLED = new HashSet<>();

  private NetworkTaps() {}

  /**
   * Install the RestAssured tap for {@code service} — the {@code to} of every edge it observes, as
   * the diagram names the service — skipping every path that carries a {@code /q/} segment.
   *
   * <p><b>Verify that default against your own {@code quarkus.http.non-application-root-path}.</b>
   * A service that moved its probes elsewhere, or that serves a real route containing {@code /q/},
   * needs {@link #restAssured(String, Predicate)} instead — the shipped predicate is the platform's
   * convention, not a fact about your configuration.
   */
  public static void restAssured(String service) {
    restAssured(service, QUARKUS_PROBES);
  }

  /**
   * The tap with the skip predicate spelled out: {@code ignoredPath} is tested against the request
   * path alone (no query, no host), and a path it <b>matches</b> records no edge at all.
   *
   * <p>Idempotent per service: a second call for a service already tapped installs nothing, so
   * every story class in a fork may install from its own {@code @BeforeAll} without the diagram
   * doubling every edge. First install wins — a later call passing a different predicate is
   * ignored, so two story classes tapping one service must agree on what a probe is.
   */
  public static synchronized void restAssured(String service, Predicate<String> ignoredPath) {
    if (INSTALLED.add(service)) {
      RestAssured.filters(new StoryNetworkFilter(service, ignoredPath));
    }
  }

  /** Observes after {@code ctx.next(…)}, so the status on the label is the answered one. */
  private record StoryNetworkFilter(String service, Predicate<String> ignoredPath)
      implements Filter {

    @Override
    public Response filter(
        FilterableRequestSpecification requestSpec,
        FilterableResponseSpecification responseSpec,
        FilterContext ctx) {
      Response response = ctx.next(requestSpec, responseSpec);
      String path = URI.create(requestSpec.getURI()).getPath();
      if (!ignoredPath.test(path)) {
        NetworkCapture.observe(
            NetworkEdge.HTTP,
            NetworkCapture.actor(),
            service,
            requestSpec.getMethod() + " " + Labels.scrub(path) + " -> " + response.statusCode());
      }
      return response;
    }
  }
}
