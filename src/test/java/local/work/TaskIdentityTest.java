package local.work;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskIdentityTest {
    @TempDir Path temporary;
    Store store; WorkService service;
    @BeforeEach void open() throws Exception {
        store = new Store(temporary.resolve("test.db"));
        service = new WorkService(store, new KoreaTime(Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC)), new JiraClient(new Config(temporary.resolve("none"), Map.of())));
    }
    @AfterEach void close() throws Exception { store.close(); }
    @Test void linkingALocalTaskToAnAlreadyImportedIssueKeepsOneTaskAndTheLocalIdentity() {
        ObjectNode local = service.task(Json.obj("title", "먼저 계획한 업무", "workGroup", "워크플로우 개선", "important", true));
        String id = Json.text(local, "id");
        store.put("task", Json.obj("id", "jira:CT2612-510", "jiraKey", "CT2612-510", "title", "Jira에서 수집한 업무", "status", "todo", "hidden", false));
        service.task(Json.obj("id", id, "jiraKey", "CT2612-510"));
        assertEquals(1, service.tasks().size());
        assertEquals(id, Json.text(service.tasks().getFirst(), "id"));
        assertEquals("워크플로우 개선", Json.text(service.tasks().getFirst(), "workGroup"));
        assertTrue(service.tasks().getFirst().path("important").asBoolean());
    }
    ObjectNode imported() { return Json.obj("id","jira:CT2612-510","jiraKey","CT2612-510","title","원격 제목","status","todo","important",false,"urgent",false,"hidden",false); }
    @Test void repairKeepsDependenciesPlansActivitiesWorklogsAndRecordedValuesAndIsIdempotent() {
        String old="jira:CT2612-510",id=Json.text(service.task(Json.obj("title","원래 업무","jiraKey","CT2612-510","important",true,"workType","회의")),"id");
        String before=Json.text(service.task(Json.obj("title","먼저 할 일")),"id");
        service.task(Json.obj("id",id,"predecessorIds",List.of(before)));
        store.put("task",imported());
        String next=Json.text(service.task(Json.obj("title","후속 업무","predecessorIds",List.of(old))),"id");
        ObjectNode plan=Json.obj("id","2026-11-10","date","2026-11-10","taskIds",List.of(old,id,next),"version",3,"sort","manual","updatedAt","2026-11-10T08:00:00Z","initialTasks",List.of(Json.obj("id",old,"title","그날의 이름","status","todo")),"taskSnapshots",List.of(Json.obj("id",old,"title","그날의 이름","status","todo")));
        store.put("plan",plan);store.put("planRevision",plan.deepCopy().put("id","2026-11-10:3"));
        for(String kind:List.of("activity","evidence","sessionDay","sessionLink","workLink","taskChange"))store.put(kind,Json.obj("id",kind.equals("workLink")?old:kind+"-1","taskId",old,"date","2026-11-10","title","기록 당시 이름","at","2026-11-10T09:30:00Z"));
        store.put("followup",Json.obj("id","proposal","sourceTaskId",old,"acceptedTaskId",old,"title","후속 제안"));
        store.put("draftDigest",Json.obj("id","digest","prs",List.of(Json.obj("reviewSuggestion",Json.obj("acceptedTaskId",old)))));
        ObjectNode posted=Json.obj("taskId",old,"workId",old,"jiraKey","CT2612-510","seconds",7200,"state","posted","marker","unchanged-marker","jiraId","remote-123","comment","이미 등록한 설명");
        ObjectNode draft=Json.obj("taskId",id,"workId",id,"jiraKey","CT2612-510","seconds",3600,"state","draft","marker","second-marker","comment","추가 설명");
        store.put("worklog",Json.obj("id","2026-11-10","date","2026-11-10","totalSeconds",10800,"items",List.of(posted,draft)));
        store.put("workDay",Json.obj("id","2026-11-10:"+old,"date","2026-11-10","taskId",old,"workId",old,"outcome","progress","note","그날의 메모","title","기록 당시 이름","updatedAt","2026-11-10T09:30:00Z"));
        TaskIdentity identity=new TaskIdentity(store);assertEquals(1,identity.repair());
        assertNull(store.get("task",old));assertEquals(id,identity.resolve(old));
        assertEquals(List.of(before),Json.MAPPER.convertValue(store.get("task",id).path("predecessorIds"),List.class));
        assertEquals(id,store.get("task",next).path("predecessorIds").get(0).asText());
        ObjectNode savedPlan=store.get("plan","2026-11-10");assertEquals(List.of(id,next),Json.MAPPER.convertValue(savedPlan.path("taskIds"),List.class));
        assertEquals("그날의 이름",savedPlan.path("initialTasks").get(0).path("title").asText());assertEquals(id,savedPlan.path("taskSnapshots").get(0).path("id").asText());assertEquals("2026-11-10T08:00:00Z",Json.text(savedPlan,"updatedAt"));
        for(String kind:List.of("activity","evidence","sessionDay","sessionLink","workLink","taskChange"))assertEquals(id,Json.text(store.list(kind).getFirst(),"taskId"));
        assertEquals(id,Json.text(store.get("followup","proposal"),"sourceTaskId"));assertEquals(id,store.get("draftDigest","digest").path("prs").get(0).path("reviewSuggestion").path("acceptedTaskId").asText());
        ObjectNode log=store.get("worklog","2026-11-10");assertEquals(10800,log.path("totalSeconds").asInt());assertEquals(2,log.path("items").size());
        ObjectNode expected=posted.deepCopy().put("taskId",id).put("workId",id);assertEquals(expected,log.path("items").get(0));assertEquals(draft,log.path("items").get(1));
        assertNull(store.get("workDay","2026-11-10:"+old));assertEquals("그날의 메모",Json.text(store.get("workDay","2026-11-10:"+id),"note"));
        service.plan(Json.obj("date","2026-11-11","taskIds",List.of(old,id)));
        assertEquals(1,service.planned(service.time.today()).size());
        long revision=store.revision();assertEquals(0,identity.repair());assertEquals(revision,store.revision());assertEquals(1,store.list("taskMerge").size());
        assertThrows(IllegalStateException.class,()->store.put("task",imported()));
    }
    @Test void newRegistrationAndStaleSyncReuseTheOwnerInsteadOfCreatingAnotherTask() throws Exception {
        String id=Json.text(service.task(Json.obj("title","원래 업무","jiraKey","CT2612-510","important",true)),"id");
        new TaskIdentity(store).repair();
        assertEquals(id,Json.text(service.task(Json.obj("title","새로 입력한 이름","jiraKey","ct2612-510","important",false,"today",true)),"id"));
        assertTrue(store.get("task",id).path("important").asBoolean());assertEquals(1,service.planned(service.time.today()).size());
        ObjectNode stale=imported();store.put("taskAlias",Json.obj("id",Json.text(stale,"id"),"taskId",id));
        ObjectNode remote=Json.obj("id","CT2612-510","title","최신 Jira 이름","status","in-progress","due","2026-11-20");
        service.jira.updateTask(service,stale,remote);service.jira.updateTask(service,stale,remote);
        assertEquals(1,service.tasks().size());assertEquals("in-progress",Json.text(store.get("task",id),"status"));assertEquals("2026-11-20",Json.text(store.get("task",id),"due"));assertTrue(store.get("task",id).path("important").asBoolean());
        service.task(Json.obj("id",Json.text(stale,"id"),"workType","리뷰"));assertEquals("리뷰",Json.text(store.get("task",id),"workType"));
    }
    @Test void conflictingSettingsAndDailyOutcomesLeaveOriginalRecordsIntact() {
        String old="jira:CT2612-510",id=Json.text(service.task(Json.obj("title","원래 업무","jiraKey","CT2612-510","workGroup","로컬 분류")),"id");
        store.put("task",imported().put("workGroup","다른 분류"));TaskIdentity identity=new TaskIdentity(store);
        assertEquals(0,identity.repair());assertEquals(2,service.tasks().size());assertTrue(store.list("taskAlias").isEmpty());assertFalse(store.list("taskMergeConflict").isEmpty());
        store.put("task",imported());store.put("plan",Json.obj("id","2026-11-10","date","2026-11-10","taskIds",List.of(old),"version",2));
        for(var entry:Map.of(old,"progress",id,"done").entrySet())store.put("workDay",Json.obj("id","2026-11-10:"+entry.getKey(),"date","2026-11-10","taskId",entry.getKey(),"workId",entry.getKey(),"outcome",entry.getValue()));
        assertEquals(0,identity.repair());assertEquals(old,store.get("plan","2026-11-10").path("taskIds").get(0).asText());assertEquals(2,store.get("plan","2026-11-10").path("version").asInt());assertEquals(2,store.list("workDay").size());assertEquals(2,service.tasks().size());
    }
    @Test void nestedPlanChangesRollBackWithTheOuterTaskOperation() {
        String id=Json.text(service.task(Json.obj("title","원래 제목")),"id");service.plan(Json.obj("taskIds",List.of(id)));
        ObjectNode original=store.get("plan",service.time.today().toString());
        assertThrows(IllegalArgumentException.class,()->store.transaction(()->{service.task(Json.obj("id",id,"title","바뀐 제목"));throw new IllegalArgumentException("중간 실패");}));
        assertEquals("원래 제목",Json.text(store.get("task",id),"title"));assertEquals(original,store.get("plan",service.time.today().toString()));
    }
    @Test void separateDraftMarkersAndTimeRemainEditableAfterBothWorkIdsBecomeOne() {
        String id=Json.text(service.task(Json.obj("title","업무","jiraKey","CT2612-510")),"id");store.put("task",imported());
        store.put("worklog",Json.obj("id","2026-11-10","date","2026-11-10","totalSeconds",10800,"items",List.of(
                Json.obj("workId","jira:CT2612-510","taskId","jira:CT2612-510","jiraKey","","title","첫 기록","marker","one","seconds",7200,"state","draft"),
                Json.obj("workId",id,"taskId",id,"jiraKey","","title","둘째 기록","marker","two","seconds",3600,"state","draft"))));
        new TaskIdentity(store).repair();
        ObjectNode saved=service.saveWorklog(Json.obj("date","2026-11-10","items",List.of(
                Json.obj("workId",id,"taskId",id,"jiraKey","","title","첫 기록","marker","one","duration","2h"),
                Json.obj("workId",id,"taskId",id,"jiraKey","","title","둘째 기록","marker","two","duration","1h"))));
        assertEquals(10800,saved.path("totalSeconds").asInt());assertEquals(2,saved.path("items").size());
        assertThrows(IllegalArgumentException.class,()->service.saveWorklog(Json.obj("date","2026-11-10","items",List.of(
                Json.obj("workId",id,"jiraKey","","title","첫 기록","marker","one","duration","2h"),
                Json.obj("workId",id,"jiraKey","","title","새 중복","duration","1h")))));
    }
    @Test void bothSyncAndLinkOrdersConvergeToTheOriginalLocalTask() throws Exception {
        JiraClient remote=new JiraClient(new Config(temporary.resolve("none"),Map.of())) {
            @Override void loadSiteUrl() {}
            @Override List<JsonNode> search(String jql) {
                return List.of(Json.obj("key","CT2612-510","fields",Json.obj("summary","Jira 이름","created","2026-11-06T00:00:00Z","updated","2026-11-11T00:00:00Z","duedate","2026-11-20","status",Json.obj("name","In Progress","id","3","statusCategory",Json.obj("key","indeterminate")))));
            }
        };
        String id=Json.text(service.task(Json.obj("title","원래 할 일","workGroup","내 업무 묶음")),"id");new TaskIdentity(store).repair();
        remote.syncIssues(service);assertEquals(2,service.tasks().size());
        service.task(Json.obj("id",id,"jiraKey","CT2612-510"));remote.syncIssues(service);remote.syncIssues(service);
        assertEquals(1,service.tasks().size());assertEquals(id,Json.text(service.tasks().getFirst(),"id"));assertEquals("내 업무 묶음",Json.text(service.tasks().getFirst(),"workGroup"));assertEquals("in-progress",Json.text(service.tasks().getFirst(),"status"));
        store.delete("task",id);
        String linkedFirst=Json.text(service.task(Json.obj("title","먼저 연결한 업무","jiraKey","CT2612-510")),"id");
        remote.syncIssues(service);assertEquals(1,service.tasks().size());assertEquals(linkedFirst,Json.text(service.tasks().getFirst(),"id"));
    }
    @Test void anOldSyncSnapshotCannotOverwriteAWorkWhoseJiraLinkWasChanged() {
        String id=Json.text(service.task(Json.obj("title","원래 업무","jiraKey","CT2612-510")),"id");
        store.put("taskAlias",Json.obj("id","jira:CT2612-510","taskId",id));
        service.task(Json.obj("id",id,"jiraKey","CT2612-530"));
        service.jira.updateTask(service,imported(),Json.obj("id","CT2612-510","title","이전 이슈의 최신 상태","status","in-progress"));
        assertEquals("CT2612-530",Json.text(store.get("task",id),"jiraKey"));assertEquals("원래 업무",Json.text(store.get("task",id),"title"));
        ObjectNode owner=new TaskIdentity(store).forKey("CT2612-510");assertNotNull(owner);assertNotEquals(id,Json.text(owner,"id"));assertEquals("in-progress",Json.text(owner,"status"));
    }
}
