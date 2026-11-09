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
import java.util.concurrent.*;
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
    ObjectNode result(List<ObjectNode> findings) { return Json.obj("summary", "검토 완료", "findings", findings, "reply", "", "replyKind", "fact", "relatedPaths", List.of(), "replyConfidence", "low", "replyEvidence", List.of(), "limitations", List.of()); }
    ObjectNode await(ObjectNode row) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        do { row = store.get("prReview", Json.required(row, "id")); if (!Set.of("queued", "running").contains(Json.text(row, "status"))) return row; Thread.sleep(10); } while (System.nanoTime() < end);
        fail("review did not finish"); return row;
    }
    @Test void requiresSecondPassExactEvidenceAndChangedAnchor() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ObjectNode wrong = finding(); wrong.put("line", 20);
        reviews = new PullRequestReview(service, api, prompt -> { assertTrue(prompt.contains("name: write-message")); return result(calls.incrementAndGet() == 1 ? List.of(finding(), wrong) : List.of(finding(), wrong)); });
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
        assertFalse(store.get("prReview", id).has("error"));
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
        ObjectNode row = await(reviews.request("https://bitbucket.org/team/repo/pull-requests/1", 7, false)); assertEquals(3, calls.get()); assertFalse(Json.text(row, "reply").isBlank());
        assertEquals("fact", Json.text(row, "replyKind"));
        api.comments.add(Json.obj("id", 8, "parent", Json.obj("id", 7), "created_on", "2026-11-06T01:00:00Z", "user", Json.obj("uuid", "owner")));
        assertEquals("stale", Json.text(reviews.savePending(Json.required(row, "id")), "status")); assertEquals(0, api.writes);
        api.author = "other";
        ObjectNode invalid = await(reviews.request("https://bitbucket.org/team/repo/pull-requests/2", 7, false)); assertEquals("failed", Json.text(invalid, "status"));
    }
    @Test void designReplyLoadsUnchangedCallerAndSpecAtThePrCommitBeforeVerification() throws Exception {
        String spec = "openspec/specs/test-generator-setup/spec.md", caller = "install.go";
        String specLine = "명시한 false를 보내고, 값이 없으면 서버가 거부할 때 필드 없이 다시 보낸다.";
        String callerLine = "return checkConnection(ctx, dep.check, out, checker)";
        api = new FakeApi(new Config(temporary.resolve("none"), Map.of())) {
            @Override JsonNode sourceDirectory(String repository, String commit, String directory, int depth) {
                assertEquals("team/repo", repository);
                assertEquals("source", commit);
                if (directory.equals("openspec/specs")) return Json.obj("values", List.of(Json.obj("type", "commit_file", "path", spec)));
                if (directory.isEmpty()) return Json.obj("values", List.of(Json.obj("type", "commit_file", "path", caller)));
                return Json.obj("values", List.of());
            }
            @Override String source(String repository, String commit, String path) throws Exception {
                if (path.equals(spec) || path.equals(caller)) {
                    assertEquals("source", commit);
                    return (path.equals(spec) ? specLine : callerLine) + "\n".repeat(30) + "FAR_UNRELATED_SOURCE";
                }
                return super.source(repository, commit, path);
            }
        };
        api.author = "owner";
        api.comments.add(Json.obj("id", 7, "user", Json.obj("uuid", "other"), "content", Json.obj("raw", "thinking_unsupported이면 model.thinking을 자동으로 지우면 어떨까요?")));
        AtomicInteger calls = new AtomicInteger();
        reviews = new PullRequestReview(service, api, prompt -> {
            ObjectNode response = result(List.of()).put("replyKind", "design");
            int pass = calls.incrementAndGet();
            if (pass == 1) {
                assertFalse(prompt.contains(caller));
                assertFalse(prompt.contains("return null;"));
                return response;
            }
            if (pass == 2) {
                assertTrue(prompt.contains(spec));
                assertTrue(prompt.contains(caller));
                assertFalse(prompt.contains("return null;"));
                assertFalse(prompt.contains(specLine));
                response.set("relatedPaths", Json.MAPPER.valueToTree(List.of(spec, caller, "../secret.md")));
                return response;
            }
            assertTrue(prompt.contains(specLine));
            assertTrue(prompt.contains(callerLine));
            assertEquals(pass == 3, prompt.contains("FAR_UNRELATED_SOURCE"));
            assertTrue(prompt.contains("채택 여부에 직접 답했는지") == (pass == 4));
            response.set("replyEvidence", Json.MAPPER.valueToTree(List.of(
                    Json.obj("path", spec, "side", "new", "line", 1, "quote", specLine),
                    Json.obj("path", caller, "side", "new", "line", 1, "quote", callerLine))));
            return response.put("reply", "값을 빼고 재시험하는 방안에 찬성합니다. 설정 삭제는 설치에 미치는 영향까지 확인한 뒤 결정하는 편이 좋겠습니다.").put("replyConfidence", "high");
        });
        ObjectNode row = await(reviews.request("https://bitbucket.org/team/repo/pull-requests/1", 7, false));
        assertEquals("ready", Json.text(row, "status"), Json.text(row, "error"));
        assertEquals("design", Json.text(row, "replyKind"));
        assertEquals(4, calls.get());
        assertEquals(2, row.path("replyEvidence").size());
        assertFalse(Json.text(row, "reply").isBlank());
        assertEquals(0, api.writes);
    }
    @Test void editedQuestionCannotReceiveThePreviousAnswersDraft() throws Exception {
        api.author = "owner";
        ObjectNode incoming = Json.obj("id", 7, "created_on", "01", "updated_on", "01", "user", Json.obj("uuid", "other"), "content", Json.obj("raw", "첫 질문")); api.comments.add(incoming);
        reviews = new PullRequestReview(service, api, prompt -> { ObjectNode response = result(List.of()); response.put("reply", "현재 코드는 null을 반환합니다.").put("replyConfidence", "high"); response.set("replyEvidence", Json.MAPPER.valueToTree(List.of(evidence()))); return response; });
        ObjectNode row = await(reviews.request("https://bitbucket.org/team/repo/pull-requests/1", 7, false)); String id = Json.required(row, "id");
        incoming.set("content", Json.obj("raw", "수정된 질문")); incoming.put("updated_on", "02");
        assertEquals("stale", Json.text(reviews.savePending(id), "status")); assertEquals(0, api.writes);
        ObjectNode next = await(reviews.request("https://bitbucket.org/team/repo/pull-requests/1", 7, false)); assertNotEquals(id, Json.text(next, "id"));
    }
    @Test void editingAndSendingCannotRestoreAnOldLocalDeliveryState() throws Exception {
        reviews = new PullRequestReview(service, api, prompt -> result(List.of(finding())));
        ObjectNode row = await(reviews.request("https://bitbucket.org/team/repo/pull-requests/1", 0, false)); String id = Json.required(row, "id");
        CountDownLatch editing = new CountDownLatch(1), release = new CountDownLatch(1), sending = new CountDownLatch(1);
        ObjectNode input = new ObjectNode(Json.MAPPER.getNodeFactory()) {
            @Override public JsonNode path(String field) {
                if (field.equals("text")) { editing.countDown(); try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("edit timed out"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
                return super.path(field);
            }
        };
        input.put("id", id).put("findingId", Json.text(row.path("findings").get(0), "id")).put("text", "수정한 댓글");
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<ObjectNode> edited = threads.submit(() -> reviews.edit(input)); assertTrue(editing.await(5, TimeUnit.SECONDS));
            Future<ObjectNode> saved = threads.submit(() -> { sending.countDown(); return reviews.savePending(id); }); assertTrue(sending.await(5, TimeUnit.SECONDS));
            try { saved.get(300, TimeUnit.MILLISECONDS); } catch (TimeoutException expected) { /* Sending waits for the active editor. */ }
            finally { release.countDown(); }
            edited.get(5, TimeUnit.SECONDS); saved.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        ObjectNode stored = store.get("prReview", id); assertEquals("saved", Json.text(stored, "status")); assertEquals("pending", Json.text(stored.path("findings").get(0), "delivery"));
        assertTrue(Json.text(api.comments.getFirst().path("content"), "raw").contains("수정한 댓글")); assertEquals(1, api.writes);
    }
    @Test void completionNotificationPreservesConcurrentDeliveryAndItsOwnFlag() throws Exception {
        reviews = new PullRequestReview(service, api, prompt -> result(List.of(finding())));
        ObjectNode row = await(reviews.request("https://bitbucket.org/team/repo/pull-requests/1", 0, false)); String id = Json.required(row, "id"); AtomicInteger messages = new AtomicInteger();
        try (ReviewMonitor monitor = new ReviewMonitor(service, api, reviews, new Config(temporary.resolve("none"), Map.of()), text -> { messages.incrementAndGet(); reviews.savePending(id); })) {
            monitor.notifyCompleted(); ObjectNode stored = store.get("prReview", id);
            assertEquals("saved", Json.text(stored, "status")); assertEquals("pending", Json.text(stored.path("findings").get(0), "delivery")); assertTrue(stored.path("notified").asBoolean());
            reviews.savePending(id); monitor.notifyCompleted(); assertEquals(1, messages.get());
        }
    }
    @Test void restartLeavesAnInterruptedSendUncertainWithoutPostingAgain() throws Exception {
        reviews = new PullRequestReview(service, api, prompt -> result(List.of(finding())));
        ObjectNode row = await(reviews.request("https://bitbucket.org/team/repo/pull-requests/1", 0, false)); String id = Json.required(row, "id");
        row.put("status", "saving"); ((ObjectNode) row.path("findings").get(0)).put("delivery", "posting"); store.put("prReview", row);
        reviews.resume(); assertEquals("delivery-unknown", Json.text(store.get("prReview", id), "status"));
        assertThrows(IllegalStateException.class, () -> reviews.savePending(id)); assertEquals(0, api.writes);
    }
    @Test void largeSpecificationsAreReviewedInBoundedBatchesWithoutLosingSourceLines() throws Exception {
        FakeApi large = new FakeApi(new Config(temporary.resolve("none"), Map.of())) {
            @Override String source(String repository, String commit, String path) throws Exception {
                if (path.endsWith(".md")) return ("자료를 읽고 코드와 비교합니다.\n".repeat(5000)) + path + "-END";
                return super.source(repository, commit, path);
            }
        };
        List<String> prompts = new ArrayList<>(); reviews = new PullRequestReview(service, large, prompt -> { prompts.add(prompt); return result(List.of()); });
        ObjectNode row = await(reviews.request("https://bitbucket.org/team/repo/pull-requests/1", 0, false));
        assertEquals("ready", Json.text(row, "status"), Json.text(row, "error"));
        assertTrue(prompts.stream().allMatch(prompt -> prompt.length() <= 140_000));
        for (String name : List.of("AGENTS.md", "CLAUDE.md", "README.md")) assertTrue(prompts.stream().anyMatch(prompt -> prompt.contains(name + "-END")));
        assertTrue(prompts.stream().anyMatch(prompt -> prompt.contains("1: return null;")));
        assertTrue(prompts.stream().anyMatch(prompt -> prompt.contains("1: return input;")));
    }
    @Test void consecutiveMultilineEvidenceMustMatchEveryActualSourceLine() throws Exception {
        FakeApi multiline = new FakeApi(new Config(temporary.resolve("none"), Map.of())) {
            @Override String source(String repository, String commit, String path) throws Exception {
                if (path.equals("foo.java")) return "return null;\nnext();\n";
                return super.source(repository, commit, path);
            }
        };
        ReviewContext context = new ReviewContext(multiline, BitbucketClient.PullRequest.parse("https://bitbucket.org/team/repo/pull-requests/1"));
        ObjectNode quote = evidence().put("quote", "return null;\nnext();"); assertTrue(context.validEvidence(quote));
        quote.put("quote", "return null;\ninvented();"); assertFalse(context.validEvidence(quote));
        quote.put("quote", "return null;\nnext();\nafterEnd();"); assertFalse(context.validEvidence(quote));
    }
    @Test void replyVerificationIncludesAnUncitedCallerWithoutItsWholeFile() throws Exception {
        ReviewContext context = new ReviewContext(api, BitbucketClient.PullRequest.parse("https://bitbucket.org/team/repo/pull-requests/1"));
        context.files.put("new:install.go", "return checkConnection(ctx)\n" + "\n".repeat(30) + "FAR_UNRELATED_SOURCE");
        String excerpts = Json.write(context.replyExcerpts(Json.MAPPER.valueToTree(List.of(evidence())),
                Set.of(), "변경이 설치에도 적용되나요?", "설치는 `checkConnection`을 호출합니다."));
        assertTrue(excerpts.contains("return checkConnection(ctx)"));
        assertFalse(excerpts.contains("FAR_UNRELATED_SOURCE"));
    }
}
