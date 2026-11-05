package local.work;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskAndEvidenceTest {
    @TempDir Path temporary;
    Store store; WorkService service; WorkflowJira jira;
    @BeforeEach void open() throws Exception {
        store=new Store(temporary.resolve("data.db"));
        Config config=new Config(temporary.resolve("none"),Map.of("JIRA_URL","https://jira.example.invalid","JIRA_KIND","server"));
        jira=new WorkflowJira(config);
        service=new WorkService(store,new KoreaTime(Clock.fixed(Instant.parse("2026-11-04T08:00:00Z"),ZoneOffset.UTC)),jira);
    }
    @AfterEach void close() throws Exception {store.close();}
    static ObjectNode issue(String key,String name,String category) {
        return Json.obj("key",key,"fields",Json.obj("summary",key+" 작업","created","2026-06-01T00:00:00.000+0000","status",Json.obj("id",name,"name",name,"statusCategory",Json.obj("key",category)),"project",Json.obj("name","제품"),"issuetype",Json.obj("name","Task")));
    }
    static final class WorkflowJira extends JiraClient {
        Map<String,ObjectNode> issues=new LinkedHashMap<>(); Map<String,List<JsonNode>> options=new HashMap<>(); List<String> changes=new ArrayList<>();
        WorkflowJira(Config config){super(config);}
        @Override boolean configured(){return true;}
        @Override List<JsonNode> search(String jql){return new ArrayList<>(issues.values());}
        @Override ObjectNode fetchIssue(String key){return summarize(issues.get(key));}
        @Override List<JsonNode> transitions(String key){return options.getOrDefault(key,List.of());}
        @Override void transition(String key,String id){changes.add(key+":"+id);issues.put(key,issue(key,"검증 완료","done"));}
    }
    ObjectNode importTask(String key) throws Exception {
        jira.issues.put(key,issue(key,"개발 중","indeterminate"));jira.syncIssues(service);return store.get("task","jira:"+key);
    }
    @Test void projectsUseTheirOwnAllowedTransitionNames() throws Exception {
        ObjectNode a=importTask("CTS-1"),b=importTask("LCT-2");
        jira.options.put("CTS-1",List.of(Json.obj("id","31","to",Json.obj("id","done-a","name","검증 완료","statusCategory",Json.obj("key","done")))));
        jira.options.put("LCT-2",List.of(Json.obj("id","77","to",Json.obj("id","review-b","name","기능 리뷰","statusCategory",Json.obj("key","indeterminate")))));
        assertEquals("검증 완료",service.transitions(Json.text(a,"id")).path("transitions").get(0).path("to").path("name").asText());
        assertEquals("기능 리뷰",service.transitions(Json.text(b,"id")).path("transitions").get(0).path("to").path("name").asText());
        ObjectNode updated=service.changeStatus(Json.obj("id",Json.text(a,"id"),"transitionId","31"));
        assertEquals("done",Json.text(updated,"status"));assertEquals(List.of("CTS-1:31"),jira.changes);
        assertEquals("https://jira.example.invalid/browse/CTS-1",Json.text(updated,"issueUrl"));
        assertEquals("2026-06-01T00:00:00.000+0000",Json.text(updated,"createdAt"));
        assertThrows(IllegalArgumentException.class,()->service.changeStatus(Json.obj("id",Json.text(b,"id"),"transitionId","31")));
    }
    @Test void extraRequiredTransitionFieldsAreNotSilentlySkipped() throws Exception {
        ObjectNode task=importTask("CTS-1");jira.options.put("CTS-1",List.of(Json.obj("id","31","to",Json.obj("name","완료"),"fields",Json.obj("resolution",Json.obj("required",true)))));
        assertThrows(IllegalArgumentException.class,()->service.changeStatus(Json.obj("id",Json.text(task,"id"),"transitionId","31")));assertTrue(jira.changes.isEmpty());
    }
    @Test void hidingSurvivesSyncAndDoesNotChangeJiraOrPlans() throws Exception {
        ObjectNode task=importTask("CTS-1");String id=Json.text(task,"id");service.plan(Json.obj("taskIds",List.of(id)));
        service.hideTask(Json.obj("id",id));jira.syncIssues(service);
        assertTrue(service.tasks().isEmpty());assertTrue(service.planned(service.time.today()).isEmpty());assertTrue(jira.changes.isEmpty());
        assertEquals("개발 중",Json.text(jira.fetchIssue("CTS-1"),"jiraStatus"));
        service.hideTask(Json.obj("id",id,"hidden",false));assertEquals(1,service.tasks().size());
    }
    @Test void jiraStateCannotBeChangedByEditingLocalTaskPayload() throws Exception {
        ObjectNode task=importTask("CTS-1");
        assertThrows(IllegalArgumentException.class,()->service.task(Json.obj("id",Json.text(task,"id"),"status","done")));
        assertEquals("in-progress",Json.text(store.get("task",Json.text(task,"id")),"status"));
    }
    @Test void localTaskHasThreeStatesAndLegacyStateStillLoads() throws Exception {
        ObjectNode task=service.task(Json.obj("title","로컬 업무","status","open"));
        assertEquals("todo",Json.text(task,"status"));service.changeStatus(Json.obj("id",Json.text(task,"id"),"status","in-progress"));
        assertEquals("done",Json.text(service.changeStatus(Json.obj("id",Json.text(task,"id"),"status","done")),"status"));
        assertThrows(IllegalArgumentException.class,()->service.task(Json.obj("title","잘못된 상태","status","unknown")));
    }
    @Test void priorityIsBothThenUrgentThenImportantThenNeither() {
        service.task(Json.obj("title","없음"));service.task(Json.obj("title","중요","important",true));service.task(Json.obj("title","긴급","urgent",true));service.task(Json.obj("title","둘 다","important",true,"urgent",true));
        assertEquals(List.of("둘 다","긴급","중요","없음"),service.tasks().stream().map(n->Json.text(n,"title")).toList());
    }
    @Test void allTodayCandidatesAreAccessibleAndDailyBriefingOmitsMeetingsAndProjectTotals() {
        for(int i=0;i<9;i++)service.task(Json.obj("title","업무 "+i));
        assertEquals(9,service.todayGroups(service.time.today()).stream().mapToInt(g->g.path("tasks").size()).sum());
        service.plan(Json.obj("taskIds",service.tasks().stream().map(n->Json.text(n,"id")).toList()));
        String report=new Reports(service).briefing(service.time.today());assertTrue(report.contains("업무 8"));assertFalse(report.contains("오늘 회의"));assertFalse(report.contains("프로젝트 현황"));assertTrue(report.contains("데일리 브리핑"));
    }
    @Test void evidenceGroupsTwoToolsByIssueAndCountsMessagesExactlyOnce() {
        store.put("issue",Json.obj("id","CTS-1","title","보고서 개선"));
        for(String source:List.of("codex","claude")) {
            store.put("activity",Json.obj("id",source,"date","2026-11-04","source",source,"session","session1","title","검토했습니다.","jiraKey","CTS-1"));
            for(int i=0;i<3;i++)store.put("evidence",Json.obj("id",source+i,"date","2026-11-04","source",source,"session","session1","role",i==0?"user":"assistant","text","보고서 개선","issueKeys",List.of("CTS-1")));
        }
        var groups=new ActivitySummary(store).group(service.time.today(),service.time.today());assertEquals(1,groups.size());
        var group=groups.getFirst();assertEquals(6,group.path("messageCount").asInt());assertEquals(2,group.path("userMessages").asInt());assertEquals(4,group.path("assistantMessages").asInt());assertEquals(2,group.path("sessionCount").asInt());assertEquals("보고서 개선",Json.text(group,"title"));
    }
    @Test void ambiguousIssueEvidenceUsesSessionNameWithoutDoublingCounts() {
        store.put("session",Json.obj("id","claude:s1","name","API 리뷰"));
        store.put("evidence",Json.obj("id","e1","date","2026-11-04","source","claude","session","s1","role","user","text","두 이슈를 함께 검토","issueKeys",List.of("CTS-1","CTS-2")));
        var groups=new ActivitySummary(store).group(service.time.today(),service.time.today());assertEquals(1,groups.size());assertEquals("",Json.text(groups.getFirst(),"jiraKey"));assertEquals("API 리뷰",Json.text(groups.getFirst(),"title"));assertEquals(1,groups.getFirst().path("messageCount").asInt());
    }
    @Test void sessionWithMultipleIssuesKeepsEarlierSingleIssueMessagesInTheSameGroup() {
        store.put("session",Json.obj("id","codex:s1","name","통합 검토"));
        store.put("evidence",Json.obj("id","early","date","2026-11-04","source","codex","session","s1","role","user","text","CTS-1 검토","issueKeys",List.of("CTS-1")));
        store.put("evidence",Json.obj("id","later","date","2026-11-04","source","codex","session","s1","role","user","text","CTS-2도 검토","issueKeys",List.of("CTS-1","CTS-2")));
        var groups=new ActivitySummary(store).group(service.time.today(),service.time.today());
        assertEquals(1,groups.size());assertEquals("",Json.text(groups.getFirst(),"jiraKey"));assertEquals(2,groups.getFirst().path("messageCount").asInt());
    }
    @Test void registeringMemberDoesNotRequireOrKeepFixedRole() {
        ObjectNode member=service.member(Json.obj("name","팀원","jiraUser","person","role","옛 역할"));assertFalse(member.has("role"));
    }
    @Test void refreshingRemoteStatePreservesLocalChangesMadeWhileTheRequestWasInFlight() throws Exception {
        ObjectNode stale=importTask("CTS-1");String id=Json.text(stale,"id");
        service.hideTask(Json.obj("id",id));service.task(Json.obj("id",id,"important",true));
        ObjectNode updated=jira.updateTask(service,stale,jira.fetchIssue("CTS-1"));
        assertTrue(updated.path("hidden").asBoolean());assertTrue(updated.path("important").asBoolean());
    }
}
