package local.work;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;

final class ActivitySummary {
    private final Store store;
    ActivitySummary(Store store) { this.store = store; }
    List<ObjectNode> group(LocalDate from, LocalDate to) {
        return group(from, to, false);
    }
    List<ObjectNode> briefingGroups(LocalDate from, LocalDate to) {
        return group(from, to, true);
    }
    Set<String> automatedSessions() {
        Set<String> ids = new HashSet<>();
        for (ObjectNode row : store.list("evidence")) {
            String path = Json.text(row, "cwd").replace('\\', '/');
            if (path.matches("(?i).*/(?:\\.ct-verify|llm_judge)(?:/.*)?") || Json.text(row, "text").startsWith("당신은 한 실행 결과에서 자신에게 배정된 정성 기준만 판정하는 LLM Judge")) ids.add(session(row));
        }
        return ids;
    }
    static String session(JsonNode row) { return Json.text(row, "source") + ":" + Json.text(row, "session"); }
    private List<ObjectNode> group(LocalDate from, LocalDate to, boolean briefing) {
        Set<String> excluded = briefing ? automatedSessions() : Set.of();
        List<ObjectNode> activities = store.between("activity", from.toString(), to.toString()).stream().filter(row -> !excluded.contains(session(row))).toList();
        List<ObjectNode> evidence = store.between("evidence", from.toString(), to.toString()).stream().filter(row -> !excluded.contains(session(row))).toList();
        Map<String, String> names = new HashMap<>(), issues = new HashMap<>(), assigned = new HashMap<>();
        for (ObjectNode session : store.list("session")) names.put(Json.text(session, "id"), Json.text(session, "name"));
        for (ObjectNode row : evidence) if (Json.text(row, "role").equals("user")) names.putIfAbsent(session(row), shortText(Json.text(row, "text")));
        for (ObjectNode row : store.list("issue")) issues.put(Json.text(row, "id"), Json.text(row, "title"));
        for (ObjectNode row : store.list("task")) if (!Json.text(row, "jiraKey").isBlank()) issues.putIfAbsent(Json.text(row, "jiraKey"), Json.text(row, "title"));
        for (ObjectNode row : activities) if (!Json.text(row, "session").isBlank()) assigned.put(session(row) + ":" + Json.text(row, "date"), Json.text(row, "jiraKey"));
        Map<String, Set<String>> candidates = new HashMap<>();
        for (ObjectNode row : evidence) {
            Set<String> keys = candidates.computeIfAbsent(session(row) + ":" + Json.text(row, "date"), k -> new HashSet<>());
            for (JsonNode key : row.path("issueKeys")) keys.add(key.asText());
        }
        candidates.forEach((key, keys) -> assigned.putIfAbsent(key, keys.size() == 1 ? keys.iterator().next() : ""));
        Map<String, ObjectNode> groups = new LinkedHashMap<>();
        Map<String, Map<String, ObjectNode>> sessions = new HashMap<>();
        for (ObjectNode row : activities) {
            String key = Json.text(row, "jiraKey");
            String groupKey = Json.text(row, "date") + ":" + (key.isBlank() ? (Json.text(row, "session").isBlank() ? "manual:" + Json.text(row, "id") : session(row)) : "jira:" + key);
            ObjectNode group = groups.computeIfAbsent(groupKey, k -> make(row, key, names, issues));
            group.put("activityCount", group.path("activityCount").asInt() + 1);
            if (Json.text(group, "summary").isBlank()) group.put("summary", shortText(Json.text(row, "title")));
        }
        for (ObjectNode row : evidence) {
            String key = assigned.getOrDefault(session(row) + ":" + Json.text(row, "date"), "");
            String chosen = key;
            String groupKey = Json.text(row, "date") + ":" + (key.isBlank() ? session(row) : "jira:" + key);
            ObjectNode group = groups.computeIfAbsent(groupKey, k -> make(row, chosen, names, issues));
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
        });
        return groups.values().stream().sorted(Comparator.comparing(n -> Json.text(n, "date"))).toList();
    }
    private static ObjectNode make(JsonNode row, String key, Map<String, String> names, Map<String, String> issues) {
        return Json.obj("date", Json.text(row, "date"), "jiraKey", key,
                "title", key.isBlank() ? names.getOrDefault(session(row), Json.text(row, "title").isBlank() ? "이름 없는 세션" : shortText(Json.text(row, "title"))) : issues.getOrDefault(key, key),
                "summary", "", "messageCount", 0, "userMessages", 0, "assistantMessages", 0, "activityCount", 0);
    }
    private static String shortText(String text) {
        String line = Arrays.stream(text.split("\\R")).map(s -> s.replaceAll("^[#*\\s-]+", "").trim()).filter(s -> !s.isBlank()).findFirst().orElse("");
        return line.length() > 120 ? line.substring(0, 120) + "…" : line;
    }
}
