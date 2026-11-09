package local.work;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

final class SlackConnection implements AutoCloseable {
    private static final String WORKING = "hourglass_flowing_sand";
    private final Config config;
    private final Store store;
    private final Agent agent;
    private final SlackTokens tokens;
    private final URI apiRoot;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile WebSocket socket;
    private volatile boolean closed;
    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile boolean reactionScopeMissing;
    private volatile String lastError = "";
    SlackConnection(Config config, Store store, Agent agent) {
        this(config, store, agent, URI.create("https://slack.com/api/"));
    }
    SlackConnection(Config config, Store store, Agent agent, URI apiRoot) {
        this.config = config; this.store = store; this.agent = agent;
        this.apiRoot = apiRoot;
        tokens = new SlackTokens(config, store, body -> {
            String form = body.properties().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue().asText(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("https://slack.com/api/oauth.v2.access")).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode result = Json.read(response.body());
            if (response.statusCode() != 200 || !result.path("ok").asBoolean()) throw new IllegalStateException("Slack 토큰 갱신에 실패했습니다.");
            return result;
        });
    }
    boolean configured() { return config.has("SLACK_APP_TOKEN") && config.has("SLACK_BOT_TOKEN") && config.has("SLACK_USER_ID"); }
    boolean connected() { return connected; }
    String lastError() { return lastError; }
    void start() {
        if (!configured()) return;
        maintenance.scheduleWithFixedDelay(() -> {
            try { tokens.access(Instant.now()); } catch (Exception e) { lastError = "Slack 토큰 갱신 설정과 연결을 확인하세요."; return; }
            if (!connected && !connecting && !closed) {
                try { connect(); } catch (Exception e) { connected = false; lastError = "Slack 연결 실패. 토큰과 Socket Mode 설정을 확인하세요."; }
            }
        }, 0, 20, TimeUnit.SECONDS);
        maintenance.scheduleWithFixedDelay(() -> {
            for (ObjectNode progress : store.list("slackProgress")) settleProgress(Json.required(progress, "id"));
        }, 5, 10, TimeUnit.SECONDS);
        worker.submit(this::drain);
    }
    private void connect() throws Exception {
        connecting = true;
        try {
            JsonNode response = api("apps.connections.open", tokens.access(Instant.now()), Json.object());
            socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(20)).buildAsync(URI.create(Json.required(response, "url")), new WebSocket.Listener() {
                private final StringBuilder buffer = new StringBuilder();
                @Override public void onOpen(WebSocket ws) { connected = true; lastError = ""; ws.request(1); }
                @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                    buffer.append(data);
                    if (last) {
                        String text = buffer.toString(); buffer.setLength(0);
                        try { receive(ws, Json.read(text)); }
                        catch (RuntimeException e) { lastError = "Slack 수신 내용을 처리하지 못했습니다."; }
                    }
                    ws.request(1); return CompletableFuture.completedFuture(null);
                }
                @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) { connected = false; return CompletableFuture.completedFuture(null); }
                @Override public void onError(WebSocket ws, Throwable error) { connected = false; }
            }).get(30, TimeUnit.SECONDS);
        } finally { connecting = false; }
    }
    private void receive(WebSocket ws, JsonNode envelope) {
        if (envelope.hasNonNull("envelope_id")) ws.sendText(Json.write(Json.obj("envelope_id", Json.text(envelope, "envelope_id"))), true);
        if (Json.text(envelope, "type").equals("disconnect")) { connected = false; ws.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect"); return; }
        if (!Json.text(envelope, "type").equals("events_api")) return;
        JsonNode event = envelope.path("payload").path("event");
        if (!Json.text(event, "type").equals("message") || !Json.text(event, "channel_type").equals("im") || event.has("bot_id") || event.has("subtype")) return;
        if (!config.get("SLACK_USER_ID", "").equals(Json.text(event, "user"))) return;
        String id = Json.text(envelope.path("payload"), "event_id");
        if (id.isBlank() || Json.text(event, "text").isBlank()) return;
        store.enqueue(id, Json.obj("channel", Json.text(event, "channel"), "text", Json.text(event, "text"), "thread", Json.text(event, "thread_ts"), "ts", Json.text(event, "ts")));
    }
    private void drain() {
        while (!closed) {
            for (ObjectNode event : store.pending()) {
                if (closed) return;
                handleEvent(event);
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
    void handleEvent(ObjectNode event) {
        JsonNode payload = event.path("payload");
        String id = Json.text(event, "id"), channel = Json.text(payload, "channel"), thread = Json.text(payload, "thread");
        reaction("add", payload, WORKING);
        ObjectNode result;
        try {
            ObjectNode reviewThread = thread.isBlank() ? null : store.get("prSlackThread", channel + ":" + thread);
            String conversation = "slack:" + channel + (reviewThread == null ? "" : ":" + thread);
            result = agent.chat(conversation, id, Json.required(payload, "text"), reviewThread);
        } catch (Exception e) {
            try {
                send(channel, "요청을 완료하지 못했습니다. " + friendly(e), thread);
                rememberCompletion(id, payload, Json.object(), true);
            } catch (Exception retry) { lastError = "Slack 응답 전송에 실패했습니다. 연결 후 다시 시도합니다."; }
            return;
        }
        try {
            send(channel, Json.text(result, "text"), thread);
            rememberCompletion(id, payload, result, false);
        } catch (Exception e) { lastError = "Slack 응답 전송에 실패했습니다. 연결 후 다시 시도합니다."; }
    }
    private void rememberCompletion(String id, JsonNode payload, ObjectNode response, boolean failed) {
        Set<String> reviews = new LinkedHashSet<>();
        String scanSince = "";
        for (JsonNode item : response.path("results")) {
            String type = Json.text(item, "type");
            JsonNode result = item.path("result");
            if (type.equals("pr_review") || type.equals("pr_reply")) reviews.add(Json.required(result, "id"));
            if (type.equals("review_inbox") && result.path("scanning").asBoolean()) scanSince = Json.text(result, "requestedAt");
        }
        ObjectNode progress = Json.obj("id", id, "channel", Json.text(payload, "channel"), "ts", Json.text(payload, "ts"),
                "reviews", reviews, "scanSince", scanSince, "failed", failed);
        store.transaction(() -> {
            if (!Json.text(progress, "ts").isBlank() && !reactionScopeMissing) store.put("slackProgress", progress);
            store.finish(id);
        });
        settleProgress(id);
    }
    synchronized void settleProgress(String id) {
        if (reactionScopeMissing) return;
        ObjectNode progress = store.get("slackProgress", id);
        if (progress == null) return;
        boolean failed = progress.path("failed").asBoolean();
        for (JsonNode reviewId : progress.path("reviews")) {
            ObjectNode review = store.get("prReview", reviewId.asText());
            if (review != null && PullRequestReview.inProgress(review)) return;
            failed |= review == null || !PullRequestReview.succeeded(review);
        }
        String since = Json.text(progress, "scanSince");
        if (!since.isBlank()) {
            ObjectNode scan = store.get("reviewMonitor", "status");
            if (scan == null) return;
            Instant start = Instant.parse(since);
            boolean completed = !Json.text(scan, "at").isBlank() && !Instant.parse(Json.text(scan, "at")).isBefore(start);
            boolean scanFailed = !Json.text(scan, "attemptedAt").isBlank() && !Instant.parse(Json.text(scan, "attemptedAt")).isBefore(start);
            if (!completed && !scanFailed) return;
            failed |= scanFailed && !completed;
        }
        if (reaction("add", progress, failed ? "x" : "white_check_mark") && reaction("remove", progress, WORKING)) store.delete("slackProgress", id);
    }
    private boolean reaction(String operation, JsonNode message, String name) {
        String timestamp = Json.text(message, "ts");
        if (timestamp.isBlank() || reactionScopeMissing) return false;
        try {
            api("reactions." + operation, config.get("SLACK_BOT_TOKEN", ""), Json.obj("channel", Json.text(message, "channel"), "timestamp", timestamp, "name", name));
            return true;
        } catch (Exception e) {
            String error = e.getMessage();
            if ("Slack 요청 실패: missing_scope".equals(error)) reactionScopeMissing = true;
            return Set.of("Slack 요청 실패: already_reacted", "Slack 요청 실패: no_reaction",
                    "Slack 요청 실패: message_not_found", "Slack 요청 실패: channel_not_found").contains(Objects.toString(error, ""));
        }
    }
    void notifyOwner(String text) throws Exception {
        if (!configured()) return;
        send(ownerChannel(), text, "");
    }
    void notifyReview(String text, ObjectNode review) throws Exception {
        if (!configured()) return;
        String channel = ownerChannel();
        String timestamp = send(channel, text + "\n이 PR에 관한 질문은 이 메시지의 스레드에 남겨 주세요.", "");
        if (!timestamp.isBlank()) store.put("prSlackThread", Json.obj("id", channel + ":" + timestamp,
                "url", Json.text(review, "url"), "sourceCommit", Json.text(review, "sourceCommit"),
                "reviewId", Json.text(review, "id")));
    }
    private String ownerChannel() throws Exception {
        JsonNode opened = api("conversations.open", config.get("SLACK_BOT_TOKEN", ""), Json.obj("users", config.get("SLACK_USER_ID", "")));
        return Json.required(opened.path("channel"), "id");
    }
    private String send(String channel, String text, String thread) throws Exception {
        String first = "";
        for (int offset = 0; offset < text.length(); offset += 3500) {
            ObjectNode body = Json.obj("channel", channel, "text", text.substring(offset, Math.min(text.length(), offset + 3500)), "unfurl_links", false, "unfurl_media", false);
            if (!thread.isBlank()) body.put("thread_ts", thread);
            JsonNode result = api("chat.postMessage", config.get("SLACK_BOT_TOKEN", ""), body);
            if (offset == 0) first = Json.text(result, "ts");
        }
        return first;
    }
    private JsonNode api(String method, String token, ObjectNode body) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(apiRoot.resolve(method)).timeout(Duration.ofSeconds(method.startsWith("reactions.") ? 5 : 30))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IllegalStateException("Slack 요청 실패(HTTP " + response.statusCode() + ")");
        JsonNode result = Json.read(response.body());
        if (!result.path("ok").asBoolean()) throw new IllegalStateException("Slack 요청 실패: " + Json.text(result, "error"));
        return result;
    }
    static String friendly(Exception e) {
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof JiraClient.IOExceptionBase) return e.getMessage();
        return "연결 상태를 확인한 뒤 다시 요청하세요.";
    }
    @Override public void close() {
        closed = true; maintenance.shutdownNow(); worker.shutdownNow();
        if (socket != null) socket.abort();
    }
}
