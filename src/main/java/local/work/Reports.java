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
        StringBuilder text = new StringBuilder(date + " 데일리\n\n어제 한 일\n");
        appendActivities(text, service.activities(date.minusDays(1), date.minusDays(1)));
        text.append("\n오늘 할 일\n");
        appendTasks(text, service.planned(date), "오늘 계획을 먼저 정해 주세요.");
        ObjectNode note = service.store.get("daily", date.toString());
        if (note != null && !Json.text(note, "text").isBlank()) text.append("\n추가 공유\n").append(Json.text(note, "text")).append('\n');
        return text.toString();
    }
    String briefing(LocalDate date) {
        StringBuilder text = new StringBuilder(date + " 데일리 브리핑 (한국 시간)\n");
        List<ObjectNode> jobs = service.store.list("job").stream().filter(j -> !j.path("reviewed").asBoolean()).toList();
        text.append("\n검토할 작업 결과\n");
        if (jobs.isEmpty()) text.append("- 새로 접수된 작업 결과가 없습니다.\n");
        for (ObjectNode job : jobs) {
            text.append("- ").append(Json.text(job, "title")).append(": ").append(Json.text(job, "result")).append('\n');
            if (!Json.text(job, "verification").isBlank()) text.append("  검증 결과: ").append(Json.text(job, "verification")).append('\n');
            if (!Json.text(job, "artifact").isBlank()) text.append("  결과물: ").append(Json.text(job, "artifact")).append('\n');
        }
        text.append("\n진행 상황과 마감\n");
        List<ObjectNode> risks = service.tasks().stream().filter(n -> !"done".equals(Json.text(n, "status")))
                .filter(n -> !Json.text(n, "due").isBlank() && LocalDate.parse(Json.text(n, "due")).isBefore(date.plusDays(2))).toList();
        appendTasks(text, risks, "현재 기록에서 임박한 마감이 없습니다.");
        text.append("\n오늘 계획");
        List<ObjectNode> plan = service.planned(date);
        boolean saved = service.store.get("plan", date.toString()) != null;
        text.append(saved ? "\n" : " 제안 (아직 확정하지 않았습니다)\n");
        appendTasks(text, saved ? plan : service.tasks().stream().filter(n -> !"done".equals(Json.text(n, "status"))).toList(), "등록된 할 일이 없습니다.");
        for (ObjectNode sync : service.store.list("sync")) text.append("\n").append(Json.text(sync, "id")).append(" 마지막 확인: ").append(KoreaTime.parse(Json.text(sync, "at")).atZone(KoreaTime.ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append(sync.path("ok").asBoolean() ? "" : " (연결 오류)");
        return text.toString();
    }
    String worklog(LocalDate from, LocalDate to) {
        StringBuilder text = new StringBuilder(from + " ~ " + to + " 워크로그\n작업 시간은 3h, 2.5h, 2h 30m 형식으로 입력합니다.\n");
        List<ObjectNode> groups = new ActivitySummary(service.store).group(from, to);
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            text.append("\n").append(day).append(' ').append(day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN)).append('\n');
            ObjectNode draft = service.store.get("worklog", day.toString());
            if (draft != null) for (JsonNode item : draft.path("items")) text.append("- ").append(Json.text(item, "jiraKey")).append(" / ").append(hours(item.path("seconds").asLong())).append("h / ").append(stateLabel(Json.text(item, "state"))).append('\n');
            for (ObjectNode group : groups) if (day.toString().equals(Json.text(group, "date"))) text.append("- 작업 근거: ").append(Json.text(group, "jiraKey")).append(' ').append(Json.text(group, "title")).append(" / 메시지 ").append(group.path("messageCount").asInt()).append("개 / 세션 ").append(group.path("sessionCount").asInt()).append("개\n");
            String dayText = day.toString();
            if (draft == null && groups.stream().noneMatch(g -> Json.text(g, "date").equals(dayText))) text.append("- 수집된 활동이 없습니다. 다른 이슈도 직접 입력할 수 있습니다.\n");
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
    private static void appendTasks(StringBuilder text, List<ObjectNode> tasks, String empty) {
        if (tasks.isEmpty()) text.append("- ").append(empty).append('\n');
        for (ObjectNode task : tasks) text.append("- ").append(Json.text(task, "title")).append(Json.text(task, "jiraKey").isBlank() ? "" : " (" + Json.text(task, "jiraKey") + ")").append(Json.text(task, "due").isBlank() ? "" : " / 마감 " + Json.text(task, "due")).append('\n');
    }
    private static void appendActivities(StringBuilder text, List<ObjectNode> activities) {
        if (activities.isEmpty()) text.append("- 등록된 활동이 없습니다.\n");
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        for (ObjectNode activity : activities) {
            String source = Json.text(activity, "source");
            String prefix = switch (source) { case "claude" -> "Claude 기록: "; case "codex" -> "Codex 기록: "; default -> ""; };
            lines.add("- " + prefix + Json.text(activity, "title") + (Json.text(activity, "jiraKey").isBlank() ? "" : " (" + Json.text(activity, "jiraKey") + ")"));
        }
        lines.forEach(line -> text.append(line).append('\n'));
    }
}
