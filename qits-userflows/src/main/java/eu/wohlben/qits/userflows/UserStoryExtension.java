package eu.wohlben.qits.userflows;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Video;
import eu.wohlben.qits.userflows.report.HtmlReportRenderer;
import eu.wohlben.qits.userflows.report.JsonReportWriter;
import eu.wohlben.qits.userflows.report.MarkdownReportRenderer;
import eu.wohlben.qits.userflows.report.ReportRenderer;
import eu.wohlben.qits.userflows.report.SiteIndexWriter;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowPaths;
import eu.wohlben.qits.userflows.report.UserflowReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

/**
 * The lifecycle owner of a {@link UserStory}: it launches a headless Chromium with video recording,
 * injects the recording {@link Flow}, records the outcome (including failures), and emits the
 * report ({@code userflow.json} + {@code user-story.md} + screenshots + {@code recording.webm})
 * under {@code target/userstories/<slug>/}.
 *
 * <p>The browser is <b>parameter-driven</b>: Chromium (and Playwright itself) is only created when
 * the story method declares a {@link Flow} parameter. A story that takes only {@link Interactions}
 * and/or {@link Commands} (and/or {@link UserflowContext}) is browserless — no driver, no video, no
 * screenshots; its report carries steps, interactions, commands and written files.
 *
 * <p>Attached automatically via {@link UserStory @UserStory}'s {@code @ExtendWith}; stories never
 * reference it directly.
 *
 * <p>Base URL comes from {@code qits.userflows.base-url} (default {@code http://localhost:8080}); a
 * relative {@link Flow#navigate} resolves against it while absolute URLs (incl. {@code file://} for
 * the no-app harness stories) pass through.
 */
