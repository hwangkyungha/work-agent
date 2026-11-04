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
    private final Config config;
    private final Store store;
    private final Agent agent;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile WebSocket socket;
    private volatile boolean closed;
    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile String lastError = "";
    SlackConnection(Config config, Store store, Agent agent) { this.config = config; this.store = store; this.agent = agent; }
    boolean configured() { return config.has("SLACK_APP_TOKEN") && config.has("SLACK_BOT_TOKEN") && config.has("SLACK_USER_ID"); }
    boolean connected() { return connected; }
    String lastError() { return lastError; }
    void start() {
        if (!configured()) return;
        maintenance.scheduleWithFixedDelay(() -> {
            if (!connected && !connecting && !closed) {
                try { connect(); } catch (Exception e) { connected = false; lastError = "Slack 연결 실패. 토큰과 Socket Mode 설정을 확인하세요."; }
            }
        }, 0, 20, TimeUnit.SECONDS);
        worker.submit(this::drain);
    }
    private void connect() throws Exception {
        connecting = true;
        try {
            JsonNode response = api("apps.connections.open", config.get("SLACK_APP_TOKEN", ""), Json.object());
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
        store.enqueue(id, Json.obj("channel", Json.text(event, "channel"), "text", Json.text(event, "text"), "thread", Json.text(event, "thread_ts")));
    }
    private void drain() {
        while (!closed) {
            for (ObjectNode event : store.pending()) {
                if (closed) return;
                JsonNode payload = event.path("payload");
                String id = Json.text(event, "id"), channel = Json.text(payload, "channel");
                try {
                    ObjectNode result = agent.chat("slack:" + channel, id, Json.required(payload, "text"));
                    send(channel, Json.text(result, "text"), Json.text(payload, "thread"));
                    store.finish(id);
                } catch (Exception e) {
                    try { send(channel, "요청을 완료하지 못했습니다. " + friendly(e), Json.text(payload, "thread")); store.finish(id); }
                    catch (Exception retry) { lastError = "Slack 응답 전송에 실패했습니다. 연결 후 다시 시도합니다."; }
                }
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
    void notifyOwner(String text) throws Exception {
        if (!configured()) return;
        JsonNode opened = api("conversations.open", config.get("SLACK_BOT_TOKEN", ""), Json.obj("users", config.get("SLACK_USER_ID", "")));
        send(Json.required(opened.path("channel"), "id"), text, "");
    }
    private void send(String channel, String text, String thread) throws Exception {
        for (int offset = 0; offset < text.length(); offset += 3500) {
            ObjectNode body = Json.obj("channel", channel, "text", text.substring(offset, Math.min(text.length(), offset + 3500)), "unfurl_links", false, "unfurl_media", false);
            if (!thread.isBlank()) body.put("thread_ts", thread);
            api("chat.postMessage", config.get("SLACK_BOT_TOKEN", ""), body);
        }
    }
    private JsonNode api(String method, String token, ObjectNode body) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("https://slack.com/api/" + method)).timeout(Duration.ofSeconds(30))
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
