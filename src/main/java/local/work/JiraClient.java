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
    private volatile String siteUrl = "";
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
        return response.body().isBlank() ? Json.object() : Json.read(response.body());
    }
    String issueUrl(String key) {
        String base = config.get("JIRA_SITE_URL", siteUrl);
        if (base.isBlank() && !config.get("JIRA_URL", "").contains("api.atlassian.com")) base = config.get("JIRA_URL", "");
        if (base.isBlank()) return "";
        URI uri = URI.create(base);
        if (!Set.of("http", "https").contains(uri.getScheme())) throw new IllegalArgumentException("Jira 사이트 주소를 확인하세요.");
        return base.replaceAll("/+$", "") + "/browse/" + WorkService.issue(key);
    }
    void loadSiteUrl() throws Exception {
        if (siteUrl.isBlank() && !config.has("JIRA_SITE_URL") && config.get("JIRA_URL", "").contains("api.atlassian.com")) siteUrl = Json.required(request("GET", "/serverInfo", null), "baseUrl");
    }
    String statusCategory(JsonNode status) {
        return switch (Json.text(status.path("statusCategory"), "key")) { case "done" -> "done"; case "indeterminate" -> "in-progress"; default -> "todo"; };
    }
    ObjectNode fetchIssue(String key) throws Exception {
        loadSiteUrl();
        return summarize(request("GET", "/issue/" + WorkService.issue(key) + "?fields=summary,status,assignee,created,updated,duedate,project,fixVersions,issuetype,parent,issuelinks", null));
    }
    List<JsonNode> transitions(String key) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        request("GET", "/issue/" + WorkService.issue(key) + "/transitions?expand=transitions.fields", null).path("transitions").forEach(result::add);
        return result;
    }
    void transition(String key, String id) throws Exception {
        request("POST", "/issue/" + WorkService.issue(key) + "/transitions", Json.obj("transition", Json.obj("id", id)));
    }
    List<JsonNode> changelog(String key) throws Exception { return pages("/issue/" + WorkService.issue(key) + "/changelog", "values"); }
    List<JsonNode> comments(String key) throws Exception { return pages("/issue/" + WorkService.issue(key) + "/comment", "comments"); }
    private List<JsonNode> pages(String path, String field) throws Exception {
        List<JsonNode> rows = new ArrayList<>(); int offset = 0;
        while (true) {
            JsonNode response = request("GET", path + "?startAt=" + offset + "&maxResults=100", null);
            JsonNode page = response.path(field); page.forEach(rows::add); offset += page.size();
            if (page.isEmpty() || response.path("isLast").asBoolean() || offset >= response.path("total").asInt(offset)) return rows;
        }
    }
    String resolveUser(String user) throws Exception {
        if (!cloud()) return user;
        if (user.contains(":") || user.matches("[a-fA-F0-9-]{20,}")) return Json.required(request("GET", "/user?accountId=" + encode(user), null), "accountId");
        JsonNode matches = request("GET", "/user/search?query=" + encode(user), null);
        List<JsonNode> exact = new ArrayList<>();
        for (JsonNode match : matches) if (user.equalsIgnoreCase(Json.text(match, "emailAddress")) || user.equals(Json.text(match, "accountId")) || user.equals(Json.text(match, "displayName"))) exact.add(match);
        if (exact.size() == 1) return Json.required(exact.getFirst(), "accountId");
        if (matches.size() == 1 && user.contains("@")) return Json.required(matches.get(0), "accountId");
        throw new IllegalArgumentException("Jira 사용자를 한 명으로 확인하지 못했습니다. 팀원의 Jira accountId 또는 계정 이메일을 확인하세요.");
    }
    static Instant instant(String timestamp) {
        try { return KoreaTime.parse(timestamp); }
        catch (RuntimeException e) { return OffsetDateTime.parse(timestamp, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")).toInstant(); }
    }
    static String documentText(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (Json.text(node, "type").equals("text")) return Json.text(node, "text");
        if (Json.text(node, "type").equals("hardBreak")) return "\n";
        StringBuilder text = new StringBuilder();
        JsonNode children = node.isArray() ? node : node.path("content");
        for (JsonNode child : children) text.append(documentText(child));
        if (Set.of("paragraph", "heading", "listItem").contains(Json.text(node, "type"))) text.append('\n');
        return text.toString();
    }
    ObjectNode updateTask(WorkService service, ObjectNode task, ObjectNode issue) {
        synchronized (service) {
        ObjectNode latest = service.store.get("task", Json.text(task, "id"));
        if (latest != null) task = latest;
        ObjectNode row = task.deepCopy();
        for (String field : List.of("title", "status", "due", "product", "jiraStatus", "jiraStatusId", "issueUrl", "issueType", "jiraParent", "jiraPredecessors")) row.set(field, issue.get(field));
        service.store.put("issue", issue);
        if (!Json.text(issue, "createdAt").isBlank()) row.set("createdAt", issue.get("createdAt"));
        if (issue.hasNonNull("jiraChangedAt")) row.set("jiraChangedAt", issue.get("jiraChangedAt"));
        return service.saveTask(row);
        }
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
        return search(jql, "summary,status,assignee,created,updated,duedate,project,fixVersions,parent,issuetype,issuelinks");
    }
    List<JsonNode> search(String jql, String fields) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        int offset = 0;
        String token = "";
        while (true) {
            String path = cloud() ? "/search/jql?jql=" + encode(jql) + "&maxResults=100&fields=" + encode(fields) + (token.isBlank() ? "" : "&nextPageToken=" + encode(token))
                    : "/search?jql=" + encode(jql) + "&startAt=" + offset + "&maxResults=100&fields=" + encode(fields);
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
        loadSiteUrl();
        List<JsonNode> issues = search(config.get("JIRA_JQL", "assignee = currentUser() AND (resolution = Unresolved OR updated >= -14d) ORDER BY updated DESC"));
        List<ObjectNode> snapshots = new ArrayList<>();
        for (JsonNode issue : issues) {
            ObjectNode row = summarize(issue);
            ObjectNode existing = service.store.get("task", "jira:" + Json.text(row, "id"));
            if (Json.text(row, "status").equals("done") && (existing == null || Json.text(existing, "completedAt").isBlank()) && !Json.text(row, "updatedAt").isBlank() && !parseJiraDate(Json.text(row, "updatedAt")).isBefore(service.time.today().minusDays(1))) {
                Instant latest = null;
                for (JsonNode change : changelog(Json.text(row, "id"))) for (JsonNode item : change.path("items")) {
                    if ((Json.text(item, "field").equals("status") || Json.text(item, "fieldId").equals("status")) && Json.text(item, "to").equals(Json.text(row, "jiraStatusId"))) {
                        Instant at = instant(Json.required(change, "created")); if (latest == null || at.isAfter(latest)) latest = at;
                    }
                }
                if (latest != null) row.put("jiraChangedAt", latest.toString());
            }
            snapshots.add(row);
        }
        synchronized (service) { service.store.transaction(() -> {
            for (ObjectNode row : snapshots) {
                service.store.put("issue", row);
                String key = Json.text(row, "id");
                ObjectNode existing = service.store.list("task").stream().filter(n -> key.equals(Json.text(n, "jiraKey"))).findFirst().orElse(null);
                if (existing == null) existing = Json.obj("id", "jira:" + key, "jiraKey", key, "important", false, "urgent", false, "hidden", false);
                updateTask(service, existing, row);
            }
            service.store.put("sync", Json.obj("id", "jira", "at", service.time.instant().toString(), "count", snapshots.size(), "ok", true));
        }); }
    }
    void syncTeam(WorkService service) throws Exception {
        LocalDate end = service.time.today(), start = service.time.monday(end);
        for (ObjectNode member : service.store.list("member")) {
            String user = resolveUser(Json.required(member, "jiraUser"));
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
    ObjectNode summarize(JsonNode issue) {
        JsonNode f = issue.path("fields");
        List<ObjectNode> predecessors = new ArrayList<>();
        for (JsonNode link : f.path("issuelinks")) {
            boolean inward = link.hasNonNull("inwardIssue");
            String relation = Json.text(link.path("type"), inward ? "inward" : "outward").toLowerCase(Locale.ROOT);
            if (relation.equals("is blocked by") || relation.equals("depends on") || relation.equals("선행 작업") || relation.contains("차단됨")) {
                JsonNode previous = link.path(inward ? "inwardIssue" : "outwardIssue");
                if (Json.text(previous, "key").isBlank()) continue;
                predecessors.add(Json.obj("key", Json.text(previous, "key"), "title", Json.text(previous.path("fields"), "summary"), "status", statusCategory(previous.path("fields").path("status"))));
            }
        }
        return Json.obj("id", Json.required(issue, "key"), "title", Json.text(f, "summary"), "status", statusCategory(f.path("status")), "jiraStatus", Json.text(f.path("status"), "name"), "jiraStatusId", Json.text(f.path("status"), "id"),
                "issueUrl", issueUrl(Json.required(issue, "key")), "issueType", Json.text(f.path("issuetype"), "name"), "createdAt", Json.text(f, "created"),
                "product", Json.text(f.path("project"), "name"), "due", Json.text(f, "duedate"), "updatedAt", Json.text(f, "updated"),
                "assignee", Json.text(f.path("assignee"), "displayName"), "versions", f.path("fixVersions"),
                "jiraParent", Json.obj("key", Json.text(f.path("parent"), "key"), "title", Json.text(f.path("parent").path("fields"), "summary")), "jiraPredecessors", predecessors);
    }
    static LocalDate parseJiraDate(String date) {
        try { return KoreaTime.parse(date).atZone(KoreaTime.ZONE).toLocalDate(); }
        catch (RuntimeException e) { return OffsetDateTime.parse(date, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")).atZoneSameInstant(KoreaTime.ZONE).toLocalDate(); }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
