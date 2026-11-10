package local.work;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkLedgerTest {
    @TempDir Path temporary;
    Store store; WorkService service;
    @BeforeEach void open() throws Exception {
        store = new Store(temporary.resolve("work.db"));
        service = new WorkService(store, new KoreaTime(Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"), ZoneOffset.UTC)), new JiraClient(new Config(temporary.resolve("none"), Map.of())));
    }
    @AfterEach void close() throws Exception { store.close(); }
    @Test void aPlanIsNotEvidenceOfPerformedWorkAndFridayWorkSurvivesTheWeekend() {
        ObjectNode task = service.task(Json.obj("title", "오류 재현")); String id = Json.text(task, "id");
        service.plan(Json.obj("date", "2026-11-06", "taskIds", List.of(id)));
        WorkLedger ledger = new WorkLedger(service);
        assertFalse(ledger.day(LocalDate.of(2026,11,6)).getFirst().path("performed").asBoolean());
        assertFalse(new Reports(service).briefing(service.time.today()).contains("- 오류 재현"));
        assertEquals(id, Json.text(ledger.carryover(service.time.today()).getFirst(), "taskId"));
        ledger.record(Json.obj("date", "2026-11-06", "workId", id, "outcome", "progress"));
        assertTrue(new Reports(service).briefing(service.time.today()).contains("- 오류 재현"));
        assertEquals(1, ledger.worklogCandidates(LocalDate.of(2026,11,6)).size());
    }
    @Test void initialPlanAndRemovedWorkRemainAfterEditingTitlesAndDeletingATask() {
        String id = Json.text(service.task(Json.obj("title", "아침 계획")), "id");
        service.plan(Json.obj("taskIds", List.of(id)));
        service.task(Json.obj("id", id, "title", "오후에 바꾼 제목", "important", true));
        service.plan(Json.obj("taskIds", List.of()));
        ObjectNode day = new WorkLedger(service).day(service.time.today()).getFirst();
        assertEquals("아침 계획", Json.text(day, "title")); assertFalse(day.path("inFinalPlan").asBoolean());
        assertTrue(day.path("priorityChanged").asBoolean());assertFalse(day.path("initialImportant").asBoolean());assertTrue(day.path("finalImportant").asBoolean());
        assertEquals(3, store.list("planRevision").size());
        service.removeTasks(Json.obj("taskIds", List.of(id)));
        assertEquals("아침 계획", Json.text(new WorkLedger(service).day(service.time.today()).getFirst(), "title"));
        assertTrue(new WorkLedger(service).carryover(service.time.today().plusDays(1)).isEmpty());
    }
    @Test void requirementTokensAreNotIssuesAndUnlinkedActivityCanBeLinkedToALocalTask() {
        store.put("activity", Json.obj("id", "old", "date", "2026-11-06", "source", "claude", "session", "implementation", "title", "구현 결과", "jiraKey", "REQ-001", "candidateKeys", List.of("REQ-001")));
        store.put("session", Json.obj("id", "claude:implementation", "name", "설정 오류 수정"));
        store.put("evidence", Json.obj("id", "message", "date", "2026-11-06", "source", "claude", "session", "implementation", "role", "assistant", "issueKeys", List.of("REQ-001")));
        WorkLedger ledger = new WorkLedger(service);
        ObjectNode activity = ledger.day(LocalDate.of(2026,11,6)).getFirst();
        assertEquals("", Json.text(activity, "jiraKey")); assertEquals("설정 오류 수정", Json.text(activity, "title"));
        assertEquals(1, new ActivityLinks(store).repair()); assertEquals("", Json.text(store.get("activity","old"),"jiraKey"));
        String id = Json.text(service.task(Json.obj("title", "설정 체계 개선")), "id");
        service.linkWork(Json.obj("date", "2026-11-06", "workId", Json.text(activity,"workId"), "taskId", id));
        ObjectNode linked = ledger.day(LocalDate.of(2026,11,6)).getFirst();
        assertEquals(id, Json.text(linked,"taskId")); assertTrue(linked.path("performed").asBoolean());
        assertEquals("설정 체계 개선",Json.text(linked,"title"));
    }
    @Test void unlinkedWorkKeepsItsTitleAndTimeButCannotBePostedUntilAJiraIssueIsChosen() {
        service.activity(Json.obj("date", "2026-11-06", "title", "고객과 요구사항 논의"));
        ObjectNode work = new WorkLedger(service).worklogCandidates(LocalDate.of(2026,11,6)).getFirst();
        ObjectNode saved = service.saveWorklog(Json.obj("date", "2026-11-06", "items", List.of(Json.obj("workId", Json.text(work,"workId"), "title", Json.text(work,"title"), "duration", "2h 30m"))));
        assertEquals(9000, saved.path("items").get(0).path("seconds").asInt());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.postWorklog(LocalDate.of(2026,11,6))).getMessage().contains("Jira 이슈를 연결"));
        assertEquals("고객과 요구사항 논의",Json.text(store.get("worklog","2026-11-06").path("items").get(0),"title"));
    }
    @Test void creatingADraftPrIsActualWorkEvenWithoutACliSessionAndRealReqProjectsRemainValid() {
        store.put("issue",Json.obj("id","REQ-12","title","실제 Jira 프로젝트의 업무"));
        store.put("draftResult",Json.obj("id","draft","url","https://bitbucket.org/team/repo/pull-requests/1","title","REQ-12 변경","issueKeys",List.of("REQ-12"),"createdAt","2026-11-06T00:00:00Z"));
        ObjectNode work=new WorkLedger(service).day(LocalDate.of(2026,11,6)).getFirst();
        assertTrue(work.path("performed").asBoolean());assertEquals("REQ-12",Json.text(work,"jiraKey"));
        assertTrue(new Reports(service).briefing(service.time.today()).contains("Draft PR 작성"));
    }
}
