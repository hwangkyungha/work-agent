package local.work;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

final class Reports {
    private final WorkService service;
    Reports(WorkService service) { this.service = service; }
    String daily(LocalDate date) {
        return dailyText(date, "데일리");
    }
    private String dailyText(LocalDate date, String title) {
        LocalDate previous = previousWorkDate(date);
        StringBuilder text = new StringBuilder(date + " " + title + " (한국 시간)\n\n진행한 일 (" + previous + " " + previous.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN) + ")\n");
        appendPreviousWork(text, previous);
        text.append("\n오늘 할 일\n");
        List<ObjectNode> plan = service.planned(date);
        if (plan.isEmpty()) text.append("- 오늘 할 일을 직접 선택해 주세요.\n");
        for (ObjectNode task : plan) text.append("- ").append(Json.text(task, "title")).append(Json.text(task, "jiraKey").isBlank() ? "" : " (" + Json.text(task, "jiraKey") + ")").append('\n');
        ObjectNode note = service.store.get("daily", date.toString());
        if (note != null) {
            for (var section : List.of(Map.entry("problems", "발생한 문제"), Map.entry("help", "필요한 도움"), Map.entry("decisions", "결정이 필요한 사항"), Map.entry("text", "추가 공유"))) {
                String content = Json.text(note, section.getKey());
                if (!content.isBlank()) text.append('\n').append(section.getValue()).append('\n').append(content).append('\n');
            }
        }
        return text.toString();
    }
    String briefing(LocalDate date) {
        return dailyText(date, "데일리 브리핑");
    }
    String preparation(LocalDate date) {
        LocalDate previous = previousWorkDate(date);
        StringBuilder text = new StringBuilder(date + " 오늘 업무 준비 (한국 시간)\n\n" + previous + " 계획과 실제\n");
        for (ObjectNode row : new WorkLedger(service).day(previous)) {
            text.append("- ").append(workTitle(Json.text(row, "jiraKey"), Json.text(row, "title"))).append(" · ")
                    .append(row.path("planned").asBoolean() ? "계획한 업무" : "계획 밖 활동").append(" · ")
                    .append(row.path("performed").asBoolean() ? "수행 내용 있음" : "수행 여부 확인 필요").append('\n');
        }
        text.append("\n이어서 할 일 후보\n");
        for (ObjectNode row : new WorkLedger(service).carryover(date)) text.append("- ").append(Json.text(row, "title")).append(" · ").append(Json.text(row, "reason")).append('\n');
        return text.append('\n').toString();
    }
    LocalDate previousWorkDate(LocalDate date) {
        LocalDate end = date.minusDays(1);
        while (end.getDayOfWeek().getValue() > 5) end = end.minusDays(1);
        LocalDate last = end;
        List<LocalDate> days = new ArrayList<>();
        Set<String> automated = new ActivitySummary(service.store).automatedSessions();
        service.store.list("activity").stream().filter(a -> !automated.contains(ActivitySummary.session(a))).forEach(a -> days.add(LocalDate.parse(Json.required(a, "date"))));
        service.store.list("plan").stream().filter(p -> !p.path("taskIds").isEmpty()).forEach(p -> days.add(LocalDate.parse(Json.required(p, "date"))));
        service.store.list("workDay").forEach(p -> days.add(LocalDate.parse(Json.required(p, "date"))));
        service.store.list("evidence").stream().filter(a -> !automated.contains(ActivitySummary.session(a)) && Json.text(a, "role").equals("assistant")).forEach(a -> days.add(LocalDate.parse(Json.required(a, "date"))));
        service.store.list("draftResult").stream().filter(row -> !Json.text(row,"createdAt").isBlank()).forEach(row -> days.add(KoreaTime.dateOf(Json.text(row,"createdAt"))));
        return days.stream()
                .filter(day -> !day.isAfter(last) && day.getDayOfWeek().getValue() <= 5).max(Comparator.naturalOrder()).orElse(last);
    }
    String worklog(LocalDate from, LocalDate to) {
        StringBuilder text = new StringBuilder(from + " ~ " + to + " 워크로그\n작업 시간은 3h, 2.5h, 2h 30m 형식으로 입력합니다.\n");
        List<ObjectNode> groups = new ActivitySummary(service.store).group(from, to);
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            text.append("\n").append(day).append(' ').append(day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN)).append('\n');
            ObjectNode draft = service.store.get("worklog", day.toString());
            if (draft != null) for (JsonNode item : draft.path("items")) if (!Json.text(item, "state").equals("posted")) text.append("- ").append(workTitle(Json.text(item, "jiraKey"), Json.text(item, "title"))).append(" / ").append(item.path("seconds").asLong() == 0 ? "시간 미정" : hours(item.path("seconds").asLong()) + "h").append(" / ").append(stateLabel(Json.text(item, "state"))).append('\n');
            List<ObjectNode> registered = service.registeredWorklogs(day, day);
            for (ObjectNode item : registered) text.append("- ").append(Json.text(item, "jiraKey")).append(" / ").append(hours(item.path("seconds").asLong())).append("h / 등록 완료\n");
            for (ObjectNode group : groups) if (day.toString().equals(Json.text(group, "date"))) text.append("- 작업 근거: ").append(Json.text(group, "jiraKey")).append(' ').append(Json.text(group, "title")).append(" / 메시지 ").append(group.path("messageCount").asInt()).append("개 / 세션 ").append(group.path("sessionCount").asInt()).append("개\n");
            String dayText = day.toString();
            if (draft == null && registered.isEmpty() && groups.stream().noneMatch(g -> Json.text(g, "date").equals(dayText))) text.append("- 수집된 활동이 없습니다. 다른 이슈도 직접 입력할 수 있습니다.\n");
        }
        text.append("\n예: ").append(from).append(" CT-123에 3h, CT-456에 2h 30m로 워크로그 초안 저장해 줘\n");
        return text.toString();
    }
    String oneOnOne(String memberId) {
        ObjectNode member = service.store.get("member", memberId);
        if (member == null) throw new IllegalArgumentException("팀원을 찾지 못했습니다.");
        StringBuilder text = new StringBuilder(Json.text(member, "name") + " 1on1 준비\n\n관찰 기록\n");
        List<ObjectNode> notes = service.store.list("observation").stream().filter(n -> memberId.equals(Json.text(n, "memberId"))).toList();
        if (notes.isEmpty()) text.append("- 아직 기록이 없습니다.\n");
        for (ObjectNode note : notes) {
            text.append("- ").append(Json.text(note, "date")).append(" [").append(Json.text(note, "type")).append("] ").append(Json.text(note, "text")).append('\n');
            if (!Json.text(note, "followUp").isBlank()) text.append("  후속 조치: ").append(Json.text(note, "followUp")).append('\n');
        }
        ObjectNode snapshot = service.store.get("teamSnapshot", memberId);
        text.append("\nJira 업무 현황\n");
        if (snapshot == null) text.append("- 아직 Jira에서 확인하지 않았습니다.\n");
        else {
            text.append("기간: ").append(Json.text(snapshot, "from")).append(" ~ ").append(Json.text(snapshot, "to")).append("\n등록 시간: ").append(hours(snapshot.path("loggedSeconds").asInt())).append("시간\n");
            for (JsonNode issue : snapshot.path("issues")) text.append("- ").append(Json.text(issue, "id")).append(' ').append(Json.text(issue, "title")).append(" / ").append(Json.text(issue, "jiraStatus")).append(" / 등록 ").append(hours(issue.path("loggedSeconds").asInt())).append("시간\n");
            text.append("Jira에 기록된 시간만 포함합니다. 지원 필요 여부는 면담에서 확인하세요.\n");
        }
        return text.toString();
    }
    static String hours(long seconds) { return java.math.BigDecimal.valueOf(seconds).divide(java.math.BigDecimal.valueOf(3600), 2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(); }
    static String stateLabel(String state) { return switch (state) { case "posted" -> "등록 완료"; case "posting", "unknown" -> "결과 확인 필요"; case "failed" -> "등록 실패"; default -> "초안"; }; }
    private void appendPreviousWork(StringBuilder text, LocalDate date) {
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        for (ObjectNode work : new WorkLedger(service).day(date)) if (work.path("performed").asBoolean()) {
            String title = Json.text(work, "title");
            if (title.equals("이름 없는 세션") || title.isBlank()) continue;
            lines.add(workTitle(Json.text(work, "jiraKey"), title));
        }
        if (lines.isEmpty()) text.append("- 확인된 수행 내용이 없습니다. 어제 계획과 실제 활동을 확인해 주세요.\n");
        lines.forEach(line -> text.append("- ").append(line).append('\n'));
    }
    private static String workTitle(String key, String title) {
        return key.isBlank() || title.startsWith(key) ? title : key + (title.isBlank() ? "" : " " + title);
    }
}
