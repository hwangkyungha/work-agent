package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PullRequestReviewTest {
    @TempDir Path temporary;
    Store store; WorkService service; FakeApi api; PullRequestReview reviews;
    static class FakeApi extends BitbucketClient {
        String source = "source", author = "other"; boolean loseResponse, pending = true;
        int writes; List<JsonNode> comments = new ArrayList<>();
        FakeApi(Config config) { super(config); }
        @Override boolean configured() { return true; }
        @Override JsonNode pullRequest(PullRequest pr) {
            return Json.obj("id", pr.number(), "state", "OPEN", "title", "테스트 PR", "author", Json.obj("uuid", author), "reviewers", List.of(Json.obj("uuid", "owner")),
                    "source", Json.obj("commit", Json.obj("hash", source), "repository", Json.obj("full_name", "team/repo")), "destination", Json.obj("commit", Json.obj("hash", "destination"), "repository", Json.obj("full_name", "team/repo")));
        }
        @Override JsonNode get(String path) { return path.equals("/user") ? Json.obj("uuid", "owner") : Json.obj("hash", "base"); }
        @Override String request(String method, String path, JsonNode body) { assertEquals("GET", method); return "diff --git a/foo.java b/foo.java\n--- a/foo.java\n+++ b/foo.java\n@@ -1 +1 @@\n-return input;\n+return null;\n"; }
        @Override List<JsonNode> pages(String path) { return List.of(Json.obj("lines_added", 1, "lines_removed", 1, "old", Json.obj("path", "foo.java"), "new", Json.obj("path", "foo.java"))); }
        @Override String source(String repository, String commit, String path) throws Exception { if (!path.equals("foo.java")) throw new BitbucketClient.Rejected(404); return commit.equals("source") ? "return null;\n" : "return input;\n"; }
        @Override List<JsonNode> comments(PullRequest pr) { return new ArrayList<>(comments); }
        @Override JsonNode pendingComment(PullRequest pr, String text, String path, int line, String side, long parent) throws Exception {
            writes++; ObjectNode comment = Json.obj("id", writes + 100, "content", Json.obj("raw", text), "user", Json.obj("uuid", "owner"), "pending", pending, "inline", Json.obj("path", path, "to", line));
            if (parent > 0) comment.set("parent", Json.obj("id", parent)); comments.add(comment);
            if (loseResponse) throw new IOException("connection lost"); return comment;
        }
    }
    @BeforeEach void open() throws Exception {
        Config config = new Config(temporary.resolve("none"), Map.of()); store = new Store(temporary.resolve("test.db")); api = new FakeApi(config);
        service = new WorkService(store, new KoreaTime(Clock.systemUTC()), new JiraClient(config));
    }
    @AfterEach void close() throws Exception { if (reviews != null) reviews.close(); store.close(); }
    ObjectNode evidence() { return Json.obj("path", "foo.java", "side", "new", "line", 1, "quote", "return null;"); }
    ObjectNode finding() { return Json.obj("path", "foo.java", "side", "new", "line", 1, "title", "반환값 누락", "comment", "입력이 있어도 null을 반환하여 호출한 코드가 실패합니다. 입력값을 반환해 주세요.", "severity", "medium", "confidence", "high", "trigger", "입력이 있음", "impact", "호출한 코드 실패", "fix", "입력값 반환", "evidence", List.of(evidence())); }
    ObjectNode result(List<ObjectNode> findings) { return Json.obj("summary", "검토 완료", "findings", findings, "reply", "", "replyConfidence", "low", "replyEvidence", List.of(), "limitations", List.of()); }
    ObjectNode await(ObjectNode row) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        do { row = store.get("prReview", Json.required(row, "id")); if (!Set.of("queued", "running").contains(Json.text(row, "status"))) return row; Thread.sleep(10); } while (System.nanoTime() < end);
        fail("review did not finish"); return row;
    }
    @Test void requiresSecondPassExactEvidenceAndChangedAnchor() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ObjectNode wrong = finding(); wrong.put("line", 20);
        reviews = new PullRequestReview(service, api, prompt -> { assertTrue(prompt.contains("사람이 읽는 업무 글")); return result(calls.incrementAndGet() == 1 ? List.of(finding(), wrong) : List.of(finding(), wrong)); });
        ObjectNode row = await(reviews.request("https://bitbucket.org/team/repo/pull-requests/1", 0, false));
        assertEquals("ready", Json.text(row, "status")); assertEquals(2, calls.get()); assertEquals(1, row.path("findings").size()); assertEquals(1, row.path("rejectedCount").asInt()); assertEquals(0, api.writes);
        String id = Json.required(row, "id"); reviews.savePending(id); reviews.savePending(id);
        assertEquals(1, api.writes); assertEquals("saved", Json.text(store.get("prReview", id), "status"));
        assertEquals(id, Json.text(reviews.request("https://bitbucket.org/team/repo/pull-requests/1", 0, true), "id"));
    }
    @Test void rejectsUnverifiedQuotesLowConfidenceAndRefutedCandidates() throws Exception {
        ObjectNode wrong = finding(); ((ObjectNode) wrong.path("evidence").get(0)).put("quote", "invented");
        ObjectNode low = finding(); low.put("confidence", "low");
        ReviewContext context = new ReviewContext(api, BitbucketClient.PullRequest.parse("https://bitbucket.org/team/repo/pull-requests/1"));
        assertFalse(context.validFinding(wrong)); assertFalse(context.validFinding(low)); assertTrue(context.validFinding(finding()));
        AtomicInteger calls = new AtomicInteger(); reviews = new PullRequestReview(service, api, prompt -> result(calls.incrementAndGet() == 1 ? List.of(finding()) : List.of()));
        ObjectNode row = await(reviews.request("https://bitbucket.org/team/repo/pull-requests/1", 0, true));
        assertTrue(row.path("findings").isEmpty()); assertEquals(0, api.writes);
    }
    @Test void changedCommitsStopDelivery() throws Exception {
        reviews = new PullRequestReview(service, api, prompt -> result(List.of(finding())));
        ObjectNode row = await(reviews.request("https://bitbucket.org/team/repo/pull-requests/1", 0, false)); api.source = "new-head";
        assertEquals("stale", Json.text(reviews.savePending(Json.required(row, "id")), "status")); assertEquals(0, api.writes);
    }
    @Test void lostResponseIsReconciledByOwnMarkerWithoutDuplicatePost() throws Exception {
        reviews = new PullRequestReview(service, api, prompt -> result(List.of(finding())));
        ObjectNode row = await(reviews.request("https://bitbucket.org/team/repo/pull-requests/1", 0, false)); api.loseResponse = true;
        String id = Json.required(row, "id"); assertThrows(IOException.class, () -> reviews.savePending(id));
        assertEquals("delivery-unknown", Json.text(store.get("prReview", id), "status"));
        assertEquals("saved", Json.text(reviews.savePending(id), "status")); assertEquals(1, api.writes);
    }
    @Test void missingPendingConfirmationStopsRemainingComments() throws Exception {
        reviews = new PullRequestReview(service, api, prompt -> result(List.of(finding())));
        ObjectNode row = await(reviews.request("https://bitbucket.org/team/repo/pull-requests/1", 0, false)); api.pending = false;
        assertThrows(IllegalStateException.class, () -> reviews.savePending(Json.required(row, "id")));
        assertEquals("delivery-unknown", Json.text(store.get("prReview", Json.required(row, "id")), "status")); assertEquals(1, api.writes);
    }
    @Test void uncertainDeliveryCannotBeRecreatedByRetryOrAnotherAuthorsMarker() throws Exception {
        reviews = new PullRequestReview(service, api, prompt -> result(List.of(finding())));
        ObjectNode row = await(reviews.request("https://bitbucket.org/team/repo/pull-requests/1", 0, false)); api.loseResponse = true;
        String id = Json.required(row, "id"); assertThrows(IOException.class, () -> reviews.savePending(id));
        ((ObjectNode) api.comments.getFirst()).set("user", Json.obj("uuid", "other"));
        assertThrows(IllegalStateException.class, () -> reviews.savePending(id));
        assertEquals("delivery-unknown", Json.text(reviews.request("https://bitbucket.org/team/repo/pull-requests/1", 0, false), "status")); assertEquals(1, api.writes);
    }
    @Test void repliesOnlyToOthersOnOwnPrAndRechecksConversationBeforeSaving() throws Exception {
        api.author = "owner"; api.comments.add(Json.obj("id", 7, "created_on", "2026-11-06T00:00:00Z", "user", Json.obj("uuid", "other"), "content", Json.obj("raw", "왜 null을 반환하나요?")));
        AtomicInteger calls = new AtomicInteger(); reviews = new PullRequestReview(service, api, prompt -> { calls.incrementAndGet(); ObjectNode response = result(List.of()); response.put("reply", "현재 코드는 null을 반환합니다. 입력값을 반환하도록 수정해야 합니다.").put("replyConfidence", "high"); response.set("replyEvidence", Json.MAPPER.valueToTree(List.of(evidence()))); return response; });
        ObjectNode row = await(reviews.request("https://bitbucket.org/team/repo/pull-requests/1", 7, false)); assertEquals(2, calls.get()); assertFalse(Json.text(row, "reply").isBlank());
        api.comments.add(Json.obj("id", 8, "parent", Json.obj("id", 7), "created_on", "2026-11-06T01:00:00Z", "user", Json.obj("uuid", "owner")));
        assertEquals("stale", Json.text(reviews.savePending(Json.required(row, "id")), "status")); assertEquals(0, api.writes);
        api.author = "other";
        ObjectNode invalid = await(reviews.request("https://bitbucket.org/team/repo/pull-requests/2", 7, false)); assertEquals("failed", Json.text(invalid, "status"));
    }
}
