package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SlackProgressTest {
    @TempDir Path temporary;
    private HttpServer server;
    private Config config;
    private Store store;
    private WorkService service;
    private URI apiRoot;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> messages = new CopyOnWriteArrayList<>();

    @BeforeEach void open() throws Exception {
        config = new Config(temporary.resolve("none"), Map.of("SLACK_BOT_TOKEN", "test-token", "SLACK_APP_TOKEN", "test-app", "SLACK_USER_ID", "owner"));
        store = new Store(temporary.resolve("test.db"));
        service = new WorkService(store, new KoreaTime(Clock.systemUTC()), new JiraClient(config));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", exchange -> {
            JsonNode body = Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String method = exchange.getRequestURI().getPath().substring(5);
            if (method.equals("chat.postMessage")) messages.add(Json.text(body, "text"));
            calls.add(method + ":" + Json.text(body, "name") + ":" + Json.text(body, "timestamp"));
            String result = switch (method) {
                case "conversations.open" -> "{\"ok\":true,\"channel\":{\"id\":\"D123\"}}";
                case "chat.postMessage" -> "{\"ok\":true,\"ts\":\"parent\"}";
                default -> "{\"ok\":true}";
            };
            byte[] response = result.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        apiRoot = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/");
    }
    @AfterEach void close() throws Exception {
        server.stop(0);
        store.close();
    }
    private ObjectNode event(String text) {
        ObjectNode payload = Json.obj("channel", "D123", "text", text, "thread", "parent", "ts", "123.456");
        store.enqueue("event", payload);
        return store.pending().getFirst();
    }
    @Test void completedRequestsReplaceHourglassOnTheOriginalMessage() throws Exception {
        Agent agent = new Agent(service, new Reports(service), prompt -> Json.obj("reply", "확인했습니다.", "actions", List.of()), kind -> Json.object(), "호박", "호박아");
        try (SlackConnection slack = new SlackConnection(config, store, agent, apiRoot)) { slack.handleEvent(event("안녕")); }
        assertEquals(List.of("reactions.add:hourglass_flowing_sand:123.456", "chat.postMessage::",
                "reactions.add:white_check_mark:123.456", "reactions.remove:hourglass_flowing_sand:123.456"), calls);
        assertTrue(store.pending().isEmpty());
    }
    @Test void asyncReviewKeepsHourglassAndUsesOneAcknowledgementUntilReviewFinishes() throws Exception {
        CountDownLatch modelStarted = new CountDownLatch(1), release = new CountDownLatch(1);
        PullRequestReviewTest.FakeApi api = new PullRequestReviewTest.FakeApi(config);
        String url = "https://bitbucket.org/team/repo/pull-requests/111";
        try (PullRequestReview reviews = new PullRequestReview(service, api, prompt -> {
            modelStarted.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Review timed out.");
            return Json.obj("findings", List.of(), "limitations", List.of(), "summary", "");
        })) {
            Agent agent = new Agent(service, new Reports(service), prompt -> Json.obj("reply", "중복 접수 문구",
                    "actions", List.of(Json.obj("type", "pr_review", "payload", Json.write(Json.obj("url", url))))),
                    kind -> Json.object(), "호박", "호박아", null, reviews, null);
            try (SlackConnection slack = new SlackConnection(config, store, agent, apiRoot)) {
                slack.handleEvent(event(url + " 리뷰해줘"));
                assertTrue(modelStarted.await(5, TimeUnit.SECONDS));
                assertEquals(List.of("PR #111 검토를 요청했습니다.\n" + url), messages);
                assertEquals(List.of("reactions.add:hourglass_flowing_sand:123.456", "chat.postMessage::"), calls);
                assertNotNull(store.get("slackProgress", "event"));
            } finally { release.countDown(); }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (PullRequestReview.inProgress(store.list("prReview").getFirst()) && System.nanoTime() < deadline) Thread.sleep(10);
            ObjectNode row = store.list("prReview").getFirst();
            assertEquals("ready", Json.text(row, "status"));
            assertTrue(PullRequestReview.resultMessage(row).contains("남길 지적이 없었습니다."));
            try (SlackConnection restarted = new SlackConnection(config, store, agent, apiRoot)) { restarted.settleProgress("event"); }
            assertEquals(List.of("reactions.add:hourglass_flowing_sand:123.456", "chat.postMessage::",
                    "reactions.add:white_check_mark:123.456", "reactions.remove:hourglass_flowing_sand:123.456"), calls);
            assertNull(store.get("slackProgress", "event"));
        }
    }
    @Test void failedRequestsUseXInsteadOfCheck() throws Exception {
        Agent agent = new Agent(service, new Reports(service), prompt -> { throw new IllegalStateException("테스트 실패"); }, kind -> Json.object(), "호박", "호박아");
        try (SlackConnection slack = new SlackConnection(config, store, agent, apiRoot)) { slack.handleEvent(event("처리해줘")); }
        assertEquals(List.of("reactions.add:hourglass_flowing_sand:123.456", "chat.postMessage::",
                "reactions.add:x:123.456", "reactions.remove:hourglass_flowing_sand:123.456"), calls);
    }
    @Test void reviewNoticeThreadReusesPinnedSourcesAndAnswersWithoutPostingToBitbucket() throws Exception {
        var sourceReads = new java.util.concurrent.atomic.AtomicInteger();
        PullRequestReviewTest.FakeApi api = new PullRequestReviewTest.FakeApi(config) {
            @Override String source(String repo, String commit, String path) throws Exception {
                if (path.equals("foo.java")) sourceReads.incrementAndGet();
                return super.source(repo, commit, path);
            }
        };
        String url = "https://bitbucket.org/team/repo/pull-requests/111";
        try (PullRequestReview reviews = new PullRequestReview(service, api, prompt -> {
            if (!prompt.contains("현재 질문")) return Json.obj("findings", List.of(), "limitations", List.of());
            assertTrue(prompt.contains("1: return null;"));
            return Json.obj("reply", "현재 null을 반환합니다.", "replyEvidence",
                    List.of(Json.obj("path", "foo.java", "side", "new", "line", 1, "quote", "return null;")));
        })) {
            var review = reviews.request(url, 0, false);
            String reviewId = Json.required(review, "id");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (PullRequestReview.inProgress(store.get("prReview", reviewId)) && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("ready", Json.text(store.get("prReview", reviewId), "status"));
            Agent agent = new Agent(service, new Reports(service), prompt -> { throw new AssertionError("Thread should route without classification."); },
                    kind -> Json.object(), "호박", "호박아", null, reviews, null);
            int readsAfterReview = sourceReads.get();
            try (SlackConnection slack = new SlackConnection(config, store, agent, apiRoot)) {
                slack.notifyReview("검토를 마쳤습니다.", review);
                assertEquals(url, Json.text(store.get("prSlackThread", "D123:parent"), "url"));
            }
            try (SlackConnection restarted = new SlackConnection(config, store, agent, apiRoot)) {
                restarted.handleEvent(event("foo.java에서 뭘 반환해?"));
            }
            assertTrue(messages.getLast().contains("현재 null을 반환합니다."));
            assertTrue(messages.getLast().contains("foo.java:1"));
            assertEquals(readsAfterReview, sourceReads.get());
            assertEquals(0, api.writes);
        }
    }
}
