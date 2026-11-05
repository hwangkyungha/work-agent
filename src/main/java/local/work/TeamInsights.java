package local.work;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;

final class TeamInsights {
    private final WorkService service;
    TeamInsights(WorkService service) { this.service = service; }
    ObjectNode query(JsonNode input) throws Exception {
        ObjectNode member = service.store.get("member", Json.required(input, "memberId"));
        if (member == null) throw new IllegalArgumentException("먼저 팀원을 등록하세요.");
        service.jira.loadSiteUrl();
        LocalDate from = LocalDate.parse(Json.required(input, "from")), requestedTo = LocalDate.parse(Json.required(input, "to"));
        LocalDate to = requestedTo.isAfter(service.time.today()) ? service.time.today() : requestedTo;
        if (from.isAfter(to)) throw new IllegalArgumentException("조회 기간을 확인하세요. 미래에 수행할 업무는 집계할 수 없습니다.");
        String user = service.jira.resolveUser(Json.required(member, "jiraUser"));
        String prefix = Json.text(input, "issuePrefix").toUpperCase(Locale.ROOT).replaceFirst("-?\\*$", "").replaceFirst("-$", "");
        if (!prefix.isBlank() && !prefix.matches("[A-Z][A-Z0-9_]*")) throw new IllegalArgumentException("이슈 접두사를 CTS-* 형식으로 입력하세요.");
        String key = WorkService.issue(Json.text(input, "issueKey"));
        String quoted = quote(user);
        // JQL is a candidate search. Assignment intervals and log dates are filtered exactly in KST below.
        String jql = key.isBlank() ? "(assignee = " + quoted + " OR assignee WAS " + quoted + " DURING (" + quote(from.minusDays(1).toString()) + "," + quote(to.plusDays(2).toString()) + ") OR (worklogAuthor = " + quoted + " AND worklogDate >= " + quote(from.minusDays(1).toString()) + " AND worklogDate <= " + quote(to.plusDays(2).toString()) + "))" : "key = " + quote(key);
        if (!prefix.isBlank()) jql += " AND project = " + quote(prefix);
        List<JsonNode> candidates = service.jira.search(jql, "summary,status,created,assignee,description");
        Instant start = from.atStartOfDay(KoreaTime.ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(KoreaTime.ZONE).toInstant();
        if (end.isAfter(service.time.instant())) end = service.time.instant();
        List<ObjectNode> rows = new ArrayList<>();
        Map<String, Long> monthly = new TreeMap<>();
        long total = 0; int assigned = 0;
        for (JsonNode issue : candidates) {
            String issueKey = Json.required(issue, "key");
            if (!prefix.isBlank() && !issueKey.startsWith(prefix + "-")) continue;
            List<JsonNode> changes = service.jira.changelog(issueKey);
            boolean held = assignedDuring(issue, changes, user, start, end);
            Map<String, Long> days = new TreeMap<>();
            long seconds = 0;
            for (JsonNode log : service.jira.worklogs(issueKey)) {
                if (!sameUser(log.path("author"), user)) continue;
                Instant when = JiraClient.instant(Json.required(log, "started"));
                if (when.isBefore(start) || !when.isBefore(end)) continue;
                long value = log.path("timeSpentSeconds").asLong(); seconds += value;
                String day = when.atZone(KoreaTime.ZONE).toLocalDate().toString();
                days.merge(day, value, Long::sum); monthly.merge(day.substring(0, 7), value, Long::sum);
            }
            if (!held && seconds == 0) continue;
            if (held) assigned++;
            total += seconds;
            rows.add(Json.obj("key", issueKey, "title", Json.text(issue.path("fields"), "summary"), "issueUrl", service.jira.issueUrl(issueKey),
                    "assignedInPeriod", held, "loggedSeconds", seconds, "byDay", days,
                    "description", input.path("includeReasons").asBoolean() ? limit(JiraClient.documentText(issue.path("fields").path("description")), 6000) : ""));
        }
        rows.sort(Comparator.comparingLong((ObjectNode n) -> n.path("loggedSeconds").asLong()).reversed());
        if (input.path("includeReasons").asBoolean()) {
            for (ObjectNode row : rows.stream().skip(5).toList()) row.remove("description");
            for (ObjectNode row : rows.stream().limit(5).toList()) {
                List<JsonNode> comments = service.jira.comments(Json.text(row, "key")).stream()
                        .filter(c -> JiraClient.instant(Json.required(c, "created")).isBefore(to.plusDays(1).atStartOfDay(KoreaTime.ZONE).toInstant()))
                        .sorted(Comparator.comparing(c -> JiraClient.instant(Json.text(c, "created")))).toList();
                List<JsonNode> selected = comments.subList(Math.max(0, comments.size() - 20), comments.size());
                row.set("comments", Json.MAPPER.valueToTree(selected.stream().map(c -> Json.obj("created", Json.text(c, "created"), "text", limit(JiraClient.documentText(c.path("body")), 2000))).toList()));
                row.put("commentCount", comments.size()); row.put("commentsShown", selected.size());
            }
        }
        List<ObjectNode> observations = service.store.between("observation", from.toString(), to.toString()).stream().filter(n -> Json.text(n, "memberId").equals(Json.text(member, "id"))).toList();
        return Json.obj("member", Json.text(member, "name"), "from", from.toString(), "to", to.toString(), "requestedTo", requestedTo.toString(), "asOf", service.time.now().toString(),
                "assignedIssueCount", assigned, "loggedSeconds", total, "byMonth", monthly, "issues", rows, "observations", observations,
                "assignmentDefinition", "한국 시간 조회 기간과 담당 이력 구간이 겹치는 고유 이슈 수입니다. 현재 담당자만 세지 않습니다.",
                "reasonCoverage", input.path("includeReasons").asBoolean() ? "로그 시간이 큰 순서로 최대 5개 이슈의 설명(각 6,000자)과 이슈별 최근 댓글 최대 20개(각 2,000자)를 참고합니다. 담당 건수와 시간은 전체 조회 결과를 집계합니다. 설명은 현재 내용이며 당시 내용과 다를 수 있습니다." : "원인 분석용 설명과 댓글을 조회하지 않았습니다.");
    }
    static boolean assignedDuring(JsonNode issue, List<JsonNode> history, String user, Instant from, Instant until) {
        record Change(Instant at, String before, String after) {}
        List<Change> changes = new ArrayList<>();
        for (JsonNode entry : history) for (JsonNode item : entry.path("items")) {
            if (Json.text(item, "field").equals("assignee") || Json.text(item, "fieldId").equals("assignee")) changes.add(new Change(JiraClient.instant(Json.required(entry, "created")), Json.text(item, "from"), Json.text(item, "to")));
        }
        changes.sort(Comparator.comparing(Change::at));
        JsonNode fields = issue.path("fields");
        Instant begin = JiraClient.instant(Json.required(fields, "created"));
        String holder = changes.isEmpty() ? userId(fields.path("assignee")) : changes.getFirst().before();
        for (Change change : changes) {
            if (holder.equals(user) && begin.isBefore(until) && change.at().isAfter(from) && change.at().isAfter(begin)) return true;
            holder = change.after(); begin = change.at();
        }
        return holder.equals(user) && begin.isBefore(until) && until.isAfter(from);
    }
    private static String userId(JsonNode user) { return user.path("accountId").asText(user.path("name").asText(user.path("key").asText(""))); }
    private static boolean sameUser(JsonNode node, String user) { return user.equals(Json.text(node, "accountId")) || user.equals(Json.text(node, "name")) || user.equals(Json.text(node, "key")); }
    private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static String limit(String text, int length) { return text.length() <= length ? text : text.substring(0, length) + "\n(이하 생략)"; }
    static String summary(JsonNode data) {
        return Json.text(data, "member") + " / " + Json.text(data, "from") + " ~ " + Json.text(data, "to") + " (한국 시간)\n담당 이슈 " + data.path("assignedIssueCount").asInt() + "개 / Jira 등록 시간 " + Reports.hours(data.path("loggedSeconds").asLong()) + "h\n" + Json.text(data, "assignmentDefinition");
    }
}
