package eu.wohlben.qits.userflows;

import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowPaths;
import eu.wohlben.qits.userflows.report.UserflowReport;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * The shell-command recording facade of a {@link UserStory} — the third recording surface beside
 * {@link Flow} (a browser) and {@link Interactions} (the narrative). It exists for the stories a
 * browser cannot tell: a CLI is the product, or the product is <i>reached</i> through one, and the
 * evidence a reader wants is the transcript — the command as typed, the output as printed, the exit
 * code as returned.
 *
 * <p>Like the other two facades <b>every verb appends a step</b> to the story's single {@link
 * StepRecorder}, so a story that mixes all three reads as one narrative in call order. Anything
 * done outside the facade leaves no record; there is deliberately no escape hatch returning a live
 * {@link Process}.
 *
 * <p><b>There is no shell.</b> {@link #run} tokenizes the <i>template</i> and hands the resulting
 * argv straight to {@link ProcessBuilder}, so a substituted value is always exactly one argument
 * and can never inject a word, a redirect or a second command — see {@link CommandLine}. When a
 * story genuinely needs shell semantics (a pipeline, a redirect, {@code &&}) {@link #sh} is the
 * explicit, documented escape hatch, and its javadoc says plainly what that costs. POSIX only: the
 * facade assumes a POSIX filesystem and {@code /bin/sh}, which is what every environment these
 * stories run in provides.
 *
 * <p><b>The transcript survives the failure.</b> A command's step, output and exit code are
 * recorded <i>before</i> the expected-exit assertion runs — a failing command is precisely the one
 * whose output a reader needs, so it must reach the report rather than being lost to the thrown
 * {@link AssertionError}. The same holds for a timeout (the partial transcript is emitted) and for
 * a program that is not on {@code PATH}.
 *
 * <p><b>Two parallel logs, as everywhere.</b> The <b>display</b> line carries the resolved command
 * ({@code run cat /tmp/x9f/report.txt}) while the <b>fingerprint</b> keeps the template ({@code run
 * cat {}}), so two stories that run the same command with different values share a {@linkplain
 * StepRecorder#definitionHash() definition hash}. {@link #env} follows the same fill rule (value in
 * the display, name only in the hash); {@link #in}, {@link #file} and {@link #script} take
 * author-static paths, so display and fingerprint coincide.
 *
 * <p><b>Where files go.</b> Each story gets a private scratch directory — {@link #workDir()},
 * created and wiped on first use — that every relative path resolves against and every command runs
 * in. It is <i>not</i> the report directory: scratch is working state, the report is evidence.
 * {@link #file} and {@link #script} additionally capture what they wrote, and the report gets a
 * dump of it beside the transcripts.
 *
 * <p><b>Secrets.</b> {@link #redact} registers a value that is masked out of everything the report
 * carries — step lines, transcripts, file dumps, the sidecar — while the real value keeps working
 * in the real command and the real file on disk.
 *
 * <p>Not thread-safe, and deliberately so: a story is a single narrative on a single thread.
 * Instances are created by {@link UserStoryExtension}; stories only ever receive one as a method
 * parameter.
 */
public final class Commands {

  /**
   * Per-command timeout, in <b>milliseconds</b> (a bare integer — not an ISO-8601 duration, so the
   * property reads the same as every other millisecond knob in the build).
   */
  public static final String TIMEOUT_PROPERTY = "qits.userflows.command.timeout";

  /**
   * Five minutes: long enough for a real build or install step, short enough that a hung command
   * fails a CI run instead of holding it until the job-level timeout.
   */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

  /**
   * The exit code recorded when the program could not be started at all. 127 is the POSIX shell's
   * "command not found", so the report reads the way a terminal would.
   */
  private static final int EXIT_NOT_FOUND = 127;

  /**
   * The exit code recorded when the command outran its timeout and was destroyed — 124, the
   * convention GNU {@code timeout(1)} established.
   */
  private static final int EXIT_TIMED_OUT = 124;

  /** Cap for the slug taken from a command / path when naming its artifact file. */
  private static final int ARTIFACT_SLUG_LIMIT = 40;

  private final StepRecorder recorder;
  private final Path reportDir;
  private final String categorySlug;
  private final String slug;

  // Everything below is created lazily: a story that never touches Commands must leave no scratch
  // directory behind and no trace in its sidecar.
  private Path workRoot;
  private Path currentDir;

  private final Map<String, String> env = new LinkedHashMap<>();
  private final List<String> secrets = new ArrayList<>();
  // Commands and files are resolved against their owning step at emit time (the PendingShot
  // pattern from Flow), so an author's .as(id) rename settles the step id before the artifact
  // file name and the by-id link are derived from it.
  private final List<PendingCommand> pendingCommands = new ArrayList<>();
  private final List<PendingFile> pendingFiles = new ArrayList<>();

  private Duration timeout = configuredTimeout();
  private ExitExpectation expectation;

  private String lastOutput = "";
  private int lastExitCode;
  private String lastCommand = "";

  Commands(StepRecorder recorder, Path reportDir, String categorySlug, String slug) {
    this.recorder = recorder;
    this.reportDir = reportDir;
    this.categorySlug = categorySlug;
    this.slug = slug;
  }

  // --- running -------------------------------------------------------------------------------

  /**
   * Run a command, with no shell involved. The <b>template</b> is tokenized first — quote-aware,
   * {@code '…'} and {@code "…"} group words — and each {@code {}} placeholder is then filled from
   * {@code args} <i>within its token</i>, so a value is always exactly one argv element:
   *
   * <pre>{@code
   * commands.run("git clone {} repo", url);        // url is one argument, spaces and all
   * commands.run("npm install --registry={}", r);  // concatenates inside the token
   * commands.run("git commit -m 'first cut'");     // quotes group, then vanish
   * }</pre>
   *
   * <p>Records {@code run <resolved command>} with {@code run <template>} in the fingerprint. The
   * output is stdout and stderr <b>merged</b>, in the order the process wrote them — that
   * interleaving is what makes a transcript readable, and no report ever wants them split.
   *
   * <p>The exit code must match the expectation ({@code 0} unless {@link #expectExit} or {@link
   * #expectAnyExit} preceded this call) or the story fails — but only <i>after</i> the step and its
   * transcript have been recorded.
   *
   * <p>Fails fast (before running anything) on an unterminated quote or a placeholder/argument
   * count mismatch, and fails with {@code command not found} if the program is not on {@code
   * PATH}.
   */
  public Commands run(String template, Object... args) {
    List<String> argv = CommandLine.argv(template, args);
    String commandText = CommandLine.display(argv);
    String label = argv.get(0) + (argv.size() > 1 ? " " + argv.get(1) : "");
    return execute(argv, commandText, "run " + commandText, "run " + template, label);
  }

  /**
   * Run a script through {@code sh -c} — the explicit escape hatch for the things an argv cannot
   * express: pipelines, redirects, {@code &&}, globbing, {@code $?}.
   *
   * <p><b>Values are not shell-quoted.</b> The {@code {}} placeholders are filled into the script
   * text verbatim, so a value containing a space, a quote or a {@code ;} changes what the shell
   * parses. That is the whole difference from {@link #run}, which cannot be broken this way —
   * <b>prefer {@code run()}</b> and reach for {@code sh()} only when the shell itself is the point.
   * When a value must ride along, put it in the environment ({@link #env}) or a file ({@link
   * #file}) and reference it from the script instead of interpolating it.
   *
   * <p>Records {@code sh -c "<script>"} (flattened to one line), keeping the <i>template</i> in the
   * fingerprint. Everything else — merged output, timeout, exit expectation, record-before-assert —
   * is exactly {@link #run}.
   */
  public Commands sh(String scriptTemplate, Object... args) {
    String script = CommandLine.fill(scriptTemplate, args);
    List<String> argv = List.of("sh", "-c", script);
    String commandText = "sh -c \"" + CommandLine.oneLine(script) + "\"";
    String fingerprint = "sh -c \"" + CommandLine.oneLine(scriptTemplate) + "\"";
    return execute(argv, commandText, commandText, fingerprint, "sh " + script);
  }

  // --- expectations --------------------------------------------------------------------------

  /**
   * Expect the <b>next</b> command to exit with {@code code} instead of {@code 0} — for a story
   * whose point is that the tool refuses ({@code exit 1} on a lint violation, {@code exit 2} on a
   * bad flag). One-shot: it applies to the next command only, and the one after that is back to
   * expecting success. Records no step; the expectation is a property of the command that follows,
   * not an event in the story.
   *
   * <p>An expectation that is still pending when the story ends fails an otherwise-passing story —
   * a dangling {@code expectExit} means the command it was guarding never ran, and a story that
   * silently skipped its own subject is not a passing story.
   */
  public Commands expectExit(int code) {
    expectation = new ExitExpectation(code);
    return this;
  }

  /**
   * Accept whatever the next command exits with — for the rare step whose exit code is genuinely
   * not the point (a probe, a best-effort cleanup). Same one-shot, records-nothing,
   * must-be-consumed rules as {@link #expectExit}.
   */
  public Commands expectAnyExit() {
    expectation = new ExitExpectation(null);
    return this;
  }

  /**
   * How long a single command may run before it is destroyed and the story fails. <b>Sticky</b>,
   * unlike the exit expectation: a story that needs a longer leash usually needs it for everything
   * that follows. Defaults to {@link #DEFAULT_TIMEOUT}, overridable run-wide with {@link
   * #TIMEOUT_PROPERTY} (milliseconds).
   *
   * <p>On expiry the process tree is destroyed forcibly and the <b>partial transcript is still
   * emitted</b> — what a hung command printed before it stopped printing is the evidence a reader
   * needs — and then the story fails.
   */
  public Commands timeout(Duration duration) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("command timeout must be positive: " + duration);
    }
    this.timeout = duration;
    return this;
  }

  // --- environment and files -------------------------------------------------------------------

  /**
   * Set an environment variable for every command that follows, on top of the inherited
   * environment. Records {@code env <name>=<value>} with only {@code env <name>} in the
   * fingerprint — the fill rule: a token or a port changes per run, the fact that the variable is
   * set does not.
   */
  public Commands env(String name, String value) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("environment variable name must not be blank");
    }
    env.put(name, value == null ? "" : value);
    recorder.record("env " + name + "=" + value, "env " + name);
    return this;
  }

  /**
   * Change into {@code relativePath} beneath the story's work directory, creating it — the
   * equivalent of a {@code cd} for every command, {@link #file} and {@link #script} that follows.
   * Records {@code in <path>} (display and fingerprint alike: the path is author-static).
   *
   * <p>Refuses an absolute path or any {@code ..} segment. The scratch directory is wiped between
   * runs, so letting a story climb out of it would let a story delete something it does not own.
   */
  public Commands in(String relativePath) {
    Path dir = resolveRelative(relativePath);
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to create work directory " + dir, e);
    }
    currentDir = dir;
    recorder.record("in " + relativePath, "in " + relativePath);
    return this;
  }

  /**
   * Write a fixture file beneath the current directory, creating parents. {@code contentTemplate}
   * fills its {@code {}} placeholders from {@code args} as one blob (not tokenized — this is text,
   * not an argv).
   *
   * <p>Records {@code write <path>} — <b>path only</b>, in the display line and the fingerprint
   * alike: a config file's content is data, and putting it in a step line would both wreck the
   * one-step-one-line rule and leak whatever the fixture contains. The content instead becomes a
   * report artifact, emitted beside the transcripts and rendered under the step, with {@linkplain
   * #redact redacted} secrets masked out of it.
   */
  public Commands file(String relativePath, String contentTemplate, Object... args) {
    Path target = resolveRelative(relativePath);
    String content = CommandLine.fill(contentTemplate, args);
    write(target, content);
    int stepIndex = recorder.record("write " + relativePath, "write " + relativePath);
    pendingFiles.add(new PendingFile(stepIndex, relativePath, content));
    return this;
  }

  /**
   * Write an executable POSIX shell script: {@link #file} plus a {@code #!/bin/sh} shebang (unless
   * the body already carries one) and the executable bit, so the story can then {@code
   * run("./<path>")} it.
   *
   * <p>The body takes <b>no {@code {}} substitution</b> — a shell script is full of braces and
   * a template pass over it would be a trap. Values reach a script through {@link #env} or its
   * arguments.
   *
   * <p>This is the recommended shape for anything non-trivial that {@link #sh} would otherwise
   * inline: a script is written once, shows up in the report as its own artifact, and is invoked by
   * a short, readable {@code run} line.
   */
  public Commands script(String relativePath, String posixShellBody) {
    String content =
        posixShellBody.startsWith("#!") ? posixShellBody : "#!/bin/sh\n" + posixShellBody;
    Path target = resolveRelative(relativePath);
    write(target, content);
    makeExecutable(target);
    int stepIndex = recorder.record("write " + relativePath, "write " + relativePath);
    pendingFiles.add(new PendingFile(stepIndex, relativePath, content));
    return this;
  }

  /**
   * Register a value that must never appear in the report: every occurrence is replaced with
   * {@code •••} in the step display lines, the transcripts, the written-file dumps and the
   * canonical sidecar. Records no step — redaction is a property of the report, not an event in the
   * story.
   *
   * <p>Masking is applied at <b>emit</b> time over everything the story produced, so the call order
   * does not matter: a token printed by a command three steps earlier is still masked. The real
   * value keeps working in the real command and in the real file in the work directory — only the
   * <i>evidence</i> is masked. The work directory is not part of the published bundle.
   */
  public Commands redact(String secret) {
    if (secret != null && !secret.isEmpty()) {
      secrets.add(secret);
    }
    return this;
  }

  /**
   * Give the step just recorded an explicit id instead of the machine-assigned {@code step-NN} —
   * exactly {@link Flow#as}: unique within the story, {@code [A-Za-z0-9] then [A-Za-z0-9._-]*}. A
   * command's transcript artifact is named after that id, so a meaningful id makes the report
   * directory self-describing.
   */
  public Commands as(String id) {
    recorder.as(id);
    return this;
  }

  // --- reads (record nothing) ------------------------------------------------------------------

  /**
   * The last command's merged output, sanitized and capped exactly as the report will show it — the
   * read a story uses to extract produced state for a later step (an id, a generated path) or to
   * assert something the exit code cannot express.
   */
  public String lastOutput() {
    return lastOutput;
  }

  /** The last command's exit code — meaningful chiefly after {@link #expectAnyExit}. */
  public int lastExitCode() {
    return lastExitCode;
  }

  /** The last command as resolved and recorded (the display form, not a shell-quoted one). */
  public String lastCommand() {
    return lastCommand;
  }

  /**
   * The story's private scratch directory, created (and wiped clean) on first use. Every relative
   * path and every command resolves against it, and it is where a story looks for whatever its
   * commands produced. Kept out of the report directory on purpose: scratch is working state, the
   * report is evidence.
   */
  public Path workDir() {
    ensureWorkRoot();
    return workRoot;
  }

  // --- consumed by the extension to build the report -------------------------------------------

  /**
   * Mask every registered secret out of the story's step log. Runs before the report is built (and
   * before {@link #emitCommands()} / {@link #emitFiles()}), so masking covers the whole log — the
   * {@link Flow} steps and the appended {@code FAILED:} line included — rather than only the lines
   * this facade wrote.
   */
  void maskRecordedSteps() {
    if (secrets.isEmpty()) {
      return;
    }
    recorder.maskLines(line -> CommandOutput.mask(line, secrets));
  }

  /**
   * The same masking the step log gets, as an operator the extension applies to every network
   * edge field before it reaches the sidecar — identity when nothing was redacted.
   */
  UnaryOperator<String> masker() {
    if (secrets.isEmpty()) {
      return UnaryOperator.identity();
    }
    return line -> CommandOutput.mask(line, secrets);
  }

  /**
   * Write each captured transcript into the report dir and return the records — deferred to here so
   * every step id (including {@link #as(String)} overrides) is final before the file name and the
   * by-id link are derived from it, and so redaction applies to output produced before the secret
   * was registered.
   *
   * <p>A command that printed nothing gets no artifact and no inline excerpt: an empty file in the
   * bundle would be noise, and {@code null} is how the sidecar says "silent".
   */
  List<UserflowReport.Command> emitCommands() {
    List<UserflowReport.Command> emitted = new ArrayList<>();
    for (PendingCommand pending : pendingCommands) {
      String stepId = recorder.steps().get(pending.stepIndex).id();
      String output = CommandOutput.mask(pending.output, secrets);
      String outputPath = null;
      if (!output.isEmpty()) {
        outputPath = stepId + "-" + artifactSlug(pending.label) + ".txt";
        writeArtifact(outputPath, output);
      }
      emitted.add(
          new UserflowReport.Command(
              stepId,
              CommandOutput.mask(pending.command, secrets),
              pending.exitCode,
              output.isEmpty() ? null : CommandOutput.excerpt(output),
              outputPath,
              pending.truncated ? Boolean.TRUE : null));
    }
    return emitted;
  }

  /** The written-file records, with each dump emitted beside the transcripts. Same deferral. */
  List<UserflowReport.WrittenFile> emitFiles() {
    List<UserflowReport.WrittenFile> emitted = new ArrayList<>();
    for (PendingFile pending : pendingFiles) {
      String stepId = recorder.steps().get(pending.stepIndex).id();
      String contentPath = stepId + "-" + artifactSlug(pending.path) + ".txt";
      writeArtifact(contentPath, CommandOutput.mask(pending.content, secrets));
      emitted.add(new UserflowReport.WrittenFile(stepId, pending.path, contentPath));
    }
    return emitted;
  }

  /**
   * Fail a story that armed an exit expectation and then never fired it. Checked only for a story
   * that would otherwise pass — a story that already failed has a better error to report, and
   * piling a second one on top would bury it.
   */
  void checkNoPendingExpectation() {
    if (expectation == null) {
      return;
    }
    throw new AssertionError(
        (expectation.code == null
                ? "expectAnyExit()"
                : "expectExit(" + expectation.code + ")")
            + " was armed but no command followed it — the command it guarded never ran");
  }

  // --- internals -------------------------------------------------------------------------------

  private Commands execute(
      List<String> argv, String commandText, String display, String fingerprint, String label) {
    Path dir = currentDir();
    ProcessBuilder builder = new ProcessBuilder(argv).directory(dir.toFile());
    // Merged rather than split: the interleaving of stdout and stderr in the order the process
    // wrote them IS the transcript a reader wants; two separate streams read as neither.
    builder.redirectErrorStream(true);
    builder.environment().putAll(env);

    lastCommand = commandText;
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      lastExitCode = EXIT_NOT_FOUND;
      lastOutput = "";
      int stepIndex = recorder.record(display, fingerprint);
      pendingCommands.add(
          new PendingCommand(stepIndex, label, commandText, EXIT_NOT_FOUND, "", false));
      expectation = null;
      throw new AssertionError(
          "command not found: " + argv.get(0) + " — is it on PATH?", e);
    }

    CommandOutput.Sink sink = new CommandOutput.Sink(CommandOutput.maxOutputBytes());
    Thread drain =
        Thread.ofPlatform()
            .daemon()
            .name("userflow-command-output")
            .start(
                () -> {
                  try (InputStream out = process.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = out.read(buffer)) >= 0) {
                      sink.write(buffer, read);
                    }
                  } catch (IOException ignored) {
                    // the pipe died with the process; whatever was captured is the transcript
                  }
                });

    boolean finished;
    try {
      finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      finished = false;
    } finally {
      // Only while it is still running. Destroying an ALREADY-EXITED process is not a no-op: it
      // races the JDK's hand-over of the last buffered bytes and reproducibly loses the whole
      // transcript of a short command (measured at a few percent of runs). A process that did
      // finish needs no killing; one that did not must never outlive the story.
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
    // Bounded join: the drain thread ends at EOF, which a dead process guarantees — unless a
    // grandchild inherited the pipe, in which case we take the partial transcript and move on.
    joinQuietly(drain);

    String output = sink.text();
    int exitCode = finished ? process.exitValue() : EXIT_TIMED_OUT;

    // Recorded BEFORE any assertion: a failing command's transcript is exactly what the report is
    // for, so it must survive into the bundle even though the next line throws.
    int stepIndex = recorder.record(display, fingerprint);
    pendingCommands.add(
        new PendingCommand(stepIndex, label, commandText, exitCode, output, sink.truncated()));
    lastOutput = output;
    lastExitCode = exitCode;

    ExitExpectation expected = expectation;
    expectation = null;

    if (!finished) {
      throw new AssertionError(
          "command timed out after " + timeout + ": " + commandText + tail(output));
    }
    if (expected == null) {
      expected = new ExitExpectation(0);
    }
    if (expected.code != null && expected.code != exitCode) {
      throw new AssertionError(
          "expected exit "
              + expected.code
              + " but got "
              + exitCode
              + " from: "
              + commandText
              + tail(output));
    }
    return this;
  }

  /** The trailing output appended to an assertion message, so the failure explains itself. */
  private static String tail(String output) {
    if (output.isEmpty()) {
      return " (no output)";
    }
    String excerpt = CommandOutput.excerpt(output);
    return "\n--- output ---\n" + excerpt + (excerpt.endsWith("\n") ? "" : "\n") + "--------------";
  }

  private static void joinQuietly(Thread thread) {
    try {
      thread.join(Duration.ofSeconds(10));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private Path currentDir() {
    ensureWorkRoot();
    return currentDir;
  }

  private void ensureWorkRoot() {
    if (workRoot != null) {
      return;
    }
    Path root = UserflowPaths.workDir(categorySlug, slug);
    // Wiped, not merged: a story must see the same empty directory on a rerun, or yesterday's
    // leftovers quietly become today's fixtures.
    deleteRecursively(root);
    try {
      Files.createDirectories(root);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to create work directory " + root, e);
    }
    workRoot = root;
    currentDir = root;
  }

  private Path resolveRelative(String relativePath) {
    if (relativePath == null || relativePath.isBlank()) {
      throw new IllegalArgumentException("path must not be blank");
    }
    Path candidate = Path.of(relativePath);
    if (candidate.isAbsolute()) {
      throw new IllegalArgumentException(
          "path must be relative to the story's work directory: " + relativePath);
    }
    for (Path segment : candidate) {
      if ("..".equals(segment.toString())) {
        throw new IllegalArgumentException(
            "path must not climb out of the story's work directory: " + relativePath);
      }
    }
    return currentDir().resolve(candidate).normalize();
  }

  private static void write(Path target, String content) {
    try {
      Path parent = target.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(target, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to write " + target, e);
    }
  }

  private static void makeExecutable(Path target) {
    try {
      Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
      permissions.addAll(Files.getPosixFilePermissions(target));
      permissions.add(PosixFilePermission.OWNER_EXECUTE);
      permissions.add(PosixFilePermission.GROUP_EXECUTE);
      permissions.add(PosixFilePermission.OTHERS_EXECUTE);
      Files.setPosixFilePermissions(target, permissions);
    } catch (IOException | UnsupportedOperationException e) {
      throw new IllegalStateException(
          "failed to make " + target + " executable — Commands is POSIX-only", e);
    }
  }

  private void writeArtifact(String fileName, String content) {
    try {
      Files.writeString(reportDir.resolve(fileName), content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to write command artifact " + fileName, e);
    }
  }

  /** {@link Slugs#slug} capped, so an artifact file name stays readable and portable. */
  private static String artifactSlug(String text) {
    String slugged = Slugs.slug(text);
    if (slugged.length() > ARTIFACT_SLUG_LIMIT) {
      slugged = slugged.substring(0, ARTIFACT_SLUG_LIMIT).replaceAll("-+$", "");
    }
    return slugged.isEmpty() ? "command" : slugged;
  }

  private static Duration configuredTimeout() {
    String raw = System.getProperty(TIMEOUT_PROPERTY);
    if (raw == null || raw.isBlank()) {
      return DEFAULT_TIMEOUT;
    }
    try {
      return Duration.ofMillis(Long.parseLong(raw.strip()));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          TIMEOUT_PROPERTY + " must be a millisecond count: " + raw, e);
    }
  }

  private static void deleteRecursively(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // best effort; a leftover file is overwritten on write
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException("failed to clean " + dir, e);
    }
  }

  /** A ran command awaiting its owning step's final id at emit time. */
  private record PendingCommand(
      int stepIndex,
      String label,
      String command,
      int exitCode,
      String output,
      boolean truncated) {}

  /** A written file awaiting its owning step's final id at emit time. */
  private record PendingFile(int stepIndex, String path, String content) {}

  /** A one-shot exit expectation; {@code code == null} means "any exit code is fine". */
  private record ExitExpectation(Integer code) {}
}
