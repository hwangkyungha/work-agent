package local.work;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowTest {
    @TempDir Path temporary;
    Store store;
    KoreaTime time;
    FakeJira jira;
    WorkService service;
    Reports reports;
    @BeforeEach void open() throws Exception {
        store = new Store(temporary.resolve("db.sqlite"));
        time = new KoreaTime(Clock.fixed(Instant.parse("2026-11-03T15:30:00Z"), ZoneOffset.UTC));
        jira = new FakeJira(new Config(temporary.resolve("none"), Map.of()));
        service = new WorkService(store, time, jira); reports = new Reports(service);
    }
    @AfterEach void close() throws Exception { store.close(); }
    static final class FakeJira extends JiraClient {
        int calls; boolean timeout, reject; String recovered = ""; List<JsonNode> captured = new ArrayList<>();
        FakeJira(Config config) { super(config); }
        @Override boolean configured() { return true; }
        @Override String addWorklog(LocalDate date, JsonNode item) throws Exception {
            calls++; captured.add(item.deepCopy());
            if (timeout) throw new java.net.SocketTimeoutException();
            if (reject) throw new Rejected("권한을 확인하세요.");
            return "jira-" + calls;
        }
        @Override String findMarker(String key, String marker) { return recovered; }
    }
    ObjectNode entries(String a, String b) {
        return Json.obj("date", "2026-11-04", "items", List.of(Json.obj("jiraKey", "CT-1", "duration", a), Json.obj("jiraKey", "CT-2", "duration", b)));
    }
    @Test void koreaDayDoesNotFollowUtcOrHostTimeZone() {
        assertEquals(LocalDate.of(2026,11,4), time.today());
        assertEquals(LocalDate.of(2026,11,4), KoreaTime.dateOf("2026-11-03T15:00:00Z"));
        assertEquals(LocalDate.of(2026,11,3), KoreaTime.dateOf("2026-11-03T14:59:59Z"));
        assertEquals("2026-11-04T09:00:00.000+0900", KoreaTime.jiraStarted(time.today()));
        assertEquals(LocalDate.of(2026,11,2), time.monday(time.today()));
    }
    @Test void activityUsesKoreaDateFromTimestamp() {
        ObjectNode a = service.activity(Json.obj("title", "분석", "date", "2026-11-03", "occurredAt", "2026-11-03T15:30:00Z"));
        assertEquals("2026-11-04", Json.text(a,"date"));
        assertEquals(1, service.activities(time.today(),time.today()).size());
    }
    @Test void meetingTimeIsNotTaskDeadline() {
        service.meeting(Json.obj("title","리뷰","start","2026-11-05T10:00:00+09:00"));
        assertEquals(1,service.meetings(time.today().plusDays(1)).size());
        assertTrue(service.tasks().isEmpty());
    }
    @Test void meetingSpanningMidnightAppearsOnBothKoreaDays() {
        service.meeting(Json.obj("title","장기 회의","start","2026-11-03T23:00:00+09:00","end","2026-11-04T01:00:00+09:00"));
        assertEquals(1,service.meetings(LocalDate.of(2026,11,3)).size());
        assertEquals(1,service.meetings(LocalDate.of(2026,11,4)).size());
    }
    @Test void newTaskCanBeReprioritizedWithoutLosingItsDeadline() {
        ObjectNode task = service.task(Json.obj("title","리뷰 준비","due","2026-11-05"));
        assertTrue(task.path("urgent").asBoolean());
        ObjectNode updated = service.task(Json.obj("id",Json.text(task,"id"),"important",true));
        assertEquals("2026-11-05",Json.text(updated,"due")); assertTrue(updated.path("important").asBoolean());
    }
    @Test void emptySavedPlanIsNotReplacedWithSuggestions() {
        service.task(Json.obj("title","남은 일"));
        service.plan(Json.obj("date","2026-11-04","taskIds",List.of()));
        assertTrue(service.state(time.today()).path("planSaved").asBoolean());
        assertFalse(reports.briefing(time.today()).contains("계획 제안"));
    }
    @Test void dailyHasOnlyYesterdayAndTodayUntilUserAddsContext() {
        service.activity(Json.obj("title","원인을 분석했습니다.","date","2026-11-03"));
        ObjectNode task = service.task(Json.obj("title","수정 결과 검토"));
        service.plan(Json.obj("taskIds",List.of(Json.text(task,"id"))));
        String plain=reports.daily(time.today());
        assertTrue(plain.contains("진행한 일"));assertFalse(plain.contains("원인을 분석했습니다."));assertTrue(plain.contains("수정 결과 검토"));
        assertFalse(plain.contains("도움 요청"));assertFalse(plain.contains("의사결정"));assertFalse(plain.contains("추가 공유"));
        service.dailyNote(Json.obj("text","검증 환경 접근이 필요합니다."));
        assertTrue(reports.daily(time.today()).contains("추가 공유\n검증 환경 접근이 필요합니다."));
    }
    @Test void worklogSavesEnteredHoursWithoutFillingEightHours() {
        ObjectNode draft=service.saveWorklog(entries("3h","2h"));
        assertEquals(10800,draft.path("items").get(0).path("seconds").asInt());
        assertEquals(7200,draft.path("items").get(1).path("seconds").asInt());
        assertEquals(0,jira.calls);
    }
    @Test void decimalAndMixedHoursAreEquivalent() {
        assertEquals(9000,WorkDuration.seconds("2.5h"));
        assertEquals(9000,WorkDuration.seconds("2h 30m"));
        assertEquals(1,WorkDuration.seconds("1s"));
    }
    @Test void issueListCanBeSavedWithoutTimeButCannotBePostedUntilCompleted() throws Exception {
        ObjectNode draft = service.saveWorklog(entries("1h", ""));
        assertEquals(0, draft.path("items").get(1).path("seconds").asInt());
        assertEquals(3600, draft.path("totalSeconds").asInt());
        assertThrows(IllegalArgumentException.class, () -> service.postWorklog(time.today())); assertEquals(0, jira.calls);
        service.saveWorklog(entries("1h", "2h")); service.postWorklog(time.today()); assertEquals(2, jira.calls);
    }
    @Test void worklogCanUseLessOrMoreThanEightHours() {
        assertEquals(3600,service.saveWorklog(entries("30m","30m")).path("totalSeconds").asLong());
        assertEquals(36000,service.saveWorklog(entries("6h","4h")).path("totalSeconds").asLong());
    }
    @Test void invalidDurationsAndDuplicateIssuesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->service.saveWorklog(entries("-1h","2h")));
        assertThrows(IllegalArgumentException.class,()->service.saveWorklog(Json.obj("items",List.of(Json.obj("jiraKey","CT-1","duration","1h"),Json.obj("jiraKey","CT-1","duration","2h")))));
        for(String value:List.of("", "0h", "3", "2 hours", "NaNh", "-2h", "1.00001h")) assertThrows(IllegalArgumentException.class,()->WorkDuration.seconds(value));
        assertNull(store.get("worklog","2026-11-04"));
    }
    @Test void postingSameDayTwiceDoesNotSendDuplicateWorklogs() throws Exception {
        service.saveWorklog(entries("3h","2h"));service.postWorklog(time.today());service.postWorklog(time.today());
        assertEquals(2,jira.calls);assertEquals("posted",store.get("worklog","2026-11-04").path("items").get(0).path("state").asText());
        assertThrows(IllegalArgumentException.class,()->service.saveWorklog(entries("2h","2h")));
    }
    @Test void uncertainNetworkResultDoesNotBlindlyRetry() throws Exception {
        service.saveWorklog(entries("3h","2h"));jira.timeout=true;
        assertThrows(java.net.SocketTimeoutException.class,()->service.postWorklog(time.today()));
        assertEquals("unknown",store.get("worklog","2026-11-04").path("items").get(0).path("state").asText());
        jira.timeout=false;
        assertThrows(IllegalStateException.class,()->service.postWorklog(time.today()));assertEquals(1,jira.calls);
        jira.recovered="existing-id";service.postWorklog(time.today());assertEquals(2,jira.calls);
        assertEquals("existing-id",store.get("worklog","2026-11-04").path("items").get(0).path("jiraId").asText());
    }
    @Test void definiteRejectionCanBeRetried() throws Exception {
        service.saveWorklog(entries("3h","2h"));jira.reject=true;
        assertThrows(JiraClient.Rejected.class,()->service.postWorklog(time.today()));jira.reject=false;
        service.postWorklog(time.today());assertEquals(3,jira.calls);
    }
    @Test void anotherIssueCanBeAddedAfterPostingWithoutRepostingExistingTime() throws Exception {
        service.saveWorklog(entries("3h","2h"));service.postWorklog(time.today());
        ObjectNode saved=store.get("worklog",time.today().toString());
        var items=Json.MAPPER.createArrayNode();
        for(JsonNode row:saved.path("items"))items.add(Json.obj("jiraKey",Json.text(row,"jiraKey"),"duration",row.path("seconds").asInt()+"s","marker",Json.text(row,"marker"),"comment",Json.text(row,"comment")));
        items.add(Json.obj("jiraKey","MEET-1","duration","30m"));
        service.saveWorklog(Json.obj("items",items));service.postWorklog(time.today());assertEquals(3,jira.calls);
        assertEquals(19800,store.get("worklog",time.today().toString()).path("totalSeconds").asInt());
    }
    @Test void emptyUnpostedDraftCanBeRemovedAndOldPercentDraftKeepsItsSeconds() {
        store.put("worklog",Json.obj("id",time.today().toString(),"date",time.today().toString(),"totalMinutes",480,"items",List.of(Json.obj("jiraKey","CT-1","percent",33.3333,"seconds",9599,"comment","분석","marker","legacy","state","draft","jiraId",""))));
        service.saveWorklog(Json.obj("items",List.of(Json.obj("jiraKey","CT-1","duration","2h 39m 59s","comment","분석","marker","legacy"))));
        assertEquals(9599,store.get("worklog",time.today().toString()).path("items").get(0).path("seconds").asInt());
        assertFalse(store.get("worklog",time.today().toString()).has("totalMinutes"));
        service.saveWorklog(Json.obj("items",List.of()));assertNull(store.get("worklog",time.today().toString()));
    }
    @Test void personalObservationsNeverAppearInDailyOrBriefing() {
        ObjectNode member=service.member(Json.obj("name","팀원","jiraUser","person","role","개발"));
        service.observation(Json.obj("memberId",Json.text(member,"id"),"text","개인 관찰 내용"));
        assertFalse(reports.daily(time.today()).contains("개인 관찰 내용"));assertFalse(reports.briefing(time.today()).contains("개인 관찰 내용"));
        assertTrue(reports.oneOnOne(Json.text(member,"id")).contains("개인 관찰 내용"));
    }
    @Test void receivedJobIsPendingReviewUntilExplicitlyMarked() {
        ObjectNode job=service.job(Json.obj("title","밤 작업","result","수정 결과","verification","테스트 3개 통과"));
        assertFalse(reports.briefing(time.today()).contains("밤 작업"));assertFalse(job.path("reviewed").asBoolean());job.put("reviewed",true);service.job(job);
        assertTrue(store.get("job",Json.text(job,"id")).path("reviewed").asBoolean());
    }
    @Test void naturalLanguageActionsUseValidatedServiceAndAreIdempotent() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        Agent.Model model=prompt->{calls.incrementAndGet();return Json.obj("reply","처리합니다.","actions",List.of(
                Json.obj("type","task","payload",Json.write(Json.obj("title","리뷰 준비","product","LCT"))),
                Json.obj("type","plan","payload",Json.write(Json.obj("taskIds",List.of("$lastTask"))))));};
        Agent agent=new Agent(service,reports,model,k->Json.object(),"work-agent","work-agent");
        ObjectNode first=agent.chat("web","same-id","리뷰 준비 추가하고 오늘 계획에 넣어 줘.");
        ObjectNode second=agent.chat("web","same-id","리뷰 준비 추가하고 오늘 계획에 넣어 줘.");
        assertEquals(first,second);assertEquals(1,calls.get());assertEquals(1,service.tasks().size());assertEquals(1,service.planned(time.today()).size());
    }
    @Test void quickReadDoesNotCallModel() throws Exception {
        Agent agent=new Agent(service,reports,p->{throw new AssertionError("모델 호출 불필요");},k->Json.object(),"work-agent","work-agent");
        assertTrue(agent.chat("web","brief","브리핑").path("text").asText().contains("데일리 브리핑"));
    }
    @Test void personalCallNameCanPrecedeQuickReadWithoutModelCall() throws Exception {
        Agent agent=new Agent(service,reports,p->{throw new AssertionError("모델 호출 불필요");},k->Json.object(),"호박","호박아");
        assertTrue(agent.chat("web","called-brief","호박아, 브리핑").path("text").asText().contains("데일리 브리핑"));
        assertTrue(agent.chat("web","called-daily","호박아 데일리").path("text").asText().contains("진행한 일"));
    }
    @Test void anotherUserCanChooseTheirOwnNameAndAddress() throws Exception {
        Agent agent=new Agent(service,reports,p->{throw new AssertionError("모델 호출 불필요");},k->Json.object(),"보리","보리야");
        assertEquals("보리입니다. 무엇을 도와드릴까요?",agent.chat("web","greeting","보리야!").path("text").asText());
    }
    @Test void modelGetsPersonalNameAndRequestWithoutAddress() throws Exception {
        var prompt=new java.util.concurrent.atomic.AtomicReference<String>();
        Agent agent=new Agent(service,reports,p->{prompt.set(p);return Json.obj("reply","계획을 함께 정리하겠습니다.","actions",List.of());},k->Json.object(),"호박","호박아");
        agent.chat("web","called-plan","호박아, 오늘 계획을 정리해 줘.");
        assertTrue(prompt.get().contains("너의 이름(JSON 문자열): \"호박\""));
        assertTrue(prompt.get().endsWith("현재 사용자 요청:\n오늘 계획을 정리해 줘."));
        assertEquals("호박아, 오늘 계획을 정리해 줘.",Json.text(store.list("message").getFirst(),"text"));
    }
    @Test void shortRequestsOmitUnrelatedStateAndFindAnExplicitHiddenTask() throws Exception {
        store.put("task", Json.obj("id", "jira:CT-424", "jiraKey", "CT-424", "title", "찾아야 할 업무", "status", "todo", "hidden", true));
        store.put("task", Json.obj("id", "unrelated", "title", "UNRELATED_TASK", "status", "todo"));
        store.put("issue", Json.obj("id", "CT-999", "title", "UNRELATED_JIRA_RECORD"));
        AtomicReference<String> prompt = new AtomicReference<>();
        Agent agent = new Agent(service, new Reports(service), text -> {
            prompt.set(text);
            return Json.obj("reply", "확인했습니다.", "actions", List.of());
        }, kind -> Json.object(), "호박", "호박아");
        agent.chat("web", "short-question", "안녕");
        assertFalse(prompt.get().contains("찾아야 할 업무"));
        assertFalse(prompt.get().contains("UNRELATED_TASK"));
        agent.chat("web", "hidden-question", "CT-424 다시 표시해 줘.");
        assertTrue(prompt.get().contains("jira:CT-424"));
        assertTrue(prompt.get().contains("찾아야 할 업무"));
        assertFalse(prompt.get().contains("UNRELATED_TASK"));
        assertFalse(prompt.get().contains("UNRELATED_JIRA_RECORD"));
    }
    @Test void ordinaryWordStartingWithNameIsNotTreatedAsAnAddress() throws Exception {
        var prompt=new java.util.concurrent.atomic.AtomicReference<String>();
        Agent agent=new Agent(service,reports,p->{prompt.set(p);return Json.obj("reply","요청을 확인했습니다.","actions",List.of());},k->Json.object(),"호박","호박아");
        agent.chat("web","ordinary-word","호박죽 구매를 할 일로 추가해 줘.");
        assertTrue(prompt.get().endsWith("현재 사용자 요청:\n호박죽 구매를 할 일로 추가해 줘."));
    }
    @Test void databaseRollbackPreservesEarlierRecords() {
        assertThrows(IllegalArgumentException.class,()->store.transaction(()->{store.put("test",Json.obj("id","x"));throw new IllegalArgumentException();}));
        assertNull(store.get("test","x"));
    }
    @Test void slackInboxSurvivesRetryAndRejectsDuplicateEvent() {
        assertTrue(store.enqueue("event",Json.obj("text","브리핑")));assertFalse(store.enqueue("event",Json.obj("text","브리핑")));
        assertEquals(1,store.pending().size());store.finish("event");assertTrue(store.pending().isEmpty());
    }
}
