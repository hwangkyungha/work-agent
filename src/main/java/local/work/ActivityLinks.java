package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Resolves external issue keys only after an issue has actually been identified. */
final class ActivityLinks {
    private final Store store;
    private final Map<String, ObjectNode> tasks = new HashMap<>();
    private final Map<String, String> issueTasks = new HashMap<>();
    private final Set<String> issues = new HashSet<>();
    ActivityLinks(Store store) {
        this.store = store;
        store.list("issue").forEach(row -> issues.add(Json.text(row, "id")));
        for (String kind : List.of("jiraWorklog", "worklogFavorite")) store.list(kind).forEach(row -> { String key=Json.text(row,"jiraKey");if(!key.isBlank())issues.add(key); });
        for (ObjectNode task : store.list("task")) {
            tasks.put(Json.text(task, "id"), task);
            String key = Json.text(task, "jiraKey");
            if (!key.isBlank()) { issues.add(key); issueTasks.putIfAbsent(key, Json.text(task, "id")); }
        }
    }
    boolean known(String key) {
        return !key.isBlank() && issues.contains(key);
    }
    String key(JsonNode row) {
        String taskId = Json.text(row, "taskId");
        ObjectNode task = tasks.get(taskId);
        if (task != null) return Json.text(task, "jiraKey");
        String key = Json.text(row, "jiraKey");
        if (row.path("userLinked").asBoolean()) return key;
        if (row.has("candidateKeys")) return candidate(row.path("candidateKeys"));
        return known(key) ? key : "";
    }
    String taskId(JsonNode row, String key) {
        String id = Json.text(row, "taskId");
        if (!id.isBlank()) return id;
        return issueTasks.getOrDefault(key, "");
    }
    String candidate(JsonNode keys) {
        List<String> valid = new ArrayList<>();
        keys.forEach(key -> { if (known(key.asText())) valid.add(key.asText()); });
        return valid.size() == 1 ? valid.getFirst() : "";
    }
    int repair() {
        int count = 0;
        for (ObjectNode row : store.list("activity")) {
            if (!Set.of("claude", "codex").contains(Json.text(row, "source")) || row.path("userLinked").asBoolean()) continue;
            String key = candidate(row.path("candidateKeys"));
            if (row.path("candidateKeys").isMissingNode() && known(Json.text(row, "jiraKey"))) key = Json.text(row, "jiraKey");
            if (!key.equals(Json.text(row, "jiraKey"))) { row.put("jiraKey", key); store.put("activity", row); count++; }
        }
        return count;
    }
}
