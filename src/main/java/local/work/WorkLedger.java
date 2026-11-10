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
            row.put("userRecorded",group.path("userRecorded").asBoolean());
            row.put("lastActivityAt",Json.text(group,"lastActivityAt"));
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
            row.put("outcome", Json.text(saved, "outcome")).put("note", Json.text(saved, "note")).put("confirmed",true);
            if(!Json.text(saved,"title").isBlank())row.put("title",Json.text(saved,"title"));
        }
        for (ObjectNode row : rows.values()) {
            row.put("priorityChanged", row.has("initialImportant") && (row.path("initialImportant").asBoolean()!=row.path("finalImportant").asBoolean() || row.path("initialUrgent").asBoolean()!=row.path("finalUrgent").asBoolean()));
            String taskId = Json.text(row, "taskId");
            ObjectNode task = taskId.isBlank() ? null : service.store.get("task", taskId);
            if (task != null) {
                row.put("status", Json.text(task, "status")).put("hidden", task.path("hidden").asBoolean());
                row.put("issueUrl",Json.text(task,"issueUrl"));
                if (Json.text(row, "jiraKey").isBlank()) row.put("jiraKey", Json.text(task, "jiraKey"));
            }
            if(!Json.text(row,"jiraKey").isBlank() && Json.text(row,"issueUrl").isBlank())row.put("issueUrl",service.jira.issueUrl(Json.text(row,"jiraKey")));
            String outcome = Json.text(row, "outcome");
            row.put("performed", Set.of("progress", "done").contains(outcome) || row.path("observed").asBoolean() && !Set.of("not-started", "cancelled", "deferred").contains(outcome));
            row.put("date", date.toString());
        }
        days.put(date,rows.values().stream().map(ObjectNode::deepCopy).toList());
        return new ArrayList<>(rows.values());
    }
    List<ObjectNode> reviewRows(LocalDate date) {
        Map<String,ObjectNode> issues=new HashMap<>();for(ObjectNode task:service.store.list("task"))if(!Json.text(task,"jiraKey").isBlank())issues.put(Json.text(task,"jiraKey"),task);
        return day(date).stream().filter(row->row.path("planned").asBoolean() || row.path("confirmed").asBoolean() && !Json.text(row,"outcome").equals("unconfirmed") || row.path("userRecorded").asBoolean()).map(row->{
            ObjectNode task=issues.get(titleKey(row));
            if(Json.text(row,"taskId").isBlank() && task!=null)row.put("suggestedTaskId",Json.text(task,"id")).put("jiraKey",Json.text(task,"jiraKey")).put("issueUrl",service.jira.issueUrl(Json.text(task,"jiraKey")));
            return row;
        }).toList();
    }
    private static String titleKey(JsonNode row) {
        var key=java.util.regex.Pattern.compile("^([A-Z][A-Z0-9_]*-[0-9]+)\\b").matcher(Json.text(row,"title"));
        return key.find()?key.group(1):"";
    }
    List<ObjectNode> activityCandidates(LocalDate date) {
        Set<String> selected=new HashSet<>();reviewRows(date).forEach(row->selected.add(Json.text(row,"workId")));
        Map<String,ObjectNode> issues=new HashMap<>();for(ObjectNode task:service.store.list("task"))if(!Json.text(task,"jiraKey").isBlank())issues.put(Json.text(task,"jiraKey"),task);
        return day(date).stream().filter(row->!selected.contains(Json.text(row,"workId")) && !Json.text(row,"title").isBlank() && !Json.text(row,"title").equals("이름 없는 세션"))
                .map(row->{
                    if(Json.text(row,"taskId").isBlank()) {
                        ObjectNode task=issues.get(titleKey(row));
                        if(task!=null)row.put("taskId",Json.text(task,"id")).put("jiraKey",Json.text(task,"jiraKey")).put("issueUrl",service.jira.issueUrl(Json.text(task,"jiraKey"))).put("title",Json.text(task,"title"));
                    }
                    return row;
                }).filter(row->Json.text(row,"taskId").isBlank() || !selected.contains(Json.text(row,"taskId")))
                .sorted(Comparator.comparingInt((ObjectNode row)->row.path("evidence").path("messageCount").asInt()).reversed().thenComparing(row->Json.text(row,"lastActivityAt"),Comparator.reverseOrder())).toList();
    }
    Set<String> finishedTasks(LocalDate date) {
        Map<String,ObjectNode> latest=new HashMap<>();
        Map<String,String> tasks=new HashMap<>();for(ObjectNode task:service.store.list("task"))if(!Json.text(task,"jiraKey").isBlank())tasks.put(Json.text(task,"jiraKey"),Json.text(task,"id"));
        service.store.list("workDay").stream().filter(row->Json.text(row,"date").compareTo(date.toString())<=0).sorted(Comparator.comparing(row->Json.text(row,"date"))).forEach(row->{String id=Json.text(row,"taskId");if(id.isBlank())id=tasks.getOrDefault(titleKey(row),"");if(!id.isBlank())latest.put(id,row);});
        Set<String> ids=new HashSet<>();latest.forEach((id,row)->{if(Set.of("done","cancelled").contains(Json.text(row,"outcome")))ids.add(id);});
        return ids;
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
        for (LocalDate date : dates) for (ObjectNode row : reviewRows(date)) latest.put(Json.text(row, "workId"), row);
        List<ObjectNode> results = new ArrayList<>();
        Set<String> finished=finishedTasks(today);
        Set<String> selected = new HashSet<>(); service.planned(today).forEach(t -> selected.add(Json.text(t, "id")));
        for (ObjectNode row : latest.values()) {
            String taskId = Json.text(row, "taskId"), outcome = Json.text(row, "outcome");
            if (taskId.isBlank() || service.store.get("task", taskId) == null || selected.contains(taskId) || finished.contains(taskId) || row.path("hidden").asBoolean() || Json.text(row, "status").equals("done") || Set.of("done", "cancelled", "deferred").contains(outcome)) continue;
            row.put("reason", row.path("performed").asBoolean() ? "이전에 진행했고 아직 끝나지 않은 업무입니다." : "이전 계획에 남아 있고, 수행 여부를 확인하지 못했습니다.");
            results.add(row);
        }
        return results.stream().sorted(Comparator.comparing((ObjectNode row)->Json.text(row,"date")).reversed().thenComparing(row->Json.text(row,"lastActivityAt"),Comparator.reverseOrder())).toList();
    }
    List<ObjectNode> worklogCandidates(LocalDate date) {
        return day(date).stream().filter(row -> row.path("performed").asBoolean()).toList();
    }
    ObjectNode add(JsonNode input) {
        synchronized(service) {
            LocalDate date=LocalDate.parse(Json.required(input,"date"));
            String outcome=input.path("outcome").asText("progress");validateOutcome(outcome);
            String workId=Json.text(input,"workId"),taskId=Json.text(input,"taskId");
            ObjectNode task=taskId.isBlank()?null:service.store.get("task",taskId);
            if(!taskId.isBlank() && task==null)throw new IllegalArgumentException("수행한 업무를 찾지 못했습니다.");
            ObjectNode existing=day(date).stream().filter(row->(!workId.isBlank() && workId.equals(Json.text(row,"workId"))) || workId.isBlank() && !taskId.isBlank() && taskId.equals(Json.text(row,"taskId"))).findFirst().orElse(null);
            String id;
            if(existing!=null) {
                id=Json.text(existing,"workId");
                if(task!=null && !taskId.equals(Json.text(existing,"taskId"))) {service.linkWork(Json.obj("date",date.toString(),"workId",id,"taskId",taskId));id=taskId;}
            } else {
                if(!workId.isBlank())throw new IllegalArgumentException("추가할 활동을 찾지 못했습니다.");
                String title=task==null?Json.required(input,"title"):Json.required(task,"title");
                ObjectNode activity=service.activity(Json.obj("date",date.toString(),"title",title,"taskId",taskId));
                id=taskId.isBlank()?"activity:"+Json.text(activity,"id"):taskId;
            }
            ObjectNode entry=Json.obj("date",date.toString(),"workId",id,"outcome",outcome);
            if(input.has("note"))entry.set("note",input.get("note"));
            return record(entry);
        }
    }
    private static void validateOutcome(String outcome) {
        if (!Set.of("unconfirmed", "not-started", "progress", "done", "deferred", "cancelled").contains(outcome)) throw new IllegalArgumentException("그날의 수행 상태를 확인하세요.");
    }
    ObjectNode record(JsonNode input) {
        synchronized (service) { return recordLocked(input); }
    }
    private ObjectNode recordLocked(JsonNode input) {
        LocalDate date = LocalDate.parse(Json.required(input, "date"));
        String workId = Json.required(input, "workId"), outcome = Json.required(input, "outcome");
        validateOutcome(outcome);
        ObjectNode current = day(date).stream().filter(row -> workId.equals(Json.text(row, "workId"))).findFirst().orElseThrow(() -> new IllegalArgumentException("수행 내용을 찾지 못했습니다."));
        ObjectNode row = Json.obj("id", date + ":" + workId, "date", date.toString(), "workId", workId, "taskId", Json.text(current, "taskId"), "title", Json.text(current, "title"), "jiraKey", Json.text(current, "jiraKey"),
                "outcome", outcome, "note", input.path("note").asText(Json.text(current, "note")), "updatedAt", service.time.instant().toString());
        return service.store.put("workDay", row);
    }
}
