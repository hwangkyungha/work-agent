package local.work;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

class JiraClient {
    static final class Rejected extends IOExceptionBase {
        Rejected(String message) { super(message); }
    }
    static class IOExceptionBase extends java.io.IOException {
        IOExceptionBase(String message) { super(message); }
    }
    private final Config config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    JiraClient(Config config) { this.config = config; }
    private String token() {
        String variable = config.get("JIRA_TOKEN_ENV", "");
        return variable.isBlank() ? config.get("JIRA_TOKEN", "") : config.environment(variable);
    }
    boolean configured() { return config.has("JIRA_URL") && !token().isBlank(); }
    boolean cloud() { return config.get("JIRA_KIND", config.get("JIRA_URL", "").contains("atlassian.net") ? "cloud" : "server").equals("cloud"); }
    private String root() { return config.get("JIRA_URL", "").replaceAll("/+$", "") + "/rest/api/" + (cloud() ? "3" : "2"); }
    private String auth() {
        if (config.has("JIRA_EMAIL")) return "Basic " + Base64.getEncoder().encodeToString((config.get("JIRA_EMAIL", "") + ":" + token()).getBytes(StandardCharsets.UTF_8));
        return "Bearer " + token();
    }
    JsonNode request(String method, String path, JsonNode body) throws Exception {
        if (!configured()) throw new IllegalArgumentException("Jira 연결 정보를 설정하세요.");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(root() + path)).timeout(Duration.ofSeconds(40))
                .header("Authorization", auth()).header("Accept", "application/json");
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String message = "Jira 요청에 실패했습니다(HTTP " + status + "). 주소, 인증 정보와 이슈 권한을 확인하세요.";
            if (status >= 400 && status < 500 && status != 408) throw new Rejected(message);
            throw new IOExceptionBase(message);
        }
        return Json.read(response.body());
    }
    String addWorklog(LocalDate date, JsonNode item) throws Exception {
        String comment = Json.text(item, "comment") + "\n[" + Json.text(item, "marker") + "]";
        JsonNode content = cloud() ? Json.obj("type", "doc", "version", 1, "content", List.of(Json.obj("type", "paragraph", "content", List.of(Json.obj("type", "text", "text", comment))))) : Json.MAPPER.valueToTree(comment);
        JsonNode body = Json.obj("started", KoreaTime.jiraStarted(date), "timeSpentSeconds", item.path("seconds").asInt(), "comment", content);
        JsonNode result = request("POST", "/issue/" + WorkService.issue(Json.required(item, "jiraKey")) + "/worklog?adjustEstimate=leave", body);
        return Json.required(result, "id");
    }
    List<JsonNode> worklogs(String issue) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        int offset = 0;
        while (true) {
            JsonNode result = request("GET", "/issue/" + WorkService.issue(issue) + "/worklog?startAt=" + offset + "&maxResults=100", null);
            JsonNode page = result.path("worklogs");
            for (JsonNode row : page) rows.add(row);
            offset += page.size();
            if (page.isEmpty() || offset >= result.path("total").asInt(offset)) return rows;
        }
    }
    String findMarker(String issue, String marker) throws Exception {
        for (JsonNode log : worklogs(issue)) if (log.path("comment").toString().contains(marker)) return Json.required(log, "id");
        return "";
    }
    List<JsonNode> search(String jql) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        int offset = 0;
        String token = "";
        while (true) {
            String path = cloud() ? "/search/jql?jql=" + encode(jql) + "&maxResults=100&fields=summary,status,assignee,updated,duedate,project,fixVersions,parent" + (token.isBlank() ? "" : "&nextPageToken=" + encode(token))
                    : "/search?jql=" + encode(jql) + "&startAt=" + offset + "&maxResults=100&fields=summary,status,assignee,updated,duedate,project,fixVersions,parent";
            JsonNode result = request("GET", path, null);
            JsonNode page = result.path("issues");
            for (JsonNode issue : page) rows.add(issue);
            offset += page.size();
            if (cloud()) {
                token = Json.text(result, "nextPageToken");
                if (page.isEmpty() || token.isBlank() || result.path("isLast").asBoolean(false)) return rows;
            } else if (page.isEmpty() || offset >= result.path("total").asInt(offset)) return rows;
        }
    }
    void syncIssues(WorkService service) throws Exception {
        List<JsonNode> issues = search(config.get("JIRA_JQL", "assignee = currentUser() AND (resolution = Unresolved OR updated >= -14d) ORDER BY updated DESC"));
        List<ObjectNode> snapshots = new ArrayList<>();
        for (JsonNode issue : issues) snapshots.add(summarize(issue));
        service.store.transaction(() -> {
            for (ObjectNode row : snapshots) {
                service.store.put("issue", row);
                String key = Json.text(row, "id");
                ObjectNode existing = service.store.get("task", "jira:" + key);
                if (existing == null && service.store.list("task").stream().noneMatch(n -> key.equals(Json.text(n, "jiraKey")))) {
                    service.store.put("task", Json.obj("id", "jira:" + key, "title", Json.text(row, "title"), "jiraKey", key,
                            "product", Json.text(row, "product"), "status", Json.text(row, "status"), "due", Json.text(row, "due"), "important", false, "urgent", false));
                } else if (existing != null) {
                    for (String field : List.of("title", "status", "due", "product")) existing.set(field, row.get(field));
                    service.store.put("task", existing);
                }
            }
            service.store.put("sync", Json.obj("id", "jira", "at", service.time.instant().toString(), "count", snapshots.size(), "ok", true));
        });
    }
    void syncTeam(WorkService service) throws Exception {
        LocalDate end = service.time.today(), start = service.time.monday(end);
        for (ObjectNode member : service.store.list("member")) {
            String user = Json.required(member, "jiraUser");
            String quoted = "\"" + user.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            List<JsonNode> issues = search("(assignee = " + quoted + " OR worklogAuthor = " + quoted + ") AND (resolution = Unresolved OR updated >= -14d) ORDER BY updated DESC");
            List<ObjectNode> work = new ArrayList<>();
            int total = 0;
            for (JsonNode issue : issues) {
                ObjectNode summary = summarize(issue);
                int seconds = 0;
                for (JsonNode log : worklogs(Json.required(issue, "key"))) {
                    JsonNode author = log.path("author");
                    boolean same = user.equals(Json.text(author, "accountId")) || user.equals(Json.text(author, "name")) || user.equals(Json.text(author, "key"));
                    if (!same || Json.text(log, "started").isBlank()) continue;
                    LocalDate date = parseJiraDate(Json.text(log, "started"));
                    if (!date.isBefore(start) && !date.isAfter(end)) seconds += log.path("timeSpentSeconds").asInt();
                }
                summary.put("loggedSeconds", seconds);
                total += seconds;
                work.add(summary);
            }
            service.store.put("teamSnapshot", Json.obj("id", Json.required(member, "id"), "memberId", Json.required(member, "id"), "from", start.toString(), "to", end.toString(),
                    "issues", work, "loggedSeconds", total, "at", service.time.instant().toString(), "coverage", "Jira에 등록된 이슈와 시간만 포함합니다."));
        }
        service.store.put("sync", Json.obj("id", "team", "at", service.time.instant().toString(), "ok", true));
    }
    private ObjectNode summarize(JsonNode issue) {
        JsonNode f = issue.path("fields");
        String category = f.path("status").path("statusCategory").path("key").asText();
        String status = switch (category) { case "done" -> "done"; case "indeterminate" -> "doing"; default -> "open"; };
        return Json.obj("id", Json.required(issue, "key"), "title", Json.text(f, "summary"), "status", status, "jiraStatus", Json.text(f.path("status"), "name"),
                "product", Json.text(f.path("project"), "name"), "due", Json.text(f, "duedate"), "updatedAt", Json.text(f, "updated"),
                "assignee", Json.text(f.path("assignee"), "displayName"), "versions", f.path("fixVersions"));
    }
    static LocalDate parseJiraDate(String date) {
        try { return KoreaTime.parse(date).atZone(KoreaTime.ZONE).toLocalDate(); }
        catch (RuntimeException e) { return OffsetDateTime.parse(date, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")).atZoneSameInstant(KoreaTime.ZONE).toLocalDate(); }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
