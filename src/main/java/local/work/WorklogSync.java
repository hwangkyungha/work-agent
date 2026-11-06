package local.work;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;

final class WorklogSync {
    private final WorkService service;
    WorklogSync(WorkService service) { this.service = service; }
    synchronized ObjectNode sync(LocalDate date) throws Exception {
        LocalDate from = service.time.monday(date), to = from.plusDays(6);
        String syncId = "worklogs:" + from;
        Instant started = service.time.instant();
        try {
            service.jira.loadSiteUrl();
            JsonNode user = service.jira.request("GET", "/myself", null);
            String userId = Json.required(user, service.jira.cloud() ? "accountId" : "name");
            String quoted = "\"" + userId.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            Map<String, ObjectNode> issues = new LinkedHashMap<>();
            for (JsonNode issue : service.jira.search("worklogAuthor = " + quoted + " AND worklogDate >= \"" + from.minusDays(1) + "\" AND worklogDate <= \"" + to.plusDays(1) + "\"", "summary,status,project")) {
                ObjectNode summary = service.jira.summarize(issue); issues.put(Json.text(summary, "id"), summary);
            }
            // Known issues also cover logs that Jira's worklog JQL index does not return.
            Set<String> known = new LinkedHashSet<>();
            for (ObjectNode row : service.store.list("jiraWorklog")) if (inRange(Json.text(row, "date"), from, to)) known.add(Json.text(row, "jiraKey"));
            for (ObjectNode row : service.store.list("worklogFavorite")) known.add(Json.text(row, "id"));
            for (ObjectNode day : service.store.between("worklog", from.toString(), to.toString())) for (JsonNode item : day.path("items")) known.add(Json.text(item, "jiraKey"));
            for (String key : known) if (!issues.containsKey(key)) {
                ObjectNode issue = service.store.get("issue", key);
                if (issue == null) issue = service.jira.fetchIssue(key);
                issues.put(key, issue);
            }
            List<ObjectNode> records = new ArrayList<>();
            for (var issue : issues.entrySet()) for (JsonNode log : service.jira.worklogs(issue.getKey(), from, to)) {
                JsonNode author = log.path("author");
                if (!userId.equals(Json.text(author, service.jira.cloud() ? "accountId" : "name"))) continue;
                LocalDate day = JiraClient.parseJiraDate(Json.required(log, "started"));
                if (day.isBefore(from) || day.isAfter(to)) continue;
                String id = Json.required(log, "id");
                records.add(Json.obj("id", id, "jiraId", id, "jiraKey", issue.getKey(), "title", Json.text(issue.getValue(), "title"), "issueUrl", service.jira.issueUrl(issue.getKey()),
                        "date", day.toString(), "seconds", log.path("timeSpentSeconds").asLong(), "comment", JiraClient.documentText(log.path("comment")).trim(), "started", Json.text(log, "started"), "authorId", userId));
            }
            synchronized (service) { service.store.transaction(() -> {
                for (ObjectNode old : service.store.list("jiraWorklog")) if (inRange(Json.text(old, "date"), from, to)) service.store.delete("jiraWorklog", Json.text(old, "id"));
                for (ObjectNode row : records) service.store.put("jiraWorklog", row);
                for (ObjectNode issue : issues.values()) if (service.store.get("issue", Json.text(issue, "id")) == null) service.store.put("issue", issue);
                for (ObjectNode day : service.store.between("worklog", from.toString(), to.toString())) {
                    boolean recovered = false;
                    for (JsonNode item : day.path("items")) if (Set.of("posting", "unknown").contains(Json.text(item, "state"))) {
                        String marker = Json.text(item, "marker");
                        for (ObjectNode remote : records) if (!marker.isBlank() && Json.text(remote, "comment").contains("[" + marker + "]") && Json.text(remote, "jiraKey").equals(Json.text(item, "jiraKey"))) {
                            ((ObjectNode) item).put("state", "posted").put("jiraId", Json.text(remote, "jiraId")); recovered = true; break;
                        }
                    }
                    for (JsonNode item : day.path("items")) if (Json.text(item, "state").equals("posted")) {
                        String at = item.path("registeredAt").asText(Json.text(day, "updatedAt"));
                        if (at.isBlank() || !KoreaTime.parse(at).isAfter(started)) { ((ObjectNode) item).put("snapshotSeen", true); recovered = true; }
                    }
                    if (recovered) service.store.put("worklog", day);
                }
                service.store.put("sync", Json.obj("id", syncId, "at", service.time.instant().toString(), "lastSuccessAt", started.toString(), "ok", true, "from", from.toString(), "to", to.toString(), "count", records.size()));
            }); }
            return Json.obj("from", from.toString(), "to", to.toString(), "count", records.size());
        } catch (Exception e) {
            ObjectNode previous = service.store.get("sync", syncId);
            service.store.put("sync", Json.obj("id", syncId, "at", service.time.instant().toString(), "lastSuccessAt", previous == null ? "" : Json.text(previous, "lastSuccessAt"), "ok", false, "error", SlackConnection.friendly(e)));
            throw e;
        }
    }
    private static boolean inRange(String day, LocalDate from, LocalDate to) { return day.compareTo(from.toString()) >= 0 && day.compareTo(to.toString()) <= 0; }
}