public final class UserStoryExtension
    implements ExecutionCondition,
        BeforeEachCallback,
        ParameterResolver,
        TestExecutionExceptionHandler,
        AfterEachCallback {

  public static final String BASE_URL_PROPERTY = "qits.userflows.base-url";
  public static final String DEFAULT_BASE_URL = "http://localhost:8080";
  private static final int VIDEO_WIDTH = 1280;
  private static final int VIDEO_HEIGHT = 720;

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(UserStoryExtension.class);
  private static final String STATE_KEY = "state";

  private static final List<ReportRenderer> RENDERERS =
      List.of(new JsonReportWriter(), new MarkdownReportRenderer(), new HtmlReportRenderer());

  /** JVM-wide slug → story-name registry: two <i>different</i> stories slugging alike fail fast. */
  private static final Map<String, String> EMITTED_SLUGS = new ConcurrentHashMap<>();

  /**
   * JVM-wide set of slugs whose story ran and PASSED — what {@link UserflowPrecondition} checks.
   */
  private static final Set<String> PASSED_SLUGS = ConcurrentHashMap.newKeySet();

  /**
   * The single {@link UserflowContext} shared across all stories in the run (dependency handoff).
   */
  private static final UserflowContext CONTEXT = new UserflowContext();

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    Optional<Method> method = context.getTestMethod();
    if (method.isEmpty()) {
      return ConditionEvaluationResult.enabled("not a story method");
    }
    UserflowPrecondition preconditions = method.get().getAnnotation(UserflowPrecondition.class);
    if (preconditions == null) {
      return ConditionEvaluationResult.enabled("no precondition");
    }
    // Evaluated before beforeEach, so a skipped dependent never launches Chromium.
    for (Class<?> producer : preconditions.value()) {
      String slug = storySlug(producer);
      if (!PASSED_SLUGS.contains(slug)) {
        return ConditionEvaluationResult.disabled(
            "precondition '" + producer.getSimpleName() + "' (" + slug + ") did not pass");
      }
    }
    return ConditionEvaluationResult.enabled("all preconditions passed");
  }

  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    Method method = context.getRequiredTestMethod();
    UserStory story = method.getAnnotation(UserStory.class);
    if (story == null) {
      throw new IllegalStateException(
          "UserStoryExtension is only valid on @UserStory methods: " + method);
    }
    UserStoryDescription description = method.getAnnotation(UserStoryDescription.class);
    boolean expectFailure = method.isAnnotationPresent(ExpectedFailure.class);

    String name = story.value();
    String slug = Slugs.slug(name);
    // A categorized story emits under <category-slug>/<slug>/ — the directory layout is what
    // carries the grouping to a reader. The collision registry keys on the full path, so one
    // story name may exist in two categories without their reports overwriting each other.
    String categorySlug = story.category().isBlank() ? "" : Slugs.slug(story.category());
    String category = story.category().isBlank() ? null : story.category();
    checkForCollision(categorySlug.isEmpty() ? slug : categorySlug + "/" + slug, name);
    Path reportDir = UserflowPaths.reportDir(categorySlug, slug);
    freshDirectory(reportDir);

    StepRecorder recorder = new StepRecorder();
    Interactions interactions = new Interactions(recorder);
    // Constructed for every story, browser or not — it is cheap and inert until a verb is called.
    // Its scratch directory is created lazily inside the facade, so a story that runs no command
    // leaves no work directory behind and no commands/files in its sidecar.
    Commands commands = new Commands(recorder, reportDir, categorySlug, slug);

    // The browser exists only for stories that ask for a Flow — a browserless service story never
    // even creates Playwright (no driver extraction, no browser download).
    boolean wantsBrowser =
        Stream.of(method.getParameterTypes()).anyMatch(type -> type == Flow.class);
    if (!wantsBrowser) {
      context
          .getStore(NAMESPACE)
          .put(
              STATE_KEY,
              new StoryState(
                  name,
                  slug,
                  category,
                  description == null ? null : description.value(),
                  expectFailure,
                  reportDir,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  recorder,
                  interactions,
                  commands));
      return;
    }

    Path videoDir = reportDir.resolve(".video");
    Playwright playwright = createPlaywright();
    Browser browser = null;
    try {
      // if any of these throw, we must still tear down what was already created — otherwise the
      // driver + Chromium leak (afterEach can't help: no StoryState is stored yet)
      browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
      BrowserContext browserContext =
          browser.newContext(
              new Browser.NewContextOptions()
                  .setViewportSize(VIDEO_WIDTH, VIDEO_HEIGHT)
                  .setRecordVideoDir(videoDir)
                  .setRecordVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT));
      Page page = browserContext.newPage();
      Video video = page.video();

      String baseUrl = System.getProperty(BASE_URL_PROPERTY, DEFAULT_BASE_URL);
      Flow flow = new Flow(page, reportDir, baseUrl, recorder);

      StoryState state =
          new StoryState(
              name,
              slug,
              category,
              description == null ? null : description.value(),
              expectFailure,
              reportDir,
              videoDir,
              playwright,
              browser,
              browserContext,
              video,
              flow,
              recorder,
              interactions,
              commands);
      context.getStore(NAMESPACE).put(STATE_KEY, state);
    } catch (RuntimeException | Error e) {
      closeQuietly(browser);
      closeQuietly(playwright);
      throw e;
    }
  }

  @Override
  public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext context)
      throws ParameterResolutionException {
    Class<?> type = parameterContext.getParameter().getType();
    return type == Flow.class
        || type == Interactions.class
        || type == Commands.class
        || type == UserflowContext.class;
  }

  @Override
  public Object resolveParameter(ParameterContext parameterContext, ExtensionContext context)
      throws ParameterResolutionException {
    Class<?> type = parameterContext.getParameter().getType();
    if (type == UserflowContext.class) {
      return CONTEXT;
    }
    if (type == Interactions.class) {
      return state(context).interactions;
    }
    if (type == Commands.class) {
      return state(context).commands;
    }
    return state(context).flow;
  }

  @Override
  public void handleTestExecutionException(ExtensionContext context, Throwable throwable)
      throws Throwable {
    StoryState state = state(context);
    state.outcome = UserflowReport.FAILED;
    state.recorder.recordFailure(messageOf(throwable));
    if (!state.expectFailure) {
      throw throwable; // a real story failure still fails the build
    }
    // @ExpectedFailure: swallow so the suite stays green — the failure path is what we're proving.
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    StoryState state = state(context);
    if (state == null) {
      return; // beforeEach failed before storing state
    }
    try {
      finalizeVideoAndReport(state);
    } finally {
      closeQuietly(state.browser);
      closeQuietly(state.playwright);
    }
    if (state.expectFailure && !UserflowReport.FAILED.equals(state.outcome)) {
      throw new AssertionError(
          "@ExpectedFailure story '" + state.name + "' was expected to fail but passed");
    }
    // Only for a story that would otherwise pass, and only after the report is on disk: a story
    // that already failed has a better error to report, and the bundle must exist either way.
    if (UserflowReport.PASSED.equals(state.outcome)) {
      state.commands.checkNoPendingExpectation();
    }
    // Record the pass only now, so a failed / @ExpectedFailure story never satisfies a
    // precondition.
    if (UserflowReport.PASSED.equals(state.outcome)) {
      PASSED_SLUGS.add(state.slug);
    }
  }

  private void finalizeVideoAndReport(StoryState state) {
    // Video is best-effort: a story that recorded steps must always get its report, so a failure
    // finalizing the webm must not cost us userflow.json / user-story.md.
    UserflowReport.Video video = state.browserContext == null ? null : saveVideo(state);

    // Masking runs first and over the whole step log, so a redacted secret cannot reach the
    // sidecar, the markdown, the HTML or any artifact — whatever facade recorded it.
    state.commands.maskRecordedSteps();

    List<UserflowReport.Interaction> interactions = state.interactions.emit();
    List<UserflowReport.Command> commands = state.commands.emitCommands();
    List<UserflowReport.WrittenFile> files = state.commands.emitFiles();
    UserflowReport report =
        new UserflowReport(
            state.name,
            state.slug,
            state.category,
            state.description,
            List.copyOf(state.recorder.steps()),
            state.recorder.definitionHash(),
            // null, not an empty list: a story that recorded none of these keeps the exact sidecar
            // bytes it had before the field existed.
            interactions.isEmpty() ? null : interactions,
            commands.isEmpty() ? null : commands,
            files.isEmpty() ? null : files,
            state.flow == null ? List.of() : state.flow.emitScreenshots(),
            video,
            state.outcome);

    try {
      for (ReportRenderer renderer : RENDERERS) {
        renderer.render(report, state.reportDir);
      }
      // The bundle's entry point, rewritten after EVERY story rather than in an end-of-run hook:
      // stories emit one class at a time, so a rescan here means the index on disk is complete
      // whenever the JVM stops — and the docs reader opens a bundle at index.html, so a bundle
      // without one is cataloged but unreadable.
      SiteIndexWriter.rewrite(UserflowPaths.outputRoot());
    } catch (IOException e) {
      throw new UncheckedIOException("failed to render report for '" + state.name + "'", e);
    }
  }

  /**
   * Finalize and save the recording (closing the context flushes the webm; {@code saveAs} blocks
   * until it is on disk), returning {@code null} rather than throwing if anything fails — the
   * report is written regardless. Duration is deliberately not emitted: Playwright exposes no webm
   * length and a wall-clock value would make the canonical sidecar differ on every run.
   */
  private static UserflowReport.Video saveVideo(StoryState state) {
    try {
      state.browserContext.close();
      if (state.video == null) {
        return null;
      }
      Path recording = state.reportDir.resolve("recording.webm");
      state.video.saveAs(recording);
      return new UserflowReport.Video(
          recording.getFileName().toString(), VIDEO_WIDTH, VIDEO_HEIGHT);
    } catch (RuntimeException e) {
      return null;
    } finally {
      deleteRecursively(state.videoDir); // drop Playwright's per-page temp dir
    }
  }

  private static void closeQuietly(AutoCloseable resource) {
    if (resource == null) {
      return;
    }
    try {
      resource.close();
    } catch (Exception ignored) {
      // best effort
    }
  }

  /**
   * Create Playwright, skipping its always-on browser <i>install</i> step when a browser is already
   * present (the baked {@code docker/workspace} image ships Chromium at {@code
   * PLAYWRIGHT_BROWSERS_PATH}). The Java driver otherwise re-runs {@code install} on every {@code
   * create()} and fails offline; skipping it makes it use the pinned baked browser. When no browser
   * is installed (local authoring on a bare machine) we leave the install enabled so it downloads.
   */
  private static Playwright createPlaywright() {
    Playwright.CreateOptions options = new Playwright.CreateOptions();
    if (browserAlreadyInstalled()) {
      options.setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"));
    }
    return Playwright.create(options);
  }

  private static boolean browserAlreadyInstalled() {
    String path = System.getenv("PLAYWRIGHT_BROWSERS_PATH");
    if (path == null || path.isBlank()) {
      return false;
    }
    Path dir = Path.of(path);
    if (!Files.isDirectory(dir)) {
      return false;
    }
    try (Stream<Path> entries = Files.list(dir)) {
      return entries.anyMatch(entry -> entry.getFileName().toString().startsWith("chromium-"));
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * The slug of the single {@code @UserStory} on {@code storyClass}; used to key the passed set.
   */
  static String storySlug(Class<?> storyClass) {
    Method storyMethod = null;
    for (Method method : storyClass.getDeclaredMethods()) {
      if (method.isAnnotationPresent(UserStory.class)) {
        if (storyMethod != null) {
          throw new IllegalStateException(
              storyClass.getName()
                  + " has multiple @UserStory methods; a precondition class must have exactly one");
        }
        storyMethod = method;
      }
    }
    if (storyMethod == null) {
      throw new IllegalStateException(
          storyClass.getName()
              + " has no @UserStory method — it cannot be a @UserflowPrecondition");
    }
    return Slugs.slug(storyMethod.getAnnotation(UserStory.class).value());
  }

  /**
   * The precondition classes declared on {@code storyClass}'s story method; empty if none / not a
   * story class (lenient, so the orderer can scan every test class). Gating: each must pass.
   */
  static Class<?>[] preconditionsOf(Class<?> storyClass) {
    Method storyMethod = storyMethodOf(storyClass);
    UserflowPrecondition precondition =
        storyMethod == null ? null : storyMethod.getAnnotation(UserflowPrecondition.class);
    return precondition == null ? new Class<?>[0] : precondition.value();
  }

  /** The ordering-only ({@link UserflowRunsAfter}) predecessor classes; empty if none. */
  static Class<?>[] runsAfterOf(Class<?> storyClass) {
    Method storyMethod = storyMethodOf(storyClass);
    UserflowRunsAfter runsAfter =
        storyMethod == null ? null : storyMethod.getAnnotation(UserflowRunsAfter.class);
    return runsAfter == null ? new Class<?>[0] : runsAfter.value();
  }

  /** Every class that must be ordered before {@code storyClass} — preconditions plus runs-after. */
  static Class<?>[] orderingPredecessorsOf(Class<?> storyClass) {
    Class<?>[] preconditions = preconditionsOf(storyClass);
    Class<?>[] runsAfter = runsAfterOf(storyClass);
    if (runsAfter.length == 0) {
      return preconditions;
    }
    if (preconditions.length == 0) {
      return runsAfter;
    }
    Class<?>[] all = new Class<?>[preconditions.length + runsAfter.length];
    System.arraycopy(preconditions, 0, all, 0, preconditions.length);
    System.arraycopy(runsAfter, 0, all, preconditions.length, runsAfter.length);
    return all;
  }

  private static Method storyMethodOf(Class<?> storyClass) {
    for (Method method : storyClass.getDeclaredMethods()) {
      if (method.isAnnotationPresent(UserStory.class)) {
        return method;
      }
    }
    return null;
  }

  private static void checkForCollision(String slug, String name) {
    String previous = EMITTED_SLUGS.putIfAbsent(slug, name);
    if (previous != null && !previous.equals(name)) {
      throw new IllegalStateException(
          "user-story slug collision: '"
              + name
              + "' and '"
              + previous
              + "' both slug to '"
              + slug
              + "' — rename one so their report directories don't overwrite each other");
    }
  }

  private static void freshDirectory(Path dir) throws IOException {
    deleteRecursively(dir);
    Files.createDirectories(dir);
  }

  private static void deleteRecursively(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(UserStoryExtension::deleteQuietly);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to clean " + dir, e);
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // best effort; a leftover file will simply be overwritten next run
    }
  }

  private static String messageOf(Throwable throwable) {
    String type = throwable.getClass().getSimpleName();
    String raw = throwable.getMessage();
    if (raw == null || raw.isBlank()) {
      return type;
    }
    String message = raw.replaceAll("\\s+", " ").strip();
    if (message.length() > 200) {
      message = message.substring(0, 199) + "…";
    }
    return type + ": " + message;
  }

  private static StoryState state(ExtensionContext context) {
    return context.getStore(NAMESPACE).get(STATE_KEY, StoryState.class);
  }

  /**
   * Mutable per-story holder kept in the extension store. The Playwright fields ({@code playwright}
   * through {@code flow}) are all {@code null} for a browserless story; {@code recorder}, {@code
   * interactions} and {@code commands} always exist.
   */
  private static final class StoryState {
    final String name;
    final String slug;
    final String category;
    final String description;
    final boolean expectFailure;
    final Path reportDir;
    final Path videoDir;
    final Playwright playwright;
    final Browser browser;
    final BrowserContext browserContext;
    final Video video;
    final Flow flow;
    final StepRecorder recorder;
    final Interactions interactions;
    final Commands commands;
    volatile String outcome = UserflowReport.PASSED;

    StoryState(
        String name,
        String slug,
        String category,
        String description,
        boolean expectFailure,
        Path reportDir,
        Path videoDir,
        Playwright playwright,
        Browser browser,
        BrowserContext browserContext,
        Video video,
        Flow flow,
        StepRecorder recorder,
        Interactions interactions,
        Commands commands) {
      this.name = name;
      this.slug = slug;
      this.category = category;
      this.description = description;
      this.expectFailure = expectFailure;
      this.reportDir = reportDir;
      this.videoDir = videoDir;
      this.playwright = playwright;
      this.browser = browser;
      this.browserContext = browserContext;
      this.video = video;
      this.flow = flow;
      this.recorder = recorder;
      this.interactions = interactions;
      this.commands = commands;
    }
  }
}
