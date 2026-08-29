package eu.wohlben.qits.userflows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A tiny local HTTP server for no-app <b>harness stories</b> that exercise the service-story side
 * of the framework — the {@link HarnessResources} counterpart for a captured network: where a
 * browser harness story drives a bundled page, a service harness story calls this server and then
 * proves the call was seen on both ends via {@link #served()}. Generic on purpose (any path answers
 * 200 {@code pong}), so it belongs in the framework rather than in a story.
 *
 * <p>Its request log is also a <b>cumulative recording</b> in {@link NetworkCapture}'s sense: a
 * story registers {@link #servedRequests()} as a {@linkplain NetworkCapture#source source} once,
 * and the per-source cursor attributes each answered request to whichever story was running when it
 * arrived. That is the same shape a real mock or access log has, which is why the harness proves
 * the passive path rather than a bespoke one.
 */
public final class HarnessHttpServer implements AutoCloseable {

  private final HttpServer server;
  private final List<String> served = new CopyOnWriteArrayList<>();
  private final List<String> servedRequests = new CopyOnWriteArrayList<>();

  public HarnessHttpServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to start harness http server", e);
    }
    server.createContext(
        "/",
        exchange -> {
          served.add(exchange.getRequestURI().getPath());
          servedRequests.add(
              exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
          byte[] body = "pong".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
  }

  /** The server's base URL, e.g. {@code http://127.0.0.1:49213}. */
  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** Every request path this server answered, in arrival order — the story's far-end proof. */
  public List<String> served() {
    return List.copyOf(served);
  }

  /**
   * The same recording shaped as edge labels — {@code "GET /ping"}, method and path, in arrival
   * order. This is what a story hands to {@link NetworkCapture#source}: cumulative (never reset),
   * template-shaped, and with no port or host in it, so the drained label is stable across runs.
   */
  public List<String> servedRequests() {
    return List.copyOf(servedRequests);
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
