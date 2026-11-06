package local.work;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

final class TaskStructure {
    static String group(JsonNode task) {
        if (task.has("workGroup")) return Json.text(task, "workGroup");
        JsonNode parent = task.path("jiraParent");
        String key = Json.text(parent, "key"), title = Json.text(parent, "title");
        return key.isBlank() ? "" : key + (title.isBlank() ? "" : " " + title);
    }
    static List<String> predecessors(JsonNode task) {
        List<String> ids = new ArrayList<>();
        if (task.has("predecessorIds")) task.path("predecessorIds").forEach(id -> ids.add(id.asText()));
        else task.path("jiraPredecessors").forEach(issue -> ids.add("jira:" + Json.text(issue, "key")));
        return ids;
    }
    static void validate(ObjectNode task, List<ObjectNode> tasks) {
        if (task.has("workGroup")) {
            String name = Json.text(task, "workGroup").trim();
            if (name.length() > 100) throw new IllegalArgumentException("업무 묶음 이름은 100자 이내로 입력하세요.");
            task.put("workGroup", name);
        }
        if (!task.has("predecessorIds")) return;
        if (!task.path("predecessorIds").isArray()) throw new IllegalArgumentException("선행 작업을 목록으로 입력하세요.");
        Map<String, ObjectNode> index = new HashMap<>();
        for (ObjectNode row : tasks) indexTask(index, row);
        indexTask(index, task);
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode value : task.path("predecessorIds")) {
            String id = value.asText();
            boolean external = false;
            for (JsonNode issue : task.path("jiraPredecessors")) if (id.equals("jira:" + Json.text(issue, "key"))) external = true;
            if (!index.containsKey(id) && !external) throw new IllegalArgumentException("선행 작업을 찾지 못했습니다.");
            if (reaches(id, Json.text(task, "id"), index, new HashSet<>())) throw new IllegalArgumentException("작업이 자기 자신을 기다리거나 서로 기다리도록 연결할 수 없습니다.");
            ids.add(id);
        }
        task.set("predecessorIds", Json.MAPPER.valueToTree(ids));
    }
    private static void indexTask(Map<String, ObjectNode> index, ObjectNode task) {
        index.put(Json.text(task, "id"), task);
        if (!Json.text(task, "jiraKey").isBlank()) index.put("jira:" + Json.text(task, "jiraKey"), task);
    }
    private static boolean reaches(String id, String target, Map<String, ObjectNode> index, Set<String> visited) {
        ObjectNode task = index.get(id);
        if (task == null) return false;
        String actual = Json.text(task, "id");
        if (actual.equals(target)) return true;
        if (!visited.add(actual)) return false;
        for (String previous : predecessors(task)) if (reaches(previous, target, index, visited)) return true;
        return false;
    }
}
