package local.work;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyBriefingTest {
    @TempDir Path temporary;
    Store store; WorkService service; Reports reports;
    @BeforeEach void open() throws Exception {
        store = new Store(temporary.resolve("test.db"));
        service = new WorkService(store, new KoreaTime(Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC)), new JiraClient(new Config(temporary.resolve("none"), Map.of())));
        reports = new Reports(service);
    }
    @AfterEach void close() throws Exception { store.close(); }
    @Test void briefingContainsDailyMeetingFactsWithoutOperationsOrDeadlines() {
        service.activity(Json.obj("title", "CI 리포트 오류를 확인했습니다", "date", "2026-11-05"));
        ObjectNode previous = service.task(Json.obj("title", "CI 리포트 오류 확인"));
        service.plan(Json.obj("date", "2026-11-05", "taskIds", List.of(Json.text(previous, "id"))));
        ObjectNode task = service.task(Json.obj("title", "리포트 수정 결과 검토", "due", "2026-11-07"));
        service.plan(Json.obj("taskIds", List.of(Json.text(task, "id"))));
        service.job(Json.obj("title", "비공개 작업 결과", "result", "자동 실행 결과"));
        store.put("sync", Json.obj("id", "jira", "at", "2026-11-06T00:00:00Z", "ok", true));
        service.dailyNote(Json.obj("problems", "테스트 환경에 연결되지 않습니다", "help", "실행 권한이 필요합니다", "decisions", "배포 날짜를 정해야 합니다"));
        String text = reports.briefing(service.time.today());
        for (String expected : List.of("진행한 일", "2026-11-05", "CI 리포트 오류", "오늘", "리포트 수정 결과 검토", "테스트 환경", "실행 권한", "배포 날짜")) assertTrue(text.contains(expected), expected);
        for (String removed : List.of("검토할 작업 결과", "진행 상황과 마감", "마지막 확인", "자동 실행 결과", "마감 2026-11-07")) assertFalse(text.contains(removed), removed);
    }
    @Test void emptyAdditionalSectionsAreOmittedAndExistingNotesArePreserved() {
        service.dailyNote(Json.obj("text", "팀에 공유할 변경 사항"));
        service.dailyNote(Json.obj("help", "환경 확인 요청"));
        String text = reports.daily(service.time.today());
        assertTrue(text.contains("팀에 공유할 변경 사항")); assertTrue(text.contains("환경 확인 요청"));
        assertFalse(text.contains("발생한 문제")); assertFalse(text.contains("결정이 필요한 사항"));
    }
    @Test void performedPlannedAndUnplannedWorkContainsTitlesWithoutAgentMessages() {
        ObjectNode planned = service.task(Json.obj("title", "보고서 기능 검토", "jiraKey", "CTS-1"));
        ObjectNode manual = service.task(Json.obj("title", "팀 회의 준비"));
        service.plan(Json.obj("date", "2026-11-05", "taskIds", List.of(Json.text(planned, "id"), Json.text(manual, "id"))));
        store.put("issue", Json.obj("id", "CTS-2", "title", "CI 결과 조회 개선"));
        for (String source : List.of("codex", "claude")) {
            session(source, "planned", "CTS-1");
            session(source, "extra", "CTS-2");
        }
        session("claude", "named", "CTS-3");
        store.put("issue", Json.obj("id", "CTS-3", "title", ""));
        store.put("session", Json.obj("id", "claude:named", "name", "CTS-3 설정 오류 수정"));
        session("codex", "unlinked", "");
        new WorkLedger(service).record(Json.obj("date","2026-11-05","workId",Json.text(manual,"id"),"outcome","progress"));
        service.activity(Json.obj("date", "2026-11-05", "title", "에이전트가 작성한 상세 수행 결과", "source", "claude", "session", "no-title"));
        String text = reports.briefing(service.time.today());
        assertTrue(text.contains("- CTS-1 보고서 기능 검토\n- 팀 회의 준비\n- CTS-2 CI 결과 조회 개선\n- CTS-3 설정 오류 수정\n"));
        assertEquals(1, text.split("CTS-1", -1).length - 1);
        assertEquals(1, text.split("CTS-2", -1).length - 1);
        assertFalse(text.contains("상세 수행 결과")); assertFalse(text.contains("긴 대화 원문")); assertFalse(text.contains("이름 없는 세션"));
        assertFalse(text.contains("메시지")); assertFalse(text.contains("세션 개수"));
    }
    @Test void yesterdayPlanWithoutActivityIsNotReportedAsPerformed() {
        service.activity(Json.obj("date", "2026-11-04", "title", "이틀 전 기록"));
        ObjectNode task = service.task(Json.obj("title", "어제 계획한 업무"));
        service.plan(Json.obj("date", "2026-11-05", "taskIds", List.of(Json.text(task, "id"))));
        String text = reports.daily(service.time.today());
        assertTrue(text.contains("2026-11-05 목요일")); assertFalse(text.contains("어제 계획한 업무")); assertFalse(text.contains("이틀 전 기록"));
    }
    @Test void sampleRequirementKeysAndAutomatedSessionsNeverBecomeBriefingIssues() {
        session("claude", "sample1", "REQ-001"); session("codex", "sample2", "REQ-002");
        store.put("issue", Json.obj("id", "CTS-99", "title", "자동 평가에서만 언급된 실제 이슈"));
        store.put("evidence", Json.obj("id", "automated", "date", "2026-11-05", "source", "claude", "session", "judge", "cwd", "D:\\Git\\lct\\.ct-verify\\baseline\\llm_judge", "role", "assistant", "text", "CTS-99 평가", "issueKeys", List.of("CTS-99")));
        store.put("issue", Json.obj("id", "CTS-2", "title", "실제 진행한 업무")); session("codex", "actual", "CTS-2");
        String text = reports.briefing(service.time.today());
        assertFalse(text.contains("REQ-001")); assertFalse(text.contains("REQ-002")); assertFalse(text.contains("CTS-99"));
        assertTrue(text.contains("CTS-2 실제 진행한 업무"));
    }
    @Test void automatedSessionsDoNotReplaceLatestWorkingDay() {
        service.activity(Json.obj("date", "2026-11-04", "title", "최근 실제 업무"));
        store.put("activity", Json.obj("id", "judge-activity", "date", "2026-11-05", "source", "claude", "session", "judge", "title", "평가 결과"));
        store.put("evidence", Json.obj("id", "judge-evidence", "date", "2026-11-05", "source", "claude", "session", "judge", "cwd", "D:/Git/lct/.ct-verify/run/work/project", "role", "assistant", "text", "평가 결과"));
        assertEquals(LocalDate.of(2026, 11, 4), reports.previousWorkDate(service.time.today()));
    }
    private void session(String source, String id, String key) {
        store.put("evidence", Json.obj("id", source + id, "date", "2026-11-05", "source", source, "session", id, "role", "assistant", "text", "긴 대화 원문", "issueKeys", key.isBlank() ? List.of() : List.of(key)));
    }
}
