package eu.wohlben.qits.userflows.harness;

import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.userflows.ExpectedFailure;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import org.junit.jupiter.api.AfterAll;

/**
 * The failure path of a browserless story: the framework must append the {@code FAILED:} step and
 * emit the report without any Playwright resources to fall back on ({@code recordFailure} used to
 * live on {@code Flow}, which a service story doesn't have).
 */
class BrowserlessFailureHarnessTest {

  private static final String SLUG = "a-service-story-fails-mid-way";

  @UserStory("A service story fails mid-way")
  @UserStoryDescription("Covers the framework's failure path for browserless stories.")
  @ExpectedFailure
  void serviceStoryFails(Interactions interactions) {
    interactions.note("one recorded step before the failure");
    throw new IllegalStateException("the far side never answered");
  }

  @AfterAll
  static void reportRecordsTheFailure() {
    ReportAssertions.assertFailedWithPartialLog(SLUG);
    assertNull(ReportAssertions.read(SLUG).video(), "browserless story must not record a video");
  }
}
