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
        assertFalse(new Reports(service).briefing(service.time.today()).contains("Draft PR 작성"));
        new WorkLedger(service).add(Json.obj("date","2026-11-06","workId",Json.text(work,"workId"),"outcome","progress"));
        assertTrue(new Reports(service).briefing(service.time.today()).contains("Draft PR 작성"));
    }
    @Test void reviewStartsWithTheTwoPlannedTasksInsteadOfEveryRecordedSession() {
        String first=Json.text(service.task(Json.obj("title","계획한 첫 업무")),"id"),second=Json.text(service.task(Json.obj("title","계획한 둘째 업무")),"id");
        service.plan(Json.obj("date","2026-11-06","taskIds",List.of(first,second)));
        store.put("session",Json.obj("id","claude:extra","name","계획에 없던 실제 작업"));
        store.put("evidence",Json.obj("id","extra","date","2026-11-06","source","claude","session","extra","role","assistant","text","작업 결과"));
        assertEquals(2,service.state(service.time.today()).path("previousWork").size());
    }
    @Test void emptySessionTitleFallsBackToTheFirstUserMessageWithoutErasingTheWork() {
        store.put("session",Json.obj("id","codex:blank","name",""));
        store.put("evidence",Json.obj("id","question","date","2026-11-06","source","codex","session","blank","role","user","text","설정 파일 형식 변경 검토"));
        store.put("evidence",Json.obj("id","answer","date","2026-11-06","source","codex","session","blank","role","assistant","text","검토했습니다."));
        assertEquals("설정 파일 형식 변경 검토",Json.text(new WorkLedger(service).day(LocalDate.of(2026,11,6)).getFirst(),"title"));
    }
    @Test void workCompletedYesterdayDoesNotReappearAsAnInProgressSuggestion() {
        ObjectNode task=service.task(Json.obj("title","어제 완료한 업무","jiraKey","CT2612-518"));String id=Json.text(task,"id");
        task.put("status","in-progress");store.put("task",task);
        store.put("evidence",Json.obj("id","worked","date","2026-11-06","source","claude","session","finished","role","assistant","text","수정했습니다.","issueKeys",List.of("CT2612-518")));
        new WorkLedger(service).record(Json.obj("date","2026-11-06","workId",id,"outcome","done"));
        assertTrue(service.todayGroups(service.time.today()).stream().flatMap(group->java.util.stream.StreamSupport.stream(group.path("tasks").spliterator(),false)).noneMatch(row->id.equals(Json.text(row,"id"))));
        assertEquals("in-progress",Json.text(store.get("task",id),"status"));
    }
    @Test void unfinishedWorkIsSuggestedMostRecentlyWorkedFirst() {
        String older=Json.text(service.task(Json.obj("title","먼저 작업한 업무")),"id"),newer=Json.text(service.task(Json.obj("title","최근 작업한 업무")),"id");
        service.plan(Json.obj("date","2026-11-05","taskIds",List.of(older)));service.plan(Json.obj("date","2026-11-06","taskIds",List.of(newer)));
        assertEquals(List.of(newer,older),new WorkLedger(service).carryover(service.time.today()).stream().map(row->Json.text(row,"taskId")).toList());
    }
    @Test void optionalActivityCandidatesAreOrderedByConversationCountAndAppearOnlyAfterSelection() {
        for(String session:List.of("small","large")) {
            store.put("session",Json.obj("id","claude:"+session,"name",session+" 실제 작업"));
            for(int i=0;i<(session.equals("large")?6:2);i++)store.put("evidence",Json.obj("id",session+i,"date","2026-11-06","source","claude","session",session,"role",i%2==0?"user":"assistant","text","작업 논의"));
        }
        WorkLedger ledger=new WorkLedger(service);LocalDate day=LocalDate.of(2026,11,6);
        assertTrue(ledger.reviewRows(day).isEmpty());assertEquals("large 실제 작업",Json.text(ledger.activityCandidates(day).getFirst(),"title"));
        String id=Json.text(ledger.activityCandidates(day).getFirst(),"workId");
        ledger.add(Json.obj("date",day.toString(),"workId",id,"outcome","done"));
        assertEquals(1,ledger.reviewRows(day).size());assertEquals("done",Json.text(ledger.reviewRows(day).getFirst(),"outcome"));assertEquals(1,ledger.activityCandidates(day).size());
        assertTrue(service.planned(service.time.today()).isEmpty());assertTrue(service.tasks().isEmpty());
    }
    @Test void yesterdayCanIncludeAnExistingJiraTaskOrAHandwrittenCompletedActivityWithoutChangingJiraOrToday() {
        String id=Json.text(service.task(Json.obj("title","LCOV 옵션 추가","jiraKey","CT2612-518")),"id");
        WorkLedger ledger=new WorkLedger(service);LocalDate day=LocalDate.of(2026,11,6);
        ledger.add(Json.obj("date",day.toString(),"taskId",id,"outcome","done"));
        ledger.add(Json.obj("date",day.toString(),"title","팀 회의","outcome","done"));
        assertEquals(2,ledger.reviewRows(day).size());assertEquals(1,service.tasks().size());assertEquals("todo",Json.text(store.get("task",id),"status"));assertTrue(service.planned(service.time.today()).isEmpty());
        assertEquals(2,ledger.worklogCandidates(day).size());
    }
    @Test void codexTitleImportDoesNotOverwriteAUsefulFallbackWithAnEmptyNativeTitle() throws Exception {
        Path file=temporary.resolve("codex.sqlite");
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file);var query=connection.createStatement()) {
            query.execute("CREATE TABLE threads(id TEXT,title TEXT,cwd TEXT,updated_at INTEGER,has_user_event INTEGER)");query.execute("INSERT INTO threads VALUES('s','','D:/Git/lct',0,1)");
        }
        store.put("session",Json.obj("id","codex:s","name","기존에 확인한 작업명"));
        new SessionCatalog(store).codexTitles(file);
        assertEquals("기존에 확인한 작업명",Json.text(store.get("session","codex:s"),"name"));
    }
    @Test void recentProgressUsesTheLatestMessageTimeAndShowsTheKoreanWorkDate() {
        for(String key:List.of("CT-1","CT-2")) {
            ObjectNode task=service.task(Json.obj("title",key+" 업무","jiraKey",key));task.put("status","in-progress");store.put("task",task);
            store.put("evidence",Json.obj("id",key,"date","2026-11-06","source","codex","session",key,"role","assistant","text","작업했습니다.","issueKeys",List.of(key),"occurredAt",key.equals("CT-1")?"2026-11-05T15:20:00Z":"2026-11-05T18:20:00Z"));
        }
        var tasks=service.todayGroups(service.time.today()).getFirst().path("tasks");
        assertEquals("CT-2",Json.text(tasks.get(0),"jiraKey"));assertEquals("2026-11-06",Json.text(tasks.get(0),"lastWorkedDate"));
    }
    @Test void aSessionTitleIdentifiesTheExistingTaskEvenWhenOtherIssuesAreMentionedInTheConversation() {
        String id=Json.text(service.task(Json.obj("title","LCOV 옵션 추가","jiraKey","CT2612-518")),"id");
        service.task(Json.obj("title","대화 중 참고한 별도 업무","jiraKey","CT2612-486"));
        store.put("session",Json.obj("id","claude:lcov","name","CT2612-518 LCOV 옵션 추가"));
        store.put("evidence",Json.obj("id","conversation","date","2026-11-06","source","claude","session","lcov","role","assistant","text","작업 결과","issueKeys",List.of("CT2612-518","CT2612-486")));
        WorkLedger ledger=new WorkLedger(service);LocalDate day=LocalDate.of(2026,11,6);
        ObjectNode candidate=ledger.activityCandidates(day).getFirst();
        assertEquals(id,Json.text(candidate,"taskId"));assertEquals("CT2612-518",Json.text(candidate,"jiraKey"));assertTrue(ledger.reviewRows(day).isEmpty());
        ledger.add(Json.obj("date",day.toString(),"workId",Json.text(candidate,"workId"),"taskId",id,"outcome","done"));
        assertEquals(id,Json.text(ledger.reviewRows(day).getFirst(),"taskId"));assertTrue(ledger.activityCandidates(day).isEmpty());
    }
    @Test void legacySessionChecksKeepConfirmedWorkButNotUnconfirmedRowsAndRecognizeCompletedIssueTitles() {
        String task=Json.text(service.task(Json.obj("title","LCOV 옵션 추가","jiraKey","CT2612-518","important",true)),"id");
        store.put("workDay",Json.obj("id","legacy-progress","date","2026-11-06","workId","session:claude:old","taskId","","title","CT2612-518 이전 작업명","outcome","progress"));
        store.put("workDay",Json.obj("id","legacy-unconfirmed","date","2026-11-06","workId","session:claude:unchecked","taskId","","title","아직 확인하지 않은 세션","outcome","unconfirmed"));
        WorkLedger ledger=new WorkLedger(service);LocalDate day=LocalDate.of(2026,11,6);
        assertEquals(1,ledger.reviewRows(day).size());assertEquals(task,Json.text(ledger.reviewRows(day).getFirst(),"suggestedTaskId"));
        ledger.record(Json.obj("date",day.toString(),"workId","session:claude:old","outcome","done"));
        assertTrue(ledger.finishedTasks(service.time.today()).contains(task));
        assertTrue(service.todayGroups(service.time.today()).stream().flatMap(group->java.util.stream.StreamSupport.stream(group.path("tasks").spliterator(),false)).noneMatch(row->task.equals(Json.text(row,"id"))));
    }
    @Test void worklogDefaultsToReviewedPerformedWorkInsteadOfEverySession() {
        LocalDate day=LocalDate.of(2026,11,6);String id=Json.text(service.task(Json.obj("title","확인한 실제 업무")),"id");
        service.plan(Json.obj("date",day.toString(),"taskIds",List.of(id)));new WorkLedger(service).record(Json.obj("date",day.toString(),"workId",id,"outcome","progress"));
        for(String name:List.of("unselected","harness")) {
            store.put("session",Json.obj("id","codex:"+name,"name",name+" 작업 기록"));
            store.put("evidence",Json.obj("id",name,"date",day.toString(),"source","codex","session",name,"role","assistant","text","실행 결과","cwd",name.equals("harness")?"D:/Git/loop-test-harness/runs/261110-0845-01/baseline/work/runtime/lct-generation-fix":"D:/Git/lct"));
        }
        assertEquals(List.of(id),new WorkLedger(service).worklogCandidates(day).stream().map(row->Json.text(row,"workId")).toList());
        assertTrue(service.state(service.time.today()).path("activityGroups").isEmpty());
        assertFalse(new Reports(service).worklog(day,day).contains("unselected"));
    }
    @Test void harnessRunSessionsAreExcludedButDevelopingTheHarnessItselfIsActualWork() {
        for(String name:List.of("development","evaluation"))store.put("evidence",Json.obj("id",name,"date","2026-11-06","source","codex","session",name,"role","assistant","text","결과","cwd",name.equals("development")?"D:/Git/loop-test-harness":"D:/Git/loop-test-harness/runs/261110-0845-01/baseline/work/runtime/lct-generation-fix"));
        var groups=new ActivitySummary(store).group(LocalDate.of(2026,11,6),LocalDate.of(2026,11,6));
        assertEquals(1,groups.size());assertEquals("codex:development",Json.text(groups.getFirst().path("sessions").get(0),"id"));
    }
}
