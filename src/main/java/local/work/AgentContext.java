package local.work;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

final class AgentContext {
    private static final Pattern ISSUE_KEY = Pattern.compile("(?i)\\b[A-Z][A-Z0-9]+-\\d+\\b");
    private static final Pattern DATE = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final String[] TASK_FIELDS = {"id", "title", "jiraKey", "status", "jiraStatus", "due", "important", "urgent", "hidden", "product", "workType", "workGroup", "predecessorIds"};
    private final WorkService service;

    AgentContext(WorkService service) { this.service = service; }

    ObjectNode forRequest(String request, List<ObjectNode> history) {
        String query = request.toLowerCase(Locale.ROOT);
        if (query.matches("(?s).*(아까|방금|앞에서|그거|그 일|그 업무|번째|위의|그걸).*")) {
            query += " " + history.stream().filter(row -> Json.text(row, "role").equals("user"))
                    .map(row -> Json.text(row, "text")).reduce((first, last) -> last).orElse("").toLowerCase(Locale.ROOT);
        }
        LocalDate day = requestedDate(request, service.time.today());
        ObjectNode context = Json.obj("date", day.toString());
        if (query.matches("(?s).*(계획|한 일|수행|활동|워크로그|로그워크|worklog|브리핑|데일리|이어|진행하지|못한).*") ) {
            WorkLedger ledger=new WorkLedger(service);
            context.set("actualWork",Json.MAPPER.valueToTree(ledger.reviewRows(day).stream().limit(24).map(row->fields(row,"date","workId","taskId","title","jiraKey","planned","observed","performed","outcome","note")).toList()));
            if (planningRequest(query) && day.equals(service.time.today())) {
                LocalDate previous=new Reports(service).previousWorkDate(day);
                context.set("previousWork",Json.MAPPER.valueToTree(ledger.reviewRows(previous).stream().limit(24).map(row->fields(row,"date","workId","taskId","title","jiraKey","planned","observed","performed","outcome","note")).toList()));
            }
            if(planningRequest(query))context.set("carryover",Json.MAPPER.valueToTree(ledger.carryover(day).stream().limit(12).map(row->fields(row,"date","workId","taskId","title","jiraKey","reason")).toList()));
        }
        Set<String> issueKeys = new HashSet<>();
        ISSUE_KEY.matcher(query).results().forEach(match -> issueKeys.add(match.group()));
        boolean planning = query.matches("(?s).*(계획|할\\s*일|우선순위|마감|진행\\s*(중|상황)).*");
        List<ObjectNode> matches = matchingTasks(query, issueKeys);
        if (planning || !matches.isEmpty() || query.matches("(?s).*(업무|이슈|숨긴|숨김|task).*")) {
            List<ObjectNode> candidates = matches;
            if (candidates.isEmpty()) {
                candidates = query.matches("(?s).*(숨긴|숨김).*")
                        ? service.store.list("task").stream().filter(row -> row.path("hidden").asBoolean()).toList() : service.tasks();
            }
            context.set("tasks", Json.MAPPER.valueToTree(candidates.stream().limit(24).map(row -> fields(row, TASK_FIELDS)).toList()));
            context.put("matchingTaskCount", candidates.size());
        }
        if (query.matches("(?s).*(성격|분류).*")) context.set("workTypes", Json.MAPPER.valueToTree(service.workTypes()));
        if (planning) {
            context.set("plan", Json.MAPPER.valueToTree(service.planned(day).stream().map(row -> fields(row, TASK_FIELDS)).toList()));
            if (query.matches("(?s).*(추천|후보|우선순위).*")) {
                context.set("todayGroups", Json.MAPPER.valueToTree(service.todayGroups(day).stream().map(group -> {
                    List<ObjectNode> tasks = new ArrayList<>();
                    group.path("tasks").forEach(row -> { if (tasks.size() < 8) tasks.add(fields((ObjectNode) row, TASK_FIELDS)); });
                    return Json.obj("title", Json.text(group, "title"), "count", group.path("tasks").size(), "tasks", tasks);
                }).toList()));
            }
        }
        if (query.matches("(?s).*(활동|기록|작업 근거|한 일|완료한|끝낸|워크로그|로그워크|worklog).*")) {
            if(!query.matches("(?s).*(워크로그|로그워크|worklog|시간 등록|시간 배분).*"))context.set("activities", Json.MAPPER.valueToTree(service.activities(day.minusDays(7), day).reversed().stream().limit(20)
                    .map(row -> fields(row, "id", "date", "title", "jiraKey", "product")).toList()));
            if (query.matches("(?s).*(한 일|완료한|끝낸).*")) {
                context.set("completed", Json.MAPPER.valueToTree(service.completed(day).stream().map(row -> fields(row, TASK_FIELDS)).toList()));
            }
        }
        if (query.matches("(?s).*(워크로그|로그워크|worklog|시간 등록|시간 배분).*")) {
            LocalDate monday = service.time.monday(day);
            context.set("worklogs", Json.MAPPER.valueToTree(service.store.between("worklog", monday.toString(), monday.plusDays(6).toString())));
            WorkLedger ledger=new WorkLedger(service);
            context.set("worklogCandidates",Json.MAPPER.valueToTree(java.util.stream.IntStream.range(0,7).boxed().flatMap(i->ledger.worklogCandidates(monday.plusDays(i)).stream()).limit(40).map(row->fields(row,"date","workId","taskId","title","jiraKey","outcome")).toList()));
        }
        if (query.matches("(?s).*(회의|미팅|일정|캘린더).*")) {
            context.set("meetings", Json.MAPPER.valueToTree(service.meetings(day).stream().map(row -> fields(row,"id","googleEventId","recurringEventId","etag","title","start","end","date","endDate","allDay","calendarUrl")).toList()));
        }
        if (query.matches("(?s).*(데일리|스탠드업|브리핑|공유).*")) {
            ObjectNode note = service.store.get("daily", day.toString());
            if (note != null) context.set("dailyDetails", fields(note, "text", "problems", "help", "decisions"));
        }
        if (query.matches("(?s).*(검토할|작업 결과|실행 결과|밤새|후속).*")) {
            context.set("jobs", Json.MAPPER.valueToTree(service.store.list("job").reversed().stream().limit(8).map(row -> {
                String result = Json.text(row, "result");
                return fields(row, "id", "title", "jiraKey", "date", "reviewed", "artifact", "verification")
                        .put("result", result.length() <= 800 ? result : result.substring(0, 800) + "…");
            }).toList()));
            context.set("followups", Json.MAPPER.valueToTree(service.store.between("followup", day.toString(), day.toString()).stream().limit(8)
                    .map(row -> fields(row, "id", "title", "reason", "sourceJiraKey", "acceptedTaskId")).toList()));
        }
        if (!issueKeys.isEmpty()) {
            List<ObjectNode> issues = issueKeys.stream().sorted().limit(12).map(key -> service.store.get("issue", key.toUpperCase(Locale.ROOT)))
                    .filter(Objects::nonNull).map(row -> fields(row, "id", "title", "status", "jiraStatus", "project", "due")).toList();
            if (!issues.isEmpty()) context.set("jiraIssues", Json.MAPPER.valueToTree(issues));
        }
        String lookup = query;
        boolean team = lookup.matches("(?s).*(팀원|면담|1on1|1:1|관찰|피드백|통계).*");
        List<ObjectNode> members = service.members().stream().filter(row -> team || contains(lookup, Json.text(row, "name")))
                .map(row -> fields(row, "id", "name")).toList();
        if (!members.isEmpty()) context.set("members", Json.MAPPER.valueToTree(members));
        return context;
    }

