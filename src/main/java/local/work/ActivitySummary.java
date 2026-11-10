package local.work;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;

final class ActivitySummary {
    private final Store store;
    private Set<String> automated;
    private long automatedRevision = -1;
    ActivitySummary(Store store) { this.store = store; }
    List<ObjectNode> group(LocalDate from, LocalDate to) {
        return group(from, to, false);
    }
    List<ObjectNode> briefingGroups(LocalDate from, LocalDate to) {
        return group(from, to, true);
    }
    Set<String> automatedSessions() {
        if (automated != null && automatedRevision == store.revision()) return automated;
        automatedRevision = store.revision();
        Set<String> ids = new HashSet<>();
        for (ObjectNode row : store.list("evidence")) {
            String path = Json.text(row, "cwd").replace('\\', '/');
            if (path.matches("(?i).*/(?:\\.ct-verify|llm_judge)(?:/.*)?") || Json.text(row, "text").startsWith("당신은 한 실행 결과에서 자신에게 배정된 정성 기준만 판정하는 LLM Judge")) ids.add(session(row));
        }
        automated = ids;
        return ids;
    }
    static String session(JsonNode row) { return Json.text(row, "source") + ":" + Json.text(row, "session"); }
    private List<ObjectNode> group(LocalDate from, LocalDate to, boolean briefing) {
        Set<String> excluded = automatedSessions();
        ActivityLinks links = new ActivityLinks(store);
        List<ObjectNode> activities = store.between("activity", from.toString(), to.toString()).stream().filter(row -> !excluded.contains(session(row))).toList();
        List<ObjectNode> evidence = store.between("evidence", from.toString(), to.toString()).stream().filter(row -> !excluded.contains(session(row))).toList();
        Map<String, ObjectNode> sessionLinks = new HashMap<>(); store.list("sessionLink").forEach(link -> sessionLinks.put(Json.text(link, "id"), link));
        for (ObjectNode row : activities) {
            ObjectNode link = sessionLinks.get(session(row));
            if (link != null && Json.text(row, "taskId").isBlank()) row.put("taskId", Json.text(link, "taskId"));
        }
        Map<String, String> names = new HashMap<>(), issues = new HashMap<>(), assigned = new HashMap<>(), taskIds = new HashMap<>();
        for (ObjectNode session : store.list("session")) names.put(Json.text(session, "id"), Json.text(session, "name"));
        for (ObjectNode row : evidence) if (Json.text(row, "role").equals("user")) names.putIfAbsent(session(row), shortText(Json.text(row, "text")));
        for (ObjectNode row : store.list("issue")) issues.put(Json.text(row, "id"), Json.text(row, "title"));
        for (String kind : List.of("jiraWorklog", "worklogFavorite")) for (ObjectNode row : store.list(kind)) if (!Json.text(row,"jiraKey").isBlank()) issues.putIfAbsent(Json.text(row,"jiraKey"),Json.text(row,"title"));
        for (ObjectNode row : store.list("task")) if (!Json.text(row, "jiraKey").isBlank()) issues.putIfAbsent(Json.text(row, "jiraKey"), Json.text(row, "title"));
        for (ObjectNode row : activities) if (!Json.text(row, "session").isBlank()) {
            String sessionDay = session(row) + ":" + Json.text(row, "date");
            assigned.put(sessionDay, links.key(row));
            taskIds.put(sessionDay, links.taskId(row, links.key(row)));
        }
        Map<String, Set<String>> candidates = new HashMap<>();
        for (ObjectNode row : evidence) {
            Set<String> keys = candidates.computeIfAbsent(session(row) + ":" + Json.text(row, "date"), k -> new HashSet<>());
            for (JsonNode key : row.path("issueKeys")) keys.add(key.asText());
        }
        candidates.forEach((key, keys) -> assigned.putIfAbsent(key, links.candidate(Json.MAPPER.valueToTree(keys))));
        Map<String, ObjectNode> groups = new LinkedHashMap<>();
        Map<String, Map<String, ObjectNode>> sessions = new HashMap<>();
        for (ObjectNode row : activities) {
            String key = links.key(row), taskId = links.taskId(row, key);
            String workId = !taskId.isBlank() ? taskId : !key.isBlank() ? "jira:" + key : Json.text(row, "session").isBlank() ? "activity:" + Json.text(row, "id") : "session:" + session(row);
            String groupKey = Json.text(row, "date") + ":" + workId;
            ObjectNode group = groups.computeIfAbsent(groupKey, k -> make(row, key, names, issues));
            group.put("workId", workId).put("taskId", taskId);
            group.put("activityCount", group.path("activityCount").asInt() + 1);
            if (Json.text(group, "summary").isBlank()) group.put("summary", shortText(Json.text(row, "title")));
        }
        for (ObjectNode row : evidence) {
            ObjectNode link = sessionLinks.get(session(row));
            if (link != null) row.put("taskId", Json.text(link, "taskId"));
            String key = link != null ? links.key(row) : assigned.getOrDefault(session(row) + ":" + Json.text(row, "date"), "");
            String chosen = key, taskId = link != null ? Json.text(link, "taskId") : taskIds.getOrDefault(session(row) + ":" + Json.text(row, "date"), links.taskId(row, key));
            String workId = !taskId.isBlank() ? taskId : !key.isBlank() ? "jira:" + key : "session:" + session(row);
            String groupKey = Json.text(row, "date") + ":" + workId;
            ObjectNode group = groups.computeIfAbsent(groupKey, k -> make(row, chosen, names, issues));
            group.put("workId", workId).put("taskId", taskId);
            String role = Json.text(row, "role");
            group.put("messageCount", group.path("messageCount").asInt() + 1);
            String counter = role.equals("user") ? "userMessages" : "assistantMessages";
            group.put(counter, group.path(counter).asInt() + 1);
            Map<String, ObjectNode> perSession = sessions.computeIfAbsent(groupKey, k -> new LinkedHashMap<>());
            ObjectNode item = perSession.computeIfAbsent(session(row), k -> Json.obj("id", k, "source", Json.text(row, "source"), "name", names.getOrDefault(k, "이름 없는 세션"), "messageCount", 0));
            item.put("messageCount", item.path("messageCount").asInt() + 1);
        }
        groups.forEach((key, group) -> {
            var grouped = sessions.getOrDefault(key, Map.of()).values();
            group.set("sessions", Json.MAPPER.valueToTree(grouped)); group.put("sessionCount", grouped.size());
            ObjectNode task = store.get("task", Json.text(group, "taskId"));
            if (task != null) group.put("title", Json.text(task, "title"));
        });
        return groups.values().stream().sorted(Comparator.comparing(n -> Json.text(n, "date"))).toList();
    }
    private static ObjectNode make(JsonNode row, String key, Map<String, String> names, Map<String, String> issues) {
        String title = issues.getOrDefault(key, "");
        if (title.isBlank()) title = names.getOrDefault(session(row), key.isBlank() ? Set.of("claude", "codex").contains(Json.text(row,"source")) ? "이름 없는 세션" : shortText(Json.text(row,"title")) : key);
        return Json.obj("date", Json.text(row, "date"), "jiraKey", key,
                "title", title,
                "summary", "", "messageCount", 0, "userMessages", 0, "assistantMessages", 0, "activityCount", 0);
    }
    private static String shortText(String text) {
        String line = Arrays.stream(text.split("\\R")).map(s -> s.replaceAll("^[#*\\s-]+", "").trim()).filter(s -> !s.isBlank()).findFirst().orElse("");
        return line.length() > 120 ? line.substring(0, 120) + "…" : line;
    }
}
