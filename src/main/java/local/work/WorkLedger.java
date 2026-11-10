package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;

/** The same planned and observed work is used by the morning screen, briefing and timesheet. */
final class WorkLedger {
    private final WorkService service;
    private final ActivitySummary summary;
    private long revision = -1;
    private final Map<LocalDate,List<ObjectNode>> days = new HashMap<>();
    WorkLedger(WorkService service) { this.service = service; this.summary = new ActivitySummary(service.store); }
    List<ObjectNode> day(LocalDate date) {
        if (revision != service.store.revision()) { days.clear();revision=service.store.revision(); }
        if (days.containsKey(date)) return days.get(date).stream().map(ObjectNode::deepCopy).toList();
        Map<String, ObjectNode> rows = new LinkedHashMap<>();
        ObjectNode plan = service.currentPlan(date);
        Set<String> current = new HashSet<>(); plan.path("taskIds").forEach(id -> current.add(id.asText()));
        JsonNode initial = plan.has("initialTasks") ? plan.path("initialTasks") : Json.MAPPER.valueToTree(service.planned(date));
        for (JsonNode task : initial) addPlanned(rows, task, true, current);
        JsonNode snapshots = plan.has("taskSnapshots") ? plan.path("taskSnapshots") : Json.MAPPER.valueToTree(service.planned(date));
        for (JsonNode task : snapshots) addPlanned(rows, task, false, current);
        for (ObjectNode group : summary.briefingGroups(date, date)) {
            if (group.path("sessionCount").asInt() > 0 && group.path("assistantMessages").asInt() == 0) continue;
            String id = Json.text(group, "workId");
            ObjectNode row = rows.computeIfAbsent(id, key -> make(group, id));
            row.put("observed", true);
            row.set("evidence", group);
        }
        ActivityLinks links = new ActivityLinks(service.store);
        for (ObjectNode result : service.store.list("draftResult")) {
            String created = Json.text(result,"createdAt");
            if (created.isBlank() || !KoreaTime.dateOf(created).equals(date)) continue;
            String key = links.candidate(result.path("issueKeys"));
            String taskId = links.taskId(Json.object(),key);
            ObjectNode linked = service.store.get("workLink", "pr:" + Json.text(result,"url"));
            if (linked != null && service.store.get("task",Json.text(linked,"taskId")) != null) taskId = Json.text(linked,"taskId");
            String id = taskId.isBlank() ? "pr:" + Json.text(result,"url") : taskId;
            ObjectNode source = Json.obj("taskId",taskId,"jiraKey",key,"title","Draft PR 작성: " + Json.text(result,"title"));
            ObjectNode row = rows.computeIfAbsent(id, unused -> make(source,id));
            row.put("observed",true).put("sourcePrUrl",Json.text(result,"url"));
        }
        for (ObjectNode saved : service.store.between("workDay", date.toString(), date.toString())) {
            String id = Json.text(saved, "workId");
            ObjectNode row = rows.computeIfAbsent(id, key -> make(saved, id));
            row.put("outcome", Json.text(saved, "outcome")).put("note", Json.text(saved, "note"));
        }
        for (ObjectNode row : rows.values()) {
            row.put("priorityChanged", row.has("initialImportant") && (row.path("initialImportant").asBoolean()!=row.path("finalImportant").asBoolean() || row.path("initialUrgent").asBoolean()!=row.path("finalUrgent").asBoolean()));
            String taskId = Json.text(row, "taskId");
            ObjectNode task = taskId.isBlank() ? null : service.store.get("task", taskId);
            if (task != null) {
                row.put("status", Json.text(task, "status")).put("hidden", task.path("hidden").asBoolean());
                if (Json.text(row, "jiraKey").isBlank()) row.put("jiraKey", Json.text(task, "jiraKey"));
            }
            String outcome = Json.text(row, "outcome");
            row.put("performed", Set.of("progress", "done").contains(outcome) || row.path("observed").asBoolean() && !Set.of("not-started", "cancelled", "deferred").contains(outcome));
            row.put("date", date.toString());
        }
        days.put(date,rows.values().stream().map(ObjectNode::deepCopy).toList());
        return new ArrayList<>(rows.values());
    }
    private void addPlanned(Map<String, ObjectNode> rows, JsonNode task, boolean initial, Set<String> current) {
        String id = Json.text(task, "id");
        ObjectNode row = rows.computeIfAbsent(id, key -> make(task, id));
        row.put("taskId", id).put("planned", true).put("inInitialPlan", initial || row.path("inInitialPlan").asBoolean()).put("inFinalPlan", current.contains(id));
        if (initial) row.put("initialImportant",task.path("important").asBoolean()).put("initialUrgent",task.path("urgent").asBoolean());
        row.put("finalImportant",task.path("important").asBoolean()).put("finalUrgent",task.path("urgent").asBoolean());
    }
    private ObjectNode make(JsonNode source, String id) {
        return Json.obj("workId", id, "taskId", Json.text(source, "taskId"), "title", Json.text(source, "title"), "jiraKey", Json.text(source, "jiraKey"),
                "planned", false, "observed", false, "outcome", "unconfirmed", "note", "");
    }
    List<ObjectNode> carryover(LocalDate today) {
        Map<String, ObjectNode> latest = new LinkedHashMap<>();
        Set<LocalDate> dates = new TreeSet<>();
        for (String kind : List.of("plan", "workDay")) for (ObjectNode row : service.store.list(kind)) {
            LocalDate date = LocalDate.parse(Json.required(row, "date"));
            if (date.isBefore(today)) dates.add(date);
        }
        for (LocalDate date : dates) for (ObjectNode row : day(date)) latest.put(Json.text(row, "workId"), row);
        List<ObjectNode> results = new ArrayList<>();
        Set<String> selected = new HashSet<>(); service.planned(today).forEach(t -> selected.add(Json.text(t, "id")));
        for (ObjectNode row : latest.values()) {
            String taskId = Json.text(row, "taskId"), outcome = Json.text(row, "outcome");
            if (taskId.isBlank() || service.store.get("task", taskId) == null || selected.contains(taskId) || row.path("hidden").asBoolean() || Json.text(row, "status").equals("done") || Set.of("done", "cancelled", "deferred").contains(outcome)) continue;
            row.put("reason", row.path("performed").asBoolean() ? "이전에 진행했고 아직 끝나지 않은 업무입니다." : "이전 계획에 남아 있고, 수행 여부를 확인하지 못했습니다.");
            results.add(row);
        }
        return results;
    }
    List<ObjectNode> worklogCandidates(LocalDate date) {
        return day(date).stream().filter(row -> row.path("performed").asBoolean()).toList();
    }
    ObjectNode record(JsonNode input) {
        synchronized (service) { return recordLocked(input); }
    }
    private ObjectNode recordLocked(JsonNode input) {
        LocalDate date = LocalDate.parse(Json.required(input, "date"));
        String workId = Json.required(input, "workId"), outcome = Json.required(input, "outcome");
        if (!Set.of("unconfirmed", "not-started", "progress", "done", "deferred", "cancelled").contains(outcome)) throw new IllegalArgumentException("그날의 수행 상태를 확인하세요.");
        ObjectNode current = day(date).stream().filter(row -> workId.equals(Json.text(row, "workId"))).findFirst().orElseThrow(() -> new IllegalArgumentException("수행 내용을 찾지 못했습니다."));
        ObjectNode row = Json.obj("id", date + ":" + workId, "date", date.toString(), "workId", workId, "taskId", Json.text(current, "taskId"), "title", Json.text(current, "title"), "jiraKey", Json.text(current, "jiraKey"),
                "outcome", outcome, "note", input.path("note").asText(Json.text(current, "note")), "updatedAt", service.time.instant().toString());
        return service.store.put("workDay", row);
    }
}