    private List<ObjectNode> matchingTasks(String query, Set<String> keys) {
        List<String> terms = Arrays.stream(query.split("[^\\p{L}\\p{N}-]+")).filter(word -> word.length() >= 3)
                .filter(word -> !Set.of("업무를", "업무의", "작업을", "작업의", "보여줘", "알려줘", "바꿔줘", "확인해", "추가해", "정리해").contains(word)).distinct().limit(24).toList();
        return service.store.list("task").stream().map(row -> Map.entry(row, taskScore(row, query, keys, terms)))
                .filter(entry -> entry.getValue() > 0).sorted(Map.Entry.<ObjectNode, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();
    }
    private static boolean planningRequest(String query) { return query.matches("(?s).*(계획|추천|이어|브리핑|데일리).*"); }

    private static int taskScore(ObjectNode task, String query, Set<String> keys, List<String> terms) {
        String key = Json.text(task, "jiraKey").toLowerCase(Locale.ROOT);
        String title = Json.text(task, "title").toLowerCase(Locale.ROOT);
        if (keys.contains(key) || contains(query, Json.text(task, "id")) || contains(query, title)) return 100;
        if (task.path("hidden").asBoolean() && !query.matches("(?s).*(숨긴|숨김|다시 표시|복원).*")) return 0;
        return (int) terms.stream().filter(term -> title.contains(term) || (!key.isBlank() && key.startsWith(term + "-"))).count();
    }

    private static boolean contains(String query, String value) {
        return !value.isBlank() && query.contains(value.toLowerCase(Locale.ROOT));
    }

    private static ObjectNode fields(ObjectNode row, String... names) {
        ObjectNode result = Json.object();
        for (String name : names) if (row.hasNonNull(name)) result.set(name, row.get(name));
        return result;
    }

    private static LocalDate requestedDate(String request, LocalDate today) {
        var match = DATE.matcher(request);
        if (match.find()) {
            try { return LocalDate.parse(match.group()); } catch (DateTimeParseException ignored) { return today; }
        }
        if (request.contains("내일")) return today.plusDays(1);
        if (request.contains("어제") && !request.contains("오늘")) return today.minusDays(1);
        return today;
    }
}
