package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ReviewMonitorTest {
    @TempDir Path temporary;
    Store store; PullRequestReview reviews; ReviewMonitor monitor;
    @AfterEach void close() throws Exception {
        if (monitor != null) monitor.close(); if (reviews != null) reviews.close();
        if (store != null) store.close();
    }
    @Test void autoScanRunsOnTheHourAndHalfHourDuringKoreanDaytime() {
        Map<String, String> slots = Map.of(
                "2026-11-09T07:59:59", "2026-11-09T08:00:00",
                "2026-11-09T08:00:00", "2026-11-09T08:30:00",
                "2026-11-09T08:29:59", "2026-11-09T08:30:00",
                "2026-11-09T08:30:00", "2026-11-09T09:00:00",
                "2026-11-09T16:00:00", "2026-11-09T16:30:00",
                "2026-11-09T16:30:00", "2026-11-09T17:00:00",
                "2026-11-09T19:29:59", "2026-11-09T19:30:00",
                "2026-11-09T19:30:00", "2026-11-10T08:00:00",
                "2026-11-09T23:59:59", "2026-11-10T08:00:00",
                "2026-11-07T07:00:00", "2026-11-07T08:00:00");
        slots.forEach((now, next) -> assertEquals(
                ZonedDateTime.parse(next + "+09:00[Asia/Seoul]"),
                ReviewMonitor.nextScanTime(ZonedDateTime.parse(now + "+09:00[Asia/Seoul]"))));
    }
    @Test void manualRefreshStillWorksOutsideScheduledHours() throws Exception {
        Config config = new Config(temporary.resolve("none"), Map.of("BITBUCKET_WORKSPACES", "example"));
        store = new Store(temporary.resolve("test.db"));
        CountDownLatch queried = new CountDownLatch(1);
        PullRequestReviewTest.FakeApi api = new PullRequestReviewTest.FakeApi(config) {
            @Override List<JsonNode> pages(String path) {
                if (path.contains("/permissions/repositories")) queried.countDown();
                return List.of();
            }
        };
        ZonedDateTime evening = ZonedDateTime.parse("2026-11-09T20:00:00+09:00[Asia/Seoul]");
        WorkService service = new WorkService(store, new KoreaTime(Clock.fixed(evening.toInstant(), KoreaTime.ZONE)), new JiraClient(config));
        reviews = new PullRequestReview(service, api, prompt -> Json.object());
        monitor = new ReviewMonitor(service, api, reviews, config, text -> {});
        monitor.start();
        monitor.refresh();
        assertTrue(queried.await(5, TimeUnit.SECONDS));
    }
    @Test void scansMultipleWorkspacesAndRepositoriesWithoutRepositoryAllowlist() throws Exception {
        Config config = new Config(temporary.resolve("none"), Map.of()); store = new Store(temporary.resolve("test.db"));
        List<String> requests = new ArrayList<>();
        PullRequestReviewTest.FakeApi api = new PullRequestReviewTest.FakeApi(config) {
            @Override List<JsonNode> pages(String path) {
                requests.add(path);
                if (path.startsWith("/user/workspaces?")) return List.of(Json.obj("workspace", Json.obj("slug", "alpha")), Json.obj("workspace", Json.obj("slug", "beta")));
                if (path.contains("/permissions/repositories")) { String workspace = path.contains("/alpha/") ? "alpha" : "beta"; return List.of(Json.obj("repository", Json.obj("full_name", workspace + "/one")), Json.obj("repository", Json.obj("full_name", workspace + "/two"))); }
                if (path.contains("/pullrequests?")) { assertTrue(path.contains("fields=%2Bvalues.reviewers")); assertTrue(path.contains("pagelen=50")); return List.of(pullRequest(new PullRequest("any", "any", path.contains("/two/") ? 2 : 1))); }
                return super.pages(path);
            }
            @Override JsonNode pullRequest(PullRequest pr) { ObjectNode metadata = (ObjectNode) super.pullRequest(pr); metadata.set("author", Json.obj("uuid", pr.number() == 2 ? "owner" : "other")); return metadata; }
            @Override List<JsonNode> comments(PullRequest pr) { return pr.number() == 2 ? List.of(comment(7, 0, "other", "01", false)) : List.of(); }
        };
        WorkService service = new WorkService(store, new KoreaTime(Clock.systemUTC()), new JiraClient(config));
        var modelCalls = new java.util.concurrent.atomic.AtomicInteger();
        reviews = new PullRequestReview(service, api, prompt -> {
            modelCalls.incrementAndGet();
            return Json.obj("findings", List.of(), "reply", "현재 null을 반환합니다.", "replyKind", "fact", "replyConfidence", "high",
                    "replyEvidence", List.of(Json.obj("path", "foo.java", "side", "new", "line", 1, "quote", "return null;")),
                    "summary", "확인했습니다.", "limitations", List.of());
        });
        monitor = new ReviewMonitor(service, api, reviews, config, text -> fail("no notification during scan"));
        ObjectNode status = monitor.scan(); assertEquals(4, status.path("repositoryCount").asInt()); assertEquals(4, status.path("items").size());
        assertEquals(2, status.path("items").findValuesAsText("kind").stream().filter(kind -> kind.equals("review")).count());
        assertEquals(2, status.path("items").findValuesAsText("kind").stream().filter(kind -> kind.equals("reply")).count());
        assertTrue(requests.stream().anyMatch(path -> path.contains("/beta/two/")));
        waitForJobs();
        assertTrue(modelCalls.get() > 0);
        assertEquals(2, store.list("prReview").stream().filter(row -> Json.text(row, "status").equals("saved")).count());
        assertEquals(2, api.writes);
        int firstScanCalls = modelCalls.get();
        monitor.scan();
        waitForJobs();
        assertEquals(firstScanCalls, modelCalls.get());
        assertEquals(2, api.writes);
    }
    @Test void missingWorkspaceScopeUsesExplicitFallbackWithVisibleWarning() throws Exception {
        Config config = new Config(temporary.resolve("none"), Map.of("BITBUCKET_WORKSPACES", "example")); store = new Store(temporary.resolve("test.db"));
        PullRequestReviewTest.FakeApi api = new PullRequestReviewTest.FakeApi(config) {
            @Override List<JsonNode> pages(String path) { return List.of(); }
        };
        BitbucketClient denied = new BitbucketClient(config) {
            @Override JsonNode get(String path) { return Json.obj("uuid", "owner"); }
            @Override List<JsonNode> pages(String path) throws Exception { if (path.startsWith("/user/workspaces?")) throw new Rejected(403); assertTrue(path.contains("/example/")); return List.of(); }
        };
        WorkService service = new WorkService(store, new KoreaTime(Clock.systemUTC()), new JiraClient(config)); reviews = new PullRequestReview(service, api, prompt -> Json.object());
        monitor = new ReviewMonitor(service, denied, reviews, config, text -> {}); ObjectNode status = monitor.scan();
        assertEquals("example", status.path("workspaces").get(0).asText()); assertTrue(status.path("warnings").get(0).asText().contains("read:workspace:bitbucket"));
    }
    static ObjectNode comment(int id, int parent, String owner, String time, boolean pending) {
        ObjectNode value = Json.obj("id", id, "created_on", time, "user", Json.obj("uuid", owner), "pending", pending); if (parent > 0) value.set("parent", Json.obj("id", parent)); return value;
    }
    @Test void answersLatestIncomingOnlyAndSkipsAnsweredPendingAndDeletedThreads() {
        ObjectNode deleted = comment(7, 0, "other", "07", false); deleted.put("deleted", true);
        List<JsonNode> comments = List.of(comment(1, 0, "other", "01", false), comment(2, 1, "owner", "02", false), comment(3, 0, "other", "03", false), comment(4, 3, "other", "04", false), comment(5, 0, "other", "05", false), comment(6, 5, "owner", "06", true), deleted);
        assertEquals(List.of(4L), ReviewMonitor.unanswered(comments, "owner").stream().map(comment -> comment.path("id").asLong()).toList());
        ((ObjectNode) comments.get(2)).set("resolution", Json.obj("type", "comment_resolution"));
        assertTrue(ReviewMonitor.unanswered(comments, "owner").isEmpty());
    }
    private void waitForJobs() throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (store.list("prReview").stream().anyMatch(PullRequestReview::inProgress) && System.nanoTime() < end) Thread.sleep(10);
        assertFalse(store.list("prReview").stream().anyMatch(PullRequestReview::inProgress));
    }
    @Test void validationRunsAndAlreadyNotifiedHeadsDoNotSendCompletionMessages() throws Exception {
        Config config = new Config(temporary.resolve("none"), Map.of());
        store = new Store(temporary.resolve("test.db"));
        WorkService service = new WorkService(store, new KoreaTime(Clock.systemUTC()), new JiraClient(config));
        PullRequestReviewTest.FakeApi api = new PullRequestReviewTest.FakeApi(config);
        reviews = new PullRequestReview(service, api, prompt -> Json.object());
        List<String> sent = new ArrayList<>();
        monitor = new ReviewMonitor(service, api, reviews, config, text -> sent.add(text));
        String url = "https://bitbucket.org/team/repo/pull-requests/1";
        store.put("prReview", Json.obj("id", "old", "url", url, "sourceCommit", "head", "status", "ready", "notified", true));
        store.put("prReview", Json.obj("id", "duplicate", "url", url, "sourceCommit", "head", "status", "ready"));
        store.put("prReview", Json.obj("id", "validation", "url", url, "sourceCommit", "test-head", "status", "ready", "validation", true));
        monitor.notifyCompleted();
        assertTrue(sent.isEmpty());
        store.put("prReview", Json.obj("id", "next", "url", url, "sourceCommit", "next-head", "status", "ready"));
        monitor.notifyCompleted();
        monitor.notifyCompleted();
        assertEquals(1, sent.size());
    }

    @Test void approvedPrSkipsAutomaticReviewAndQueuedNoticeButAllowsManualReview() throws Exception {
        Config config = new Config(temporary.resolve("none"), Map.of());
        store = new Store(temporary.resolve("test.db"));
        WorkService service = new WorkService(store, new KoreaTime(Clock.systemUTC()), new JiraClient(config));
        var approved = new java.util.concurrent.atomic.AtomicBoolean(true);
        var api = new PullRequestReviewTest.FakeApi(config) {
            @Override JsonNode pullRequest(PullRequest pr) {
                return ((ObjectNode) super.pullRequest(pr)).set("participants",
                        Json.MAPPER.createArrayNode().add(Json.obj("user", Json.obj("uuid", "owner"), "approved", approved.get())));
            }
        };
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        reviews = new PullRequestReview(service, api, prompt -> {
            calls.incrementAndGet();
            return Json.obj("summary", "검토 완료", "findings", List.of(), "limitations", List.of());
        });
        var sent = new ArrayList<String>();
        monitor = new ReviewMonitor(service, api, reviews, config, text -> sent.add(text));
        String url = "https://bitbucket.org/team/repo/pull-requests/1";
        assertNull(reviews.requestAutomaticReview(url, "owner"));
        assertTrue(store.list("prReview").isEmpty());
        store.put("prReview", Json.obj("id", "queued", "url", url, "sourceCommit", "source", "status", "queued", "automatic", true));
        reviews.run("queued");
        assertEquals("skipped-approved", Json.text(store.get("prReview", "queued"), "status"));
        assertEquals(0, calls.get());
        store.put("prReview", Json.obj("id", "finished", "url", url, "sourceCommit", "older", "status", "ready", "automatic", true));
        monitor.notifyCompleted();
        assertTrue(sent.isEmpty());
        assertTrue(store.get("prReview", "finished").path("approvalSuppressed").asBoolean());
        assertTrue(store.list("prReviewNotice").isEmpty());
        assertNotNull(reviews.request(url, 0, false));
        waitForJobs();
        assertTrue(calls.get() > 0);
        monitor.notifyCompleted();
        assertEquals(1, sent.size());
        String other = "https://bitbucket.org/team/repo/pull-requests/2";
        store.put("prReview", Json.obj("id", "skipped", "url", other, "sourceCommit", "source", "status", "skipped-approved", "automatic", true));
        approved.set(false);
        assertNotEquals("skipped", Json.text(reviews.requestAutomaticReview(other, "owner"), "id"));
        waitForJobs();
    }

    @Test void aRebasedHeadUsesTheRevisionBeforeTheOwnersApproval() throws Exception {
        Config config = new Config(temporary.resolve("none"), Map.of());
        store = new Store(temporary.resolve("test.db"));
        var api = new PullRequestReviewTest.FakeApi(config) {
            ObjectNode snapshot(String at, String head) {
                ObjectNode pr = (ObjectNode)super.pullRequest(new PullRequest("team", "repo", 1));
                ((ObjectNode) pr.path("source").path("commit")).put("hash", head);
                return pr.put("date", at);
            }
            @Override JsonNode get(String path) {
                if (!path.contains("/activity?")) return super.get(path);
                return Json.obj("values", List.of(
                        Json.obj("update", snapshot("2026-11-26T11:30:00Z", "rebased-head")),
                        Json.obj("approval", Json.obj("user", Json.obj("uuid", "owner"), "date", "2026-11-26T11:00:00Z")),
                        Json.obj("update", snapshot("2026-11-26T10:00:00Z", "approved-head"))));
            }
        };
        PullRequestReview.Model comparator = new PullRequestReview.Model() {
            @Override public JsonNode complete(String prompt) { throw new AssertionError("Approval uses Git."); }
            @Override public boolean sameApprovedChange(ObjectNode approved, ObjectNode current, JsonNode metadata) {
                assertEquals("approved-head", Json.text(approved, "sourceCommit"));
                return Json.text(current, "sourceCommit").equals("rebased-head");
            }
        };
        BitbucketClient.PullRequest pr = new BitbucketClient.PullRequest("team", "repo", 1);
        ObjectNode metadata = api.snapshot("2026-11-26T11:30:00Z", "rebased-head");
        metadata.set("participants", Json.MAPPER.createArrayNode().add(Json.obj("user", Json.obj("uuid", "owner"), "approved", false)));
        ReviewApprovals approvals = new ReviewApprovals(store, api, comparator);
        assertTrue(approvals.excludes(pr, metadata, "owner"));
        assertFalse(approvals.excludes(pr, metadata, "different-user"));
        ((ObjectNode) metadata.path("source").path("commit")).put("hash", "changed-head");
        assertFalse(approvals.excludes(pr, metadata, "owner"));
    }
}
