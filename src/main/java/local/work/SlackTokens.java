package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;

final class SlackTokens {
    @FunctionalInterface interface Refresh { JsonNode call(ObjectNode body) throws Exception; }
    private final Config config;
    private final Store store;
    private final Refresh refresh;
    private ObjectNode pending;
    SlackTokens(Config config, Store store, Refresh refresh) { this.config=config; this.store=store; this.refresh=refresh; }
    synchronized String access(Instant now) throws Exception {
        ObjectNode saved=store.get("secret", "slack-app-token");
        String basis=TranscriptImporter.hash(config.get("SLACK_APP_TOKEN", ""));
        if (saved!=null && !basis.equals(Json.text(saved, "configuredTokenHash"))) saved=null;
        String token=saved==null?config.get("SLACK_APP_TOKEN", ""):Json.text(saved, "accessToken");
        if (!token.startsWith("xoxe.xapp-")) return token;
        if (pending==null) {
            long expires=saved==null?Long.parseLong(config.get("SLACK_APP_TOKEN_EXPIRES_AT", "0")):saved.path("expiresAt").asLong();
            if (expires>now.plusSeconds(3600).toEpochMilli()) return token;
            String renewal=saved==null?config.get("SLACK_REFRESH_TOKEN", ""):Json.text(saved, "refreshToken");
            if (renewal.isBlank() || !config.has("SLACK_CLIENT_ID") || !config.has("SLACK_CLIENT_SECRET")) throw new IllegalStateException("Slack 토큰 갱신 설정을 확인하세요.");
            JsonNode result=refresh.call(Json.obj("client_id", config.get("SLACK_CLIENT_ID", ""), "client_secret", config.get("SLACK_CLIENT_SECRET", ""), "grant_type", "refresh_token", "refresh_token", renewal));
            if (!Json.text(result, "token_type").equals("app_level") || !Json.text(result, "access_token").startsWith("xoxe.xapp-") || Json.text(result, "refresh_token").isBlank() || result.path("expires_in").asLong()<=0) throw new IllegalStateException("Slack 갱신 응답을 확인하지 못했습니다.");
            pending=Json.obj("id", "slack-app-token", "configuredTokenHash", basis, "accessToken", Json.text(result, "access_token"), "refreshToken", Json.text(result, "refresh_token"), "expiresAt", now.plusSeconds(result.path("expires_in").asLong()).toEpochMilli());
        }
        store.put("secret", pending);
        String updated=Json.text(pending, "accessToken"); pending=null; return updated;
    }
}
