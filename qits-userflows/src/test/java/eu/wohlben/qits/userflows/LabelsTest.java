package eu.wohlben.qits.userflows;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The scrubber decides whether a story's {@code networkHash} is stable, so both halves of it matter
 * equally: a run-local id must become a placeholder, and text that merely <i>looks</i> like one
 * must survive untouched — over-eager scrubbing would erase the very path shape the diagram exists
 * to show.
 */
class LabelsTest {

  @Test
  void runLocalPathSegmentsBecomePlaceholders() {
    assertEquals(
        "GET /projects/{id}/tasks -> 200",
        Labels.scrub("GET /projects/3f2a1b4c-5d6e-7f80-9a1b-2c3d4e5f6071/tasks -> 200"));
    assertEquals("GET /tasks/{id} -> 200", Labels.scrub("GET /tasks/42 -> 200"));
    assertEquals(
        "GET /blobs/{digest} -> 200",
        Labels.scrub("GET /blobs/0123456789abcdef0123456789abcdef -> 200"));
    // A trailing segment with nothing after it is still a whole segment.
    assertEquals("DELETE /sessions/{id}", Labels.scrub("DELETE /sessions/7"));
  }

  @Test
  void aDigestLiteralIsRewrittenWhereverItSits() {
    assertEquals(
        "PUT /v2/qits/manifests/{digest} -> 201",
        Labels.scrub(
            "PUT /v2/qits/manifests/sha256:9f86d081884c7d659a2feaa0c55ad015 -> 201"));
    // Not a path segment at all — the digest form is recognised on its own.
    assertEquals("pull image {digest}", Labels.scrub("pull image sha256:1234567890abcdef12"));
  }

  /**
   * The second position a generated id reaches a label from. A query value is normally authored —
   * a branch name, a version, a page size — so only the two shapes nothing types by hand are
   * rewritten, and the digits case is the line: {@code /tasks/42} is a row this run created,
   * {@code ?limit=10} is a number the story wrote.
   */
  @Test
  void queryBorneIdsAreScrubbedAndAuthoredValuesAreNot() {
    assertEquals(
        "GET /tasks?project={id} -> 200",
        Labels.scrub("GET /tasks?project=3f2a1b4c-5d6e-7f80-9a1b-2c3d4e5f6071 -> 200"));
    assertEquals(
        "GET /blobs?digest={digest} -> 200",
        Labels.scrub("GET /blobs?digest=0123456789abcdef0123456789abcdef -> 200"));
    // A value ends at the next parameter, not at the end of the query.
    assertEquals(
        "GET /runs?id={id}&latest -> 200",
        Labels.scrub("GET /runs?id=3f2a1b4c-5d6e-7f80-9a1b-2c3d4e5f6071&latest -> 200"));
    // Ordinary values survive: a word, a dotted version, and — deliberately — a small number.
    assertEquals(
        "GET /docs?meta.git.branch.name=main -> 200",
        Labels.scrub("GET /docs?meta.git.branch.name=main -> 200"));
    assertEquals(
        "GET /packages?version=1.2.3 -> 200", Labels.scrub("GET /packages?version=1.2.3 -> 200"));
    assertEquals("GET /tasks?limit=10 -> 200", Labels.scrub("GET /tasks?limit=10 -> 200"));
  }

  @Test
  void textThatOnlyLooksRunLocalSurvives() {
    assertEquals("GET /idp/jwks -> 200", Labels.scrub("GET /idp/jwks -> 200"));
    // A version segment is not an id, a status code is not a path segment, and a short hex run is
    // ambiguous enough that rewriting it would cost more than it buys.
    assertEquals("GET /v2/health -> 404", Labels.scrub("GET /v2/health -> 404"));
    assertEquals("GET /objects/beef -> 200", Labels.scrub("GET /objects/beef -> 200"));
    // A numeric run inside a segment is part of the name, not the whole of it.
    assertEquals("GET /task42/state", Labels.scrub("GET /task42/state"));
  }
}
