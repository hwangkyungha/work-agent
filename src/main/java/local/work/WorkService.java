package local.work;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

final class WorkService {
    static final Pattern ISSUE = Pattern.compile("[A-Z][A-Z0-9_]*-[0-9]+");
    private final List<String> workTypes;
    final Store store;
    final KoreaTime time;
    final JiraClient jira;

    WorkService(Store store, KoreaTime time, JiraClient jira) { this(store, time, jira, Config.DEFAULT_WORK_TYPES); }
    WorkService(Store store, KoreaTime time, JiraClient jira, List<String> workTypes) { this.store = store; this.time = time; this.jira = jira; this.workTypes = List.copyOf(workTypes); }
    List<String> workTypes() { return workTypes; }
    private boolean validWorkType(String value) { return value.isBlank() || workTypes.contains(value); }
    private String day(JsonNode value) {
        String date = Json.text(value, "date");
        return date.isBlank() ? time.today().toString() : LocalDate.parse(date).toString();
    }
    private void stamp(ObjectNode value) { value.put("updatedAt", time.instant().toString()); }
    static String issue(String key) {
        key = key.trim().toUpperCase(Locale.ROOT);
        if (!key.isEmpty() && !ISSUE.matcher(key).matches()) throw new IllegalArgumentException("Jira 이슈 번호를 확인하세요: " + key);
        return key;
    }
    synchronized ObjectNode task(JsonNode input) {
        TaskIdentity identity = new TaskIdentity(store);
        String id = identity.resolve(Json.text(input, "id"));
        ObjectNode existing = id.isBlank() ? null : store.get("task", id);
        if (!id.isBlank() && existing == null && !id.startsWith("request:")) throw new IllegalArgumentException("수정할 업무를 찾지 못했습니다.");
        String requestedKey = issue(Json.text(input, "jiraKey"));
        if (existing == null && !requestedKey.isBlank()) {
            ObjectNode linked = identity.forKey(requestedKey);
            if (linked != null) {
                if (input.path("today").asBoolean()) planItem(Json.obj("date",day(input),"id",Json.text(linked,"id"),"included",true));
                return linked;
            }
        }
        ObjectNode row = existing == null ? Json.obj("status", "todo", "important", false, "urgent", false, "hidden", false, "product", "미분류", "jiraKey", "", "due", "", "createdAt", time.instant().toString()) : existing.deepCopy();
        if (!id.isBlank()) row.put("id", id);
        if (existing != null && !Json.text(existing, "jiraKey").isBlank() && input.hasNonNull("status") && !status(Json.text(existing, "status")).equals(status(Json.text(input, "status")))) throw new IllegalArgumentException("Jira 업무 상태는 상태 변경 메뉴에서 변경하세요.");
        for (String key : List.of("title", "product", "workType", "due", "status", "important", "urgent", "jiraKey", "hidden", "workGroup", "predecessorIds")) if (input.hasNonNull(key)) row.set(key, input.get(key));
        if (!validWorkType(Json.text(row, "workType")) && (existing == null || !Json.text(existing, "workType").equals(Json.text(row, "workType")))) throw new IllegalArgumentException("업무 성격을 확인하세요.");
        Json.required(row, "title");
        row.put("jiraKey", issue(Json.text(row, "jiraKey")));
        row.put("status", status(Json.text(row, "status")));
        String linkedKey = Json.text(row, "jiraKey");
        if (!linkedKey.isBlank() && jira.configured() && (existing == null || !linkedKey.equals(Json.text(existing, "jiraKey")))) {
            try {
                ObjectNode remote = jira.fetchIssue(linkedKey);
                store.put("issue", remote);
                for (String field : List.of("status", "jiraStatus", "jiraStatusId", "issueUrl", "issueType", "jiraParent", "jiraPredecessors")) row.set(field, remote.get(field));
            } catch (Exception e) { throw new IllegalStateException("Jira 이슈의 현재 상태를 확인하지 못했습니다. " + SlackConnection.friendly(e), e); }
        } else if (existing == null && !linkedKey.isBlank() && !Json.text(row, "status").equals("todo")) throw new IllegalArgumentException("Jira 연결을 설정한 뒤 실제 이슈 상태를 변경하세요.");
        String due = Json.text(row, "due");
        if (!due.isBlank()) {
            LocalDate date = LocalDate.parse(due);
            if (existing == null && !input.hasNonNull("urgent")) row.put("urgent", !date.isAfter(time.today().plusDays(1)));
        }
        if (Json.text(row, "id").isBlank()) row.put("id", UUID.randomUUID().toString());
        stamp(row);
        ObjectNode saved = saveTask(row);
        if (input.path("today").asBoolean()) planItem(Json.obj("date", day(input), "id", Json.text(saved, "id"), "included", true));
        return saved;
    }
    synchronized ObjectNode saveTask(ObjectNode row) {
        ObjectNode[] result = new ObjectNode[1];
        store.transaction(() -> result[0] = saveUniqueTask(new TaskIdentity(store).merge(row)));
        return result[0];
    }
    private ObjectNode saveUniqueTask(ObjectNode row) {
        TaskStructure.validate(row,store.list("task"));
        ObjectNode old = store.get("task", Json.text(row, "id"));
        String next = status(Json.text(row, "status"));
        if (old != null && !status(Json.text(old, "status")).equals(next)) {
            String at = row.path("jiraChangedAt").asText(time.instant().toString());
            store.put("taskChange", Json.obj("taskId", Json.text(row, "id"), "date", KoreaTime.dateOf(at).toString(), "at", at,
                    "from", status(Json.text(old, "status")), "to", next, "source", row.hasNonNull("jiraChangedAt") ? "jira" : Json.text(row, "jiraKey").isBlank() ? "local" : "observed"));
            row.put("statusChangedAt", at);
            if (next.equals("done") && (Json.text(row, "jiraKey").isBlank() || row.hasNonNull("jiraChangedAt"))) row.put("completedAt", at); else row.remove("completedAt");
        } else if (next.equals("done") && row.hasNonNull("jiraChangedAt")) row.put("completedAt", Json.text(row, "jiraChangedAt"));
        row.remove("jiraChangedAt");
        ObjectNode saved = store.put("task", row);
        if (old != null && List.of("title", "important", "urgent", "due", "status", "workGroup", "workType", "product").stream().anyMatch(field -> !old.path(field).equals(saved.path(field)))) {
            ObjectNode plan = store.get("plan", time.today().toString());
            if (plan != null && java.util.stream.StreamSupport.stream(plan.path("taskIds").spliterator(), false).anyMatch(id -> id.asText().equals(Json.text(saved, "id")))) plan(Json.obj("date", time.today().toString(), "taskIds", plan.get("taskIds")));
        }
        return saved;
    }
    static String status(String value) {
        return switch (value) { case "open", "todo" -> "todo"; case "doing", "blocked", "in-progress" -> "in-progress"; case "done" -> "done"; default -> throw new IllegalArgumentException("업무 상태를 확인하세요."); };
    }
    ObjectNode transitions(String id) throws Exception {
        ObjectNode task = requiredTask(id);
        if (Json.text(task, "jiraKey").isBlank()) return Json.obj("local", true, "status", status(Json.text(task, "status")));
        ObjectNode current = jira.fetchIssue(Json.text(task, "jiraKey"));
        jira.updateTask(this, task, current);
        return Json.obj("local", false, "current", current, "transitions", jira.transitions(Json.text(task, "jiraKey")));
    }
    synchronized ObjectNode changeStatus(JsonNode input) throws Exception {
        ObjectNode task = requiredTask(Json.required(input, "id"));
        String key = Json.text(task, "jiraKey");
        if (key.isBlank()) return task(Json.obj("id", Json.text(task, "id"), "status", Json.required(input, "status")));
        List<JsonNode> available = jira.transitions(key);
        String transitionId = Json.text(input, "transitionId");
        if (transitionId.isBlank()) {
            String wanted = Json.required(input, "status");
            List<JsonNode> matches = available.stream().filter(t -> wanted.equals(Json.text(t.path("to"), "name")) || wanted.equals(Json.text(t.path("to"), "id")) || wanted.equals(jira.statusCategory(t.path("to")))).toList();
            if (matches.size() != 1) throw new IllegalArgumentException("Jira에서 가능한 상태 중 하나를 골라 주세요: " + String.join(", ", available.stream().map(t -> Json.text(t.path("to"), "name")).distinct().toList()));
            transitionId = Json.text(matches.getFirst(), "id");
        }
        String chosenId = transitionId;
        JsonNode transition = available.stream().filter(t -> chosenId.equals(Json.text(t, "id"))).findFirst().orElseThrow(() -> new IllegalArgumentException("현재 Jira에서 가능한 상태 변경이 아닙니다. 목록을 다시 확인하세요."));
        for (JsonNode field : transition.path("fields")) if (field.path("required").asBoolean() && !field.hasNonNull("defaultValue") && !field.path("hasDefaultValue").asBoolean()) throw new IllegalArgumentException("이 상태 변경에는 추가 입력이 필요합니다. Jira 이슈 페이지에서 변경하세요.");
        jira.transition(key, chosenId);
        ObjectNode current = jira.fetchIssue(key);
        current.put("jiraChangedAt", time.instant().toString());
        return jira.updateTask(this, task, current);
    }
    private ObjectNode requiredTask(String id) {
        ObjectNode task = store.get("task", new TaskIdentity(store).resolve(id));
        if (task == null) throw new IllegalArgumentException("업무를 찾지 못했습니다.");
        return task;
    }
    synchronized ObjectNode hideTask(JsonNode input) {
        ObjectNode task = requiredTask(Json.required(input, "id"));
        task.put("hidden", input.path("hidden").asBoolean(true)); stamp(task);
        return store.put("task", task);
    }
    synchronized ObjectNode hideTasks(JsonNode input) {
        JsonNode ids = input.path("taskIds");
        if (!ids.isArray() || ids.isEmpty()) throw new IllegalArgumentException("숨기거나 복원할 업무를 선택하세요.");
        List<ObjectNode> rows = new ArrayList<>();
        for (JsonNode id : ids) rows.add(requiredTask(id.asText()));
        store.transaction(() -> rows.forEach(row -> { row.put("hidden", input.path("hidden").asBoolean(true)); stamp(row); store.put("task", row); }));
        return Json.obj("count", rows.size());
    }
    synchronized ObjectNode groupTasks(JsonNode input) {
        JsonNode ids = input.path("taskIds");
        if (!ids.isArray() || ids.isEmpty()) throw new IllegalArgumentException("묶을 업무를 선택하세요.");
        if (!input.has("workGroup") && !input.has("workType")) throw new IllegalArgumentException("변경할 업무 묶음이나 성격을 지정하세요.");
        String name = Json.text(input, "workGroup").trim();
        String type=Json.text(input,"workType");
        if(input.has("workType") && !validWorkType(type))throw new IllegalArgumentException("업무 성격을 확인하세요.");
        List<ObjectNode> rows = new ArrayList<>();
        for (JsonNode id : ids) {
            ObjectNode row = requiredTask(id.asText()).deepCopy();
            if(input.has("workGroup"))row.put("workGroup", name);
            if(input.has("workType"))row.put("workType",type);
            TaskStructure.validate(row, store.list("task")); rows.add(row);
        }
        store.transaction(() -> rows.forEach(row -> { stamp(row); store.put("task", row); }));
        return Json.obj("count", rows.size());
    }
    synchronized ObjectNode removeTasks(JsonNode input) {
        JsonNode ids = input.path("taskIds");
        if (!ids.isArray() || ids.isEmpty()) throw new IllegalArgumentException("삭제하거나 숨길 업무를 선택하세요.");
        List<ObjectNode> rows = new ArrayList<>(); Set<String> unique = new LinkedHashSet<>();
        ids.forEach(id -> unique.add(id.asText()));
        for (String id : unique) rows.add(requiredTask(id));
        Set<String> deleted = new HashSet<>();
        for (ObjectNode row : rows) if (Json.text(row, "jiraKey").isBlank()) deleted.add(Json.text(row, "id"));
        store.transaction(() -> {
            for (ObjectNode row : rows) {
                if (deleted.contains(Json.text(row, "id"))) store.delete("task", Json.text(row, "id"));
                else { row.put("hidden", true); stamp(row); store.put("task", row); }
            }
            for (ObjectNode task : store.list("task")) if (task.has("predecessorIds")) {
                List<String> previous = TaskStructure.predecessors(task);
                List<String> remaining = previous.stream().filter(id -> !deleted.contains(id)).toList();
                if (remaining.size() != previous.size()) { task.set("predecessorIds", Json.MAPPER.valueToTree(remaining)); stamp(task); store.put("task", task); }
            }
            for (ObjectNode plan : store.list("plan")) {
                List<String> previous = new ArrayList<>(); plan.path("taskIds").forEach(id -> previous.add(id.asText()));
                List<String> remaining = previous.stream().filter(id -> !deleted.contains(id)).toList();
                if (remaining.size() != previous.size()) {
                    plan.set("taskIds", Json.MAPPER.valueToTree(remaining)); plan.put("version", plan.path("version").asInt() + 1); stamp(plan); store.put("plan", plan);
                }
            }
        });
        return Json.obj("deleted", deleted.size(), "hidden", rows.size() - deleted.size());
    }
    ObjectNode activity(JsonNode input) {
        String occurred = Json.text(input, "occurredAt");
        String date = occurred.isBlank() ? day(input) : KoreaTime.dateOf(occurred).toString();
        ObjectNode row = Json.obj("title", Json.required(input, "title"), "date", date,
                "jiraKey", issue(Json.text(input, "jiraKey")), "product", input.path("product").asText("미분류"),
                "source", input.path("source").asText("manual"), "sourceRef", Json.text(input, "sourceRef"),
                "occurredAt", occurred.isBlank() ? LocalDate.parse(date).atTime(time.now().toLocalTime()).atZone(KoreaTime.ZONE).toInstant().toString() : KoreaTime.parse(occurred).toString());
        if (input.hasNonNull("id")) row.set("id", input.get("id"));
        String taskId = Json.text(input, "taskId");
        if (!taskId.isBlank()) { ObjectNode task = requiredTask(taskId); row.put("taskId", Json.text(task,"id")).put("jiraKey", Json.text(task, "jiraKey")).put("userLinked", true); }
        stamp(row);
        return store.put("activity", row);
    }
    synchronized ObjectNode plan(JsonNode input) {
        String date = day(input);
        JsonNode suppliedIds = input.path("taskIds");
        if (!suppliedIds.isArray()) throw new IllegalArgumentException("오늘 할 업무 목록을 입력하세요.");
        Set<String> resolvedIds = new LinkedHashSet<>(); TaskIdentity identity = new TaskIdentity(store);
        Set<String> supplied = new HashSet<>();
        suppliedIds.forEach(value -> {
            if (!supplied.add(value.asText())) throw new IllegalArgumentException("계획의 업무 번호를 확인하세요.");
            resolvedIds.add(identity.resolve(value.asText()));
        });
        JsonNode ids = Json.MAPPER.valueToTree(resolvedIds);
        Set<String> seen = new HashSet<>();
        for (JsonNode id : ids) {
            if (store.get("task", id.asText()) == null || !seen.add(id.asText())) throw new IllegalArgumentException("계획의 업무 번호를 확인하세요.");
        }
        ObjectNode old = store.get("plan", date);
        if (input.has("version") && input.path("version").asInt() != (old == null ? 0 : old.path("version").asInt())) throw new IllegalArgumentException("다른 곳에서 오늘 할 일이 바뀌었습니다. 새 목록을 확인하고 다시 조정하세요.");
        String sort = input.path("sort").asText(old == null ? "manual" : old.path("sort").asText("manual"));
        if (!Set.of("manual", "priority", "due", "created-asc", "created-desc").contains(sort)) throw new IllegalArgumentException("정렬 기준을 확인하세요.");
        ObjectNode row = Json.obj("id", date, "date", date, "taskIds", ids, "sort", sort, "version", old == null ? 1 : old.path("version").asInt() + 1);
        List<ObjectNode> selectedTasks = new ArrayList<>(); ids.forEach(id -> selectedTasks.add(requiredTask(id.asText()).deepCopy()));
        row.set("initialTasks", old != null && old.has("initialTasks") ? old.get("initialTasks") : Json.MAPPER.valueToTree(old == null ? selectedTasks : planned(LocalDate.parse(date))));
        Map<String, JsonNode> snapshots = new LinkedHashMap<>();
        if (old != null) old.path("taskSnapshots").forEach(task -> snapshots.put(Json.text(task, "id"), task));
        row.path("initialTasks").forEach(task -> snapshots.putIfAbsent(Json.text(task, "id"), task));
        selectedTasks.forEach(task -> snapshots.put(Json.text(task, "id"), task));
        row.set("taskSnapshots", Json.MAPPER.valueToTree(snapshots.values()));
        stamp(row);
        store.transaction(() -> { store.put("plan", row); ObjectNode revision = row.deepCopy(); revision.put("id", date + ":" + row.path("version").asInt()); store.put("planRevision", revision); });
        return row;
    }
    synchronized ObjectNode planItem(JsonNode input) {
        LocalDate date = LocalDate.parse(day(input));
        ObjectNode task = requiredTask(Json.required(input, "id"));
        if (input.path("included").asBoolean(true) && (task.path("hidden").asBoolean() || status(Json.text(task, "status")).equals("done"))) throw new IllegalArgumentException("숨기거나 완료한 업무는 오늘 할 일에 추가할 수 없습니다.");
        ObjectNode plan = currentPlan(date);
        List<String> ids = new ArrayList<>(); plan.path("taskIds").forEach(n -> ids.add(n.asText()));
        String id = Json.text(task, "id");
        if (input.path("included").asBoolean(true) && ids.contains(id)) return plan;
        ids.remove(id);
        if (input.path("included").asBoolean(true)) ids.add(id);
        return plan(Json.obj("date", date.toString(), "taskIds", ids));
    }
    synchronized ObjectNode currentPlan(LocalDate date) {
        ObjectNode row = store.get("plan", date.toString());
        return row == null ? Json.obj("date", date.toString(), "taskIds", List.of(), "sort", "manual", "version", 0) : row;
    }
    synchronized ObjectNode planItems(JsonNode input) {
        if (!input.path("taskIds").isArray()) throw new IllegalArgumentException("오늘 추가할 업무를 선택하세요.");
        LocalDate date=LocalDate.parse(day(input));
        Set<String> selected=new LinkedHashSet<>();
        for(JsonNode value:input.path("taskIds")) {
            ObjectNode task=requiredTask(value.asText());
            if(task.path("hidden").asBoolean() || status(Json.text(task,"status")).equals("done")) throw new IllegalArgumentException("숨기거나 완료한 업무는 오늘 할 일에 추가할 수 없습니다.");
            selected.add(Json.text(task,"id"));
        }
        ObjectNode current=currentPlan(date);
        Set<String> ids=new LinkedHashSet<>(); current.path("taskIds").forEach(id->ids.add(id.asText()));
        int before=ids.size(); ids.addAll(selected);
        ObjectNode saved=ids.size()==before ? current : plan(Json.obj("date",date.toString(),"taskIds",ids,"sort",current.path("sort").asText("manual")));
        return Json.obj("added",ids.size()-before,"plan",saved);
    }
    ObjectNode dailyNote(JsonNode input) {
        String date = day(input);
        ObjectNode old = store.get("daily", date);
        ObjectNode row = old == null ? Json.obj("id", date, "date", date) : old.deepCopy();
        for (String field : List.of("text", "problems", "help", "decisions")) if (input.has(field)) row.put(field, Json.text(input, field));
        stamp(row);
        return store.put("daily", row);
    }
    List<ObjectNode> tasks() {
        return allTasks().stream().filter(n -> !n.path("hidden").asBoolean()).toList();
    }
    private List<ObjectNode> allTasks() {
        return store.list("task").stream().map(n -> { n.put("status", status(Json.text(n, "status"))); if (!Json.text(n, "jiraKey").isBlank() && Json.text(n, "issueUrl").isBlank()) n.put("issueUrl", jira.issueUrl(Json.text(n, "jiraKey"))); return creationMetadata(n); }).sorted(Comparator
                .comparingInt((ObjectNode n) -> "done".equals(Json.text(n, "status")) ? 1 : 0)
                .thenComparingInt(n -> (n.path("urgent").asBoolean() ? 0 : 2) + (n.path("important").asBoolean() ? 0 : 1))
                .thenComparing(n -> Json.text(n, "due").isBlank() ? "9999" : Json.text(n, "due"))).toList();
    }
    List<ObjectNode> activities(LocalDate start, LocalDate end) {
        return store.between("activity", start.toString(), end.toString()).stream().sorted(Comparator.comparing(n -> Json.text(n, "occurredAt"))).toList();
    }
    List<ObjectNode> meetings(LocalDate day) {
        Instant from = day.atStartOfDay(KoreaTime.ZONE).toInstant(), until = day.plusDays(1).atStartOfDay(KoreaTime.ZONE).toInstant();
        return store.list("meeting").stream().filter(n -> Json.text(n,"source").equals("calendar")).filter(n -> {
            Instant start = KoreaTime.parse(Json.text(n, "start"));
            String end = Json.text(n, "end");
            return start.isBefore(until) && (end.isBlank() ? !start.isBefore(from) : KoreaTime.parse(end).isAfter(from));
        }).sorted(Comparator.comparing(n -> Json.text(n, "start"))).toList();
    }
    List<ObjectNode> planned(LocalDate day) {
        ObjectNode plan = currentPlan(day);
        Map<String, ObjectNode> historical = new HashMap<>();
        if (day.isBefore(time.today())) for (JsonNode task : plan.path("taskSnapshots")) historical.put(Json.text(task, "id"), (ObjectNode) task.deepCopy());
        List<ObjectNode> result = new ArrayList<>();
        for (JsonNode id : plan.path("taskIds")) {
            ObjectNode task = historical.getOrDefault(id.asText(), store.get("task", id.asText())); if (task != null && (day.isBefore(time.today()) || !task.path("hidden").asBoolean())) { task.put("status", status(Json.text(task, "status"))); result.add(creationMetadata(task)); }
        }
        String sort = plan.path("sort").asText("manual");
        if (sort.startsWith("created-")) result.sort(Comparator.comparing(WorkService::creationTime, Comparator.nullsLast(sort.equals("created-desc") ? Comparator.<Instant>reverseOrder() : Comparator.<Instant>naturalOrder())));
        else if (!sort.equals("manual")) result.sort(sort.equals("due") ? Comparator.comparing((ObjectNode n) -> Json.text(n, "due").isBlank() ? "9999" : Json.text(n, "due")).thenComparingInt(WorkService::priority) : Comparator.comparingInt(WorkService::priority).thenComparing(n -> Json.text(n, "due").isBlank() ? "9999" : Json.text(n, "due")));
        return result;
    }
    private static int priority(ObjectNode task) { return (task.path("urgent").asBoolean() ? 0 : 2) + (task.path("important").asBoolean() ? 0 : 1); }
    private ObjectNode creationMetadata(ObjectNode task) {
        task.put("groupName", TaskStructure.group(task));
        String created = Json.text(task, "createdAt");
        if (created.isBlank() && !Json.text(task, "jiraKey").isBlank()) {
            ObjectNode issue = store.get("issue", Json.text(task, "jiraKey"));
            if (issue != null) created = Json.text(issue, "createdAt");
        }
        if (!created.isBlank()) task.put("createdAt", JiraClient.instant(created).toString());
        return task;
    }
    private static Instant creationTime(ObjectNode task) { return Json.text(task, "createdAt").isBlank() ? null : JiraClient.instant(Json.text(task, "createdAt")); }
    List<ObjectNode> completed(LocalDate day) {
        return tasks().stream().filter(n -> Json.text(n, "status").equals("done") && !Json.text(n, "completedAt").isBlank() && KoreaTime.dateOf(Json.text(n, "completedAt")).equals(day)).toList();
    }
    synchronized ObjectNode acceptFollowup(JsonNode input) {
        ObjectNode suggestion = store.get("followup", Json.required(input, "id"));
        if (suggestion == null) throw new IllegalArgumentException("후속 작업 제안을 찾지 못했습니다.");
        if (suggestion.path("dismissed").asBoolean()) throw new IllegalArgumentException("제외한 후속 작업 제안입니다.");
        if (suggestion.hasNonNull("acceptedTaskId")) {
            ObjectNode existing = store.get("task", Json.text(suggestion, "acceptedTaskId"));
            if (existing != null) { planItem(Json.obj("date", day(input), "id", Json.text(existing, "id"), "included", true)); return existing; }
        }
        String id = "request:followup:" + Json.text(suggestion, "id");
        ObjectNode result = task(Json.obj("id", id, "title", Json.required(suggestion, "title"), "today", true, "date", day(input)));
        result.put("sourceTaskId", Json.text(suggestion, "sourceTaskId"));
        result.put("followupReason", Json.text(suggestion, "reason")); store.put("task", result);
        if(suggestion.hasNonNull("sourceDraftResultId")) {result.put("sourceDraftResultId",Json.text(suggestion,"sourceDraftResultId")).put("sourcePrUrl",Json.text(suggestion,"sourcePrUrl"));store.put("task",result);}
        suggestion.put("acceptedTaskId", Json.text(result, "id")); store.put("followup", suggestion);
        return result;
    }
    synchronized ObjectNode reviewDraftResult(JsonNode input) {
        synchronized(store) {
            ObjectNode row=store.get("draftResult",Json.required(input,"id"));
            if(row==null)throw new IllegalArgumentException("검토할 draft PR을 찾지 못했습니다.");
            row.put("reviewed",input.path("reviewed").asBoolean(true));stamp(row);store.put("draftResult",row);
            ObjectNode suggestion=store.get("followup",Json.text(row,"reviewSuggestionId"));
            ObjectNode task=suggestion==null?null:store.get("task",Json.text(suggestion,"acceptedTaskId"));
            if(row.path("open").asBoolean(true) && task!=null && Json.text(task,"jiraKey").isBlank()) {task.put("status",row.path("reviewed").asBoolean()?"done":"todo");stamp(task);saveTask(task);}
            return row;
        }
    }
    synchronized ObjectNode dismissFollowup(JsonNode input) {
        ObjectNode row=store.get("followup",Json.required(input,"id"));
        if(row==null||row.hasNonNull("acceptedTaskId"))throw new IllegalArgumentException("아직 등록하지 않은 제안만 제외할 수 있습니다.");
        row.put("dismissed",true);return store.put("followup",row);
    }
    synchronized ObjectNode linkWork(JsonNode input) {
        LocalDate date = LocalDate.parse(Json.required(input, "date"));
        ObjectNode work = new WorkLedger(this).day(date).stream().filter(row -> new TaskIdentity(store).resolve(Json.required(input, "workId")).equals(Json.text(row, "workId"))).findFirst().orElseThrow(() -> new IllegalArgumentException("연결할 활동을 찾지 못했습니다."));
        ObjectNode task = input.path("create").asBoolean() ? task(Json.obj("title", Json.required(work, "title"), "jiraKey", Json.text(input,"jiraKey"))) : requiredTask(Json.required(input, "taskId"));
        String taskId = Json.text(task, "id");
        store.put("workLink", Json.obj("id", Json.text(work,"workId"), "taskId", taskId));
        for (JsonNode session : work.path("evidence").path("sessions")) store.put("sessionLink", Json.obj("id", Json.text(session, "id"), "taskId", taskId));
        String workId = Json.text(work, "workId");
        if (workId.startsWith("activity:")) {
            ObjectNode activity = store.get("activity", workId.substring(9));
            if (activity != null) { activity.put("taskId", taskId).put("jiraKey", Json.text(task, "jiraKey")).put("userLinked", true); store.put("activity", activity); }
        }
        ObjectNode note = store.get("workDay", date + ":" + workId);
        if (note != null) { store.delete("workDay", Json.text(note, "id")); note.put("id", date + ":" + taskId).put("workId", taskId).put("taskId", taskId); store.put("workDay", note); }
        for (ObjectNode day : store.list("worklog")) {
            boolean changed = false;
            for (JsonNode item : day.path("items")) if (workId.equals(Json.text(item,"workId")) && !Set.of("posted","posting","unknown").contains(Json.text(item,"state"))) {
                ObjectNode entry = (ObjectNode) item;
                entry.put("workId",taskId).put("taskId",taskId).put("title",Json.text(task,"title"));
                if (Json.text(entry,"jiraKey").isBlank()) entry.put("jiraKey",Json.text(task,"jiraKey"));
                changed = true;
            }
            if (changed) store.put("worklog",day);
        }
        return task;
    }
    List<ObjectNode> todayGroups(LocalDate day) {
        Set<String> selected = new HashSet<>();
        for (ObjectNode task : planned(day)) selected.add(Json.text(task, "id"));
        Map<String, String> recentKeys = new HashMap<>();
        for (ObjectNode group : new ActivitySummary(store).group(day.minusDays(3), day.minusDays(1))) {
            if (!Json.text(group, "jiraKey").isBlank() && group.path("sessionCount").asInt() > 0 && group.path("assistantMessages").asInt() > 0) recentKeys.merge(Json.text(group, "jiraKey"), Json.text(group,"lastActivityAt").isBlank()?Json.text(group, "date"):Json.text(group,"lastActivityAt"), (a, b) -> a.compareTo(b) >= 0 ? a : b);
        }
        Set<String> finished=new WorkLedger(this).finishedTasks(day);
        List<ObjectNode> candidates = tasks().stream().filter(t -> !Json.text(t, "status").equals("done") && !selected.contains(Json.text(t, "id")) && !finished.contains(Json.text(t,"id"))).toList();
        List<ObjectNode> recent = candidates.stream().filter(t -> Json.text(t, "status").equals("in-progress") && recentKeys.containsKey(Json.text(t, "jiraKey")))
                .sorted(Comparator.comparing((ObjectNode t) -> recentKeys.get(Json.text(t, "jiraKey"))).reversed()).map(t->{ObjectNode row=t.deepCopy();String at=recentKeys.get(Json.text(t,"jiraKey"));row.put("lastWorkedAt",at).put("lastWorkedDate",at.length()==10?at:KoreaTime.dateOf(at).toString());return row;}).toList();
        List<ObjectNode> due = candidates.stream().filter(t -> !Json.text(t, "due").isBlank() && Json.text(t, "due").compareTo(day.toString()) >= 0 && Json.text(t, "due").compareTo(day.plusDays(7).toString()) <= 0)
                .sorted(Comparator.comparing(t -> Json.text(t, "due"))).toList();
        List<ObjectNode> priority = candidates.stream().filter(t -> t.path("important").asBoolean() || t.path("urgent").asBoolean()).toList();
        return List.of(Json.obj("id", "recent", "title", "최근 진행 작업", "description", day.minusDays(3) + " ~ " + day.minusDays(1) + "의 작업 세션과 연결된 In Progress 업무입니다.", "tasks", recent),
                Json.obj("id", "due", "title", "마감이 다가오는 일", "description", "오늘부터 7일 이내에 마감되는 업무입니다.", "tasks", due),
                Json.obj("id", "priority", "title", "중요도/긴급도가 높은 일", "description", "중요하거나 긴급하다고 직접 표시한 업무입니다.", "tasks", priority));
    }
    ObjectNode state(LocalDate date) {
        LocalDate monday = time.monday(date);
        ObjectNode daily = store.get("daily", date.toString());
        ObjectNode plan = currentPlan(date);
        WorkLedger ledger = new WorkLedger(this);
        LocalDate previous = new Reports(this).previousWorkDate(date);
        return Json.obj("date", date.toString(), "timezone", KoreaTime.ZONE.toString(), "now", time.now().toString(),
                "tasks", tasks(), "plan", planned(date), "planSaved", store.get("plan", date.toString()) != null, "planVersion", plan.path("version").asInt(), "planSort", plan.path("sort").asText("manual"), "yesterdayCompleted", completed(date.minusDays(1)),
                "hiddenTasks", allTasks().stream().filter(n -> n.path("hidden").asBoolean()).toList(), "todayGroups", todayGroups(date),
                "draftDigest", DraftResults.state(this,date),
                "taskAliases", store.list("taskAlias"), "taskMergeConflicts", store.list("taskMergeConflict"), "todayWork", ledger.reviewRows(date), "previousWorkDate", previous.toString(), "previousWork", ledger.reviewRows(previous), "carryover", ledger.carryover(date),
                "worklogCandidates", java.util.stream.IntStream.range(0, 7).boxed().flatMap(i -> ledger.worklogCandidates(monday.plusDays(i)).stream()).toList(),
                "activities", activities(monday.minusDays(7), monday.plusDays(6)), "meetings", meetings(date),
                "activityGroups", ledger.worklogEvidence(monday, monday.plusDays(6)), "followups", store.list("followup").stream().filter(n -> (Json.text(n,"kind").equals("followup") || Json.text(n, "date").equals(date.toString())) && !n.hasNonNull("acceptedTaskId") && !n.path("dismissed").asBoolean()).toList(),
                "worklogs", store.list("worklog").stream().filter(n -> Json.text(n, "date").compareTo(monday.toString()) >= 0 && Json.text(n, "date").compareTo(monday.plusDays(6).toString()) <= 0).toList(),
                "registeredWorklogs", registeredWorklogs(monday, monday.plusDays(6)), "worklogFavorites", store.list("worklogFavorite"), "recentWorklogIssues", recentWorklogIssues(),
                "dailyNote", daily == null ? "" : Json.text(daily, "text"), "dailyDetails", daily == null ? Json.object() : daily, "jobs", store.list("job"),
                "jiraIssues", store.list("issue"), "sync", store.list("sync"));
    }
    synchronized ObjectNode worklogFavorite(JsonNode input) throws Exception {
        String key = issue(Json.required(input, "jiraKey"));
        if (!input.path("favorite").asBoolean(true)) { store.delete("worklogFavorite", key); return Json.obj("jiraKey", key, "favorite", false); }
        ObjectNode issue = store.get("issue", key);
        if (issue == null) { issue = jira.fetchIssue(key); store.put("issue", issue); }
        return store.put("worklogFavorite", Json.obj("id", key, "jiraKey", key, "title", Json.text(issue, "title"), "issueUrl", jira.issueUrl(key)));
    }
    List<ObjectNode> registeredWorklogs(LocalDate from, LocalDate to) {
        Map<String, ObjectNode> rows = new LinkedHashMap<>();
        List<ObjectNode> days = store.between("worklog", from.toString(), to.toString());
        Set<String> localIds = new HashSet<>();
        for (ObjectNode day : days) for (JsonNode item : day.path("items")) if (Json.text(item, "state").equals("posted")) {
            String jiraId = Json.text(item, "jiraId"), key = Json.text(item, "jiraKey");
            localIds.add(jiraId);
            ObjectNode sync = store.get("sync", "worklogs:" + time.monday(LocalDate.parse(Json.text(day, "date"))));
            String registeredAt = item.path("registeredAt").asText(Json.text(day, "updatedAt"));
            if (sync != null && !Json.text(sync, "lastSuccessAt").isBlank() && (item.path("snapshotSeen").asBoolean() || (!registeredAt.isBlank() && !KoreaTime.parse(registeredAt).isAfter(KoreaTime.parse(Json.text(sync, "lastSuccessAt")))))) continue;
            ObjectNode issue = store.get("issue", key);
            rows.put(jiraId, Json.obj("jiraId", jiraId, "jiraKey", key, "date", Json.text(day, "date"), "seconds", item.path("seconds").asLong(), "comment", Json.text(item, "comment"),
                    "title", issue == null ? "" : Json.text(issue, "title"), "issueUrl", jira.issueUrl(key), "source", "work-agent", "confirmed", false));
        }
        for (ObjectNode remote : store.between("jiraWorklog", from.toString(), to.toString())) {
            String id = Json.text(remote, "jiraId");
            ObjectNode row = remote.deepCopy(); row.put("source", localIds.contains(id) ? "work-agent" : "jira").put("confirmed", true); rows.put(id, row);
        }
        return rows.values().stream().sorted(Comparator.comparing((ObjectNode row) -> Json.text(row, "date")).thenComparing(row -> Json.text(row, "jiraKey"))).toList();
    }
    List<ObjectNode> recentWorklogIssues() {
        Map<String, ObjectNode> issues = new HashMap<>();
        for (ObjectNode row : registeredWorklogs(time.today().minusDays(90), time.today())) {
            String key = Json.text(row, "jiraKey"); ObjectNode old = issues.get(key);
            if (old == null || Json.text(row, "date").compareTo(Json.text(old, "lastDate")) > 0) issues.put(key, Json.obj("jiraKey", key, "title", Json.text(row, "title"), "issueUrl", Json.text(row, "issueUrl"), "lastDate", Json.text(row, "date")));
        }
        return issues.values().stream().sorted(Comparator.comparing((ObjectNode row) -> Json.text(row, "lastDate")).reversed().thenComparing(row -> Json.text(row, "jiraKey"))).limit(20).toList();
    }
    synchronized ObjectNode saveWorklog(JsonNode input) {
        String date = day(input);
        ObjectNode old = store.get("worklog", date);
        JsonNode entries = input.path("items");
        if (!entries.isArray()) throw new IllegalArgumentException("이슈와 작업 시간을 입력하세요.");
        List<JsonNode> previous = new ArrayList<>();
        if (old != null) old.path("items").forEach(previous::add);
        Set<String> keys = new HashSet<>(), retained = new HashSet<>();
        ArrayNode items = Json.MAPPER.createArrayNode();
        long total = 0;
        for (JsonNode entry : entries) {
            String key = issue(Json.text(entry, "jiraKey"));
            String workId = new TaskIdentity(store).resolve(Json.text(entry, "workId"));
            String title = Json.text(entry, "title");
            if (key.isBlank() && (workId.isBlank() || title.isBlank())) throw new IllegalArgumentException("Jira가 연결되지 않은 기록에는 업무와 제목이 필요합니다.");
            String duration = Json.text(entry, "duration");
            int seconds = duration.isBlank() ? 0 : WorkDuration.seconds(duration);
            String marker = Json.text(entry, "marker");
            JsonNode prior = previous.stream().filter(n -> (!marker.isBlank() && marker.equals(Json.text(n, "marker"))) || (marker.isBlank() && (!key.isBlank() ? key.equals(Json.text(n, "jiraKey")) : workId.equals(Json.text(n, "workId"))))).findFirst().orElse(null);
            if ((prior == null || !Set.of("posted", "posting", "unknown").contains(Json.text(prior, "state"))) && !keys.add(key.isBlank() ? "work:" + workId : key) && !(prior != null && !marker.isBlank())) throw new IllegalArgumentException("작성 중인 같은 업무는 한 번만 입력하세요.");
            String comment = entry.path("comment").asText(prior == null ? "업무 기록" : Json.text(prior, "comment"));
            ObjectNode row;
            if (prior != null && Set.of("posted", "posting", "unknown").contains(Json.text(prior, "state"))) {
                if (!key.equals(Json.text(prior, "jiraKey")) || seconds != prior.path("seconds").asInt() || !comment.equals(Json.text(prior, "comment"))) throw new IllegalArgumentException("이미 Jira에 등록했거나 결과 확인 중인 기록은 변경할 수 없습니다.");
                row = ((ObjectNode) prior).deepCopy();
            } else {
                row = Json.obj("jiraKey", key, "seconds", seconds, "comment", comment, "marker", prior == null ? (marker.isBlank() ? "work-agent:" + UUID.randomUUID() : marker) : Json.text(prior, "marker"), "state", "draft", "jiraId", "");
                row.put("workId", workId).put("title", title).put("taskId", new TaskIdentity(store).resolve(Json.text(entry, "taskId")));
            }
            row.remove("percent");
            if (!retained.add(Json.text(row, "marker"))) throw new IllegalArgumentException("같은 워크로그 기록을 두 번 저장할 수 없습니다.");
            total += seconds; items.add(row);
        }
        for (JsonNode prior : previous) if (Set.of("posted", "posting", "unknown").contains(Json.text(prior, "state")) && !retained.contains(Json.text(prior, "marker"))) throw new IllegalArgumentException("이미 등록했거나 결과 확인 중인 기록을 삭제할 수 없습니다.");
        ObjectNode row = Json.obj("id", date, "date", date, "totalSeconds", total, "items", items);
        if (items.isEmpty()) { store.delete("worklog", date); return row; }
        stamp(row);
        return store.put("worklog", row);
    }
    synchronized ObjectNode postWorklog(LocalDate date) throws Exception {
        ObjectNode row = store.get("worklog", date.toString());
        if (row != null) for (JsonNode item : row.path("items")) if (!Json.text(item, "state").equals("posted") && Json.text(item, "jiraKey").isBlank()) throw new IllegalArgumentException(Json.text(item, "title") + "에 Jira 이슈를 연결한 뒤 등록하세요. 연결 전에는 초안으로 보관할 수 있습니다.");
        if (row == null) throw new IllegalArgumentException("먼저 이 날짜의 작업 시간을 저장하세요.");
        if (!jira.configured()) throw new IllegalArgumentException("Jira 연결 정보를 config.properties에 설정하세요.");
        for (JsonNode item : row.path("items")) if (!Json.text(item, "state").equals("posted") && item.path("seconds").asInt() <= 0) throw new IllegalArgumentException(Json.text(item, "jiraKey") + "의 시간을 입력하세요. 시간 없는 목록은 초안으로만 저장할 수 있습니다.");
        for (JsonNode n : row.path("items")) {
            ObjectNode item = (ObjectNode) n;
            String state = Json.text(item, "state");
            if ("posted".equals(state)) continue;
            if (Set.of("posting", "unknown").contains(state)) {
                String found = jira.findMarker(Json.text(item, "jiraKey"), Json.text(item, "marker"));
                if (found.isBlank()) throw new IllegalStateException("Jira 등록 결과가 불확실합니다. 중복 등록을 막기 위해 멈췄습니다. 해당 날짜의 Jira 워크로그를 확인하세요.");
                item.put("jiraId", found).put("state", "posted").put("registeredAt", time.instant().toString()); store.put("worklog", row); continue;
            }
            item.put("state", "posting"); store.put("worklog", row);
            try {
                String id = jira.addWorklog(date, item);
                item.put("jiraId", id).put("state", "posted").put("registeredAt", time.instant().toString()).remove("error");
                store.put("worklog", row);
            } catch (JiraClient.Rejected e) {
                item.put("state", "failed").put("error", e.getMessage()); store.put("worklog", row); throw e;
            } catch (Exception e) {
                item.put("state", "unknown").put("error", "등록 결과를 확인해야 합니다."); store.put("worklog", row); throw e;
            }
        }
        stamp(row);
        return store.put("worklog", row);
    }
    ObjectNode member(JsonNode input) {
        String id = Json.text(input, "id");
        ObjectNode row = Json.obj("name", Json.required(input, "name"), "jiraUser", Json.required(input, "jiraUser"));
        if (!id.isBlank()) row.put("id", id);
        stamp(row); return store.put("member", row);
    }
    ObjectNode observation(JsonNode input) {
        String member = Json.required(input, "memberId");
        if (store.get("member", member) == null) throw new IllegalArgumentException("팀원을 먼저 등록하세요.");
        String type = input.path("type").asText("관찰");
        if (!Set.of("관찰", "칭찬", "피드백", "후속 조치").contains(type)) throw new IllegalArgumentException("관찰 기록 종류를 확인하세요.");
        ObjectNode row = Json.obj("memberId", member, "date", day(input), "type", type,
                "text", Json.required(input, "text"), "followUp", Json.text(input, "followUp"));
        stamp(row); return store.put("observation", row);
    }
    List<ObjectNode> members() { return store.list("member").stream().map(n -> { n.remove("role"); return n; }).toList(); }
    ObjectNode team() { return Json.obj("members", members(), "observations", store.list("observation"), "snapshots", store.list("teamSnapshot")); }
    ObjectNode job(JsonNode input) {
        ObjectNode row = Json.obj("title", Json.required(input, "title"), "jiraKey", issue(Json.text(input, "jiraKey")),
                "result", Json.required(input, "result"), "artifact", Json.text(input, "artifact"), "verification", Json.text(input, "verification"),
                "reviewed", input.path("reviewed").asBoolean(false), "date", day(input));
        if (!Json.text(input, "id").isBlank()) row.set("id", input.get("id"));
        stamp(row); return store.put("job", row);
    }
}
