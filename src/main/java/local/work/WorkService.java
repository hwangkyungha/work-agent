package local.work;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

final class WorkService {
    static final Pattern ISSUE = Pattern.compile("[A-Z][A-Z0-9_]*-[0-9]+");
    final Store store;
    final KoreaTime time;
    final JiraClient jira;

    WorkService(Store store, KoreaTime time, JiraClient jira) { this.store = store; this.time = time; this.jira = jira; }
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
        String id = Json.text(input, "id");
        ObjectNode existing = id.isBlank() ? null : store.get("task", id);
        if (!id.isBlank() && existing == null && !id.startsWith("request:")) throw new IllegalArgumentException("수정할 업무를 찾지 못했습니다.");
        ObjectNode row = existing == null ? Json.obj("status", "open", "important", false, "urgent", false, "product", "미분류", "jiraKey", "", "due", "", "createdAt", time.instant().toString()) : existing;
        if (!id.isBlank()) row.put("id", id);
        for (String key : List.of("title", "product", "due", "status", "important", "urgent", "jiraKey")) if (input.hasNonNull(key)) row.set(key, input.get(key));
        Json.required(row, "title");
        row.put("jiraKey", issue(Json.text(row, "jiraKey")));
        if (!Set.of("open", "doing", "blocked", "done").contains(Json.text(row, "status"))) throw new IllegalArgumentException("업무 상태를 확인하세요.");
        String due = Json.text(row, "due");
        if (!due.isBlank()) {
            LocalDate date = LocalDate.parse(due);
            if (existing == null && !input.hasNonNull("urgent")) row.put("urgent", !date.isAfter(time.today().plusDays(1)));
        }
        stamp(row);
        return store.put("task", row);
    }
    ObjectNode activity(JsonNode input) {
        String occurred = Json.text(input, "occurredAt");
        String date = occurred.isBlank() ? day(input) : KoreaTime.dateOf(occurred).toString();
        ObjectNode row = Json.obj("title", Json.required(input, "title"), "date", date,
                "jiraKey", issue(Json.text(input, "jiraKey")), "product", input.path("product").asText("미분류"),
                "source", input.path("source").asText("manual"), "sourceRef", Json.text(input, "sourceRef"),
                "occurredAt", occurred.isBlank() ? LocalDate.parse(date).atTime(time.now().toLocalTime()).atZone(KoreaTime.ZONE).toInstant().toString() : KoreaTime.parse(occurred).toString());
        if (input.hasNonNull("id")) row.set("id", input.get("id"));
        stamp(row);
        return store.put("activity", row);
    }
    ObjectNode meeting(JsonNode input) {
        Instant start = KoreaTime.parse(Json.required(input, "start"));
        String endText = Json.text(input, "end");
        if (!endText.isBlank() && !KoreaTime.parse(endText).isAfter(start)) throw new IllegalArgumentException("회의 종료 시각은 시작 시각보다 늦어야 합니다.");
        ObjectNode row = Json.obj("title", Json.required(input, "title"), "start", start.toString(),
                "end", endText.isBlank() ? "" : KoreaTime.parse(endText).toString(), "date", start.atZone(KoreaTime.ZONE).toLocalDate().toString(),
                "product", input.path("product").asText("미분류"), "jiraKey", issue(Json.text(input, "jiraKey")),
                "source", input.path("source").asText("manual"));
        if (input.hasNonNull("id")) row.set("id", input.get("id"));
        stamp(row);
        return store.put("meeting", row);
    }
    ObjectNode plan(JsonNode input) {
        String date = day(input);
        JsonNode ids = input.path("taskIds");
        if (!ids.isArray()) throw new IllegalArgumentException("오늘 할 업무 목록을 입력하세요.");
        Set<String> seen = new HashSet<>();
        for (JsonNode id : ids) {
            if (store.get("task", id.asText()) == null || !seen.add(id.asText())) throw new IllegalArgumentException("계획의 업무 번호를 확인하세요.");
        }
        ObjectNode row = Json.obj("id", date, "date", date, "taskIds", ids);
        stamp(row);
        return store.put("plan", row);
    }
    ObjectNode dailyNote(JsonNode input) {
        String date = day(input);
        ObjectNode row = Json.obj("id", date, "date", date, "text", Json.text(input, "text"));
        stamp(row);
        return store.put("daily", row);
    }
    List<ObjectNode> tasks() {
        return store.list("task").stream().sorted(Comparator
                .comparingInt((ObjectNode n) -> "done".equals(Json.text(n, "status")) ? 1 : 0)
                .thenComparingInt(n -> (n.path("important").asBoolean() ? 0 : 2) + (n.path("urgent").asBoolean() ? 0 : 1))
                .thenComparing(n -> Json.text(n, "due").isBlank() ? "9999" : Json.text(n, "due"))).toList();
    }
    List<ObjectNode> activities(LocalDate start, LocalDate end) {
        return store.list("activity").stream().filter(n -> {
            LocalDate d = LocalDate.parse(Json.text(n, "date")); return !d.isBefore(start) && !d.isAfter(end);
        }).sorted(Comparator.comparing(n -> Json.text(n, "occurredAt"))).toList();
    }
    List<ObjectNode> meetings(LocalDate day) {
        Instant from = day.atStartOfDay(KoreaTime.ZONE).toInstant(), until = day.plusDays(1).atStartOfDay(KoreaTime.ZONE).toInstant();
        return store.list("meeting").stream().filter(n -> {
            Instant start = KoreaTime.parse(Json.text(n, "start"));
            String end = Json.text(n, "end");
            return start.isBefore(until) && (end.isBlank() ? !start.isBefore(from) : KoreaTime.parse(end).isAfter(from));
        }).sorted(Comparator.comparing(n -> Json.text(n, "start"))).toList();
    }
    List<ObjectNode> planned(LocalDate day) {
        ObjectNode plan = store.get("plan", day.toString());
        if (plan == null) return List.of();
        List<ObjectNode> result = new ArrayList<>();
        for (JsonNode id : plan.path("taskIds")) {
            ObjectNode task = store.get("task", id.asText()); if (task != null) result.add(task);
        }
        return result;
    }
    ObjectNode state(LocalDate date) {
        LocalDate monday = time.monday(date);
        ObjectNode daily = store.get("daily", date.toString());
        return Json.obj("date", date.toString(), "timezone", KoreaTime.ZONE.toString(), "now", time.now().toString(),
                "tasks", tasks(), "plan", planned(date), "planSaved", store.get("plan", date.toString()) != null,
                "activities", activities(monday.minusDays(7), monday.plusDays(6)), "meetings", meetings(date),
                "worklogs", store.list("worklog").stream().filter(n -> Json.text(n, "date").compareTo(monday.toString()) >= 0 && Json.text(n, "date").compareTo(monday.plusDays(6).toString()) <= 0).toList(),
                "dailyNote", daily == null ? "" : Json.text(daily, "text"), "jobs", store.list("job"),
                "jiraIssues", store.list("issue"), "sync", store.list("sync"));
    }
    synchronized ObjectNode allocate(JsonNode input) {
        String date = day(input);
        ObjectNode old = store.get("worklog", date);
        if (old != null) for (JsonNode item : old.path("items")) {
            if (Set.of("posted", "posting", "unknown").contains(Json.text(item, "state"))) throw new IllegalArgumentException("이 날짜에는 Jira 등록 기록이 있습니다. 기존 Jira 기록을 먼저 확인하세요.");
        }
        int totalMinutes = input.path("totalMinutes").asInt(480);
        if (totalMinutes <= 0 || totalMinutes > 1440) throw new IllegalArgumentException("하루 기준 시간은 1분부터 24시간까지 입력하세요.");
        JsonNode shares = input.path("shares");
        if (!shares.isArray() || shares.isEmpty()) throw new IllegalArgumentException("이슈별 비율을 입력하세요.");
        BigDecimal sum = BigDecimal.ZERO;
        Set<String> keys = new HashSet<>();
        for (JsonNode share : shares) {
            String key = issue(Json.required(share, "jiraKey"));
            if (!keys.add(key)) throw new IllegalArgumentException("같은 이슈는 한 번만 입력하세요.");
            BigDecimal percent = share.path("percent").decimalValue();
            if (percent.signum() <= 0 || percent.compareTo(new BigDecimal("100")) > 0) throw new IllegalArgumentException("비율은 0보다 크고 100 이하여야 합니다.");
            sum = sum.add(percent);
        }
        if (sum.compareTo(new BigDecimal("100")) != 0) throw new IllegalArgumentException("비율의 합계가 100%가 되어야 합니다. 현재 " + sum.stripTrailingZeros().toPlainString() + "%입니다.");
        int remaining = totalMinutes * 60;
        ArrayNode items = Json.MAPPER.createArrayNode();
        for (int i = 0; i < shares.size(); i++) {
            JsonNode share = shares.get(i);
            int seconds = i == shares.size() - 1 ? remaining : share.path("percent").decimalValue().multiply(BigDecimal.valueOf(totalMinutes * 60L)).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).intValueExact();
            if (seconds <= 0) throw new IllegalArgumentException("배분한 시간이 1초보다 짧습니다.");
            remaining -= seconds;
            items.add(Json.obj("jiraKey", issue(Json.required(share, "jiraKey")), "percent", share.path("percent"), "seconds", seconds,
                    "comment", share.path("comment").asText("업무 기록"), "marker", "work-agent:" + UUID.randomUUID(), "state", "draft", "jiraId", ""));
        }
        ObjectNode row = Json.obj("id", date, "date", date, "totalMinutes", totalMinutes, "items", items);
        stamp(row);
        return store.put("worklog", row);
    }
    synchronized ObjectNode postWorklog(LocalDate date) throws Exception {
        if (!jira.configured()) throw new IllegalArgumentException("Jira 연결 정보를 config.properties에 설정하세요.");
        ObjectNode row = store.get("worklog", date.toString());
        if (row == null) throw new IllegalArgumentException("먼저 이 날짜의 워크로그 비율을 저장하세요.");
        for (JsonNode n : row.path("items")) {
            ObjectNode item = (ObjectNode) n;
            String state = Json.text(item, "state");
            if ("posted".equals(state)) continue;
            if (Set.of("posting", "unknown").contains(state)) {
                String found = jira.findMarker(Json.text(item, "jiraKey"), Json.text(item, "marker"));
                if (found.isBlank()) throw new IllegalStateException("Jira 등록 결과가 불확실합니다. 중복 등록을 막기 위해 멈췄습니다. 해당 날짜의 Jira 워크로그를 확인하세요.");
                item.put("jiraId", found).put("state", "posted"); store.put("worklog", row); continue;
            }
            item.put("state", "posting"); store.put("worklog", row);
            try {
                String id = jira.addWorklog(date, item);
                item.put("jiraId", id).put("state", "posted").remove("error");
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
        ObjectNode row = Json.obj("name", Json.required(input, "name"), "jiraUser", Json.required(input, "jiraUser"), "role", Json.text(input, "role"));
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
    ObjectNode team() { return Json.obj("members", store.list("member"), "observations", store.list("observation"), "snapshots", store.list("teamSnapshot")); }
    ObjectNode job(JsonNode input) {
        ObjectNode row = Json.obj("title", Json.required(input, "title"), "jiraKey", issue(Json.text(input, "jiraKey")),
                "result", Json.required(input, "result"), "artifact", Json.text(input, "artifact"), "verification", Json.text(input, "verification"),
                "reviewed", input.path("reviewed").asBoolean(false), "date", day(input));
        if (!Json.text(input, "id").isBlank()) row.set("id", input.get("id"));
        stamp(row); return store.put("job", row);
    }
}
