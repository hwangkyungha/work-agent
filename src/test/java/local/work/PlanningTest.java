package local.work;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanningTest {
    @TempDir Path temporary;
    Store store; WorkService service;
    @BeforeEach void open() throws Exception { store=new Store(temporary.resolve("data.db")); service=at("2026-11-06T02:00:00Z"); }
    @AfterEach void close() throws Exception {store.close();}
    WorkService at(String now) throws Exception {return new WorkService(store,new KoreaTime(Clock.fixed(Instant.parse(now),ZoneOffset.UTC)),new JiraClient(new Config(temporary.resolve("none"),Map.of())));}
    String add(String title){return Json.text(service.task(Json.obj("title",title)),"id");}
    List<String> ids(){return service.planned(service.time.today()).stream().map(t->Json.text(t,"id")).toList();}
    @Test void nextWorkingDayCarriesOnlyUnfinishedVisibleTasksAndNewOngoingWork() throws Exception {
        String carry=add("이어 할 일"),done=add("끝낸 일"),hidden=add("숨긴 일");
        service.plan(Json.obj("taskIds",List.of(carry,done,hidden),"sort","manual"));
        service.changeStatus(Json.obj("id",done,"status","done"));service.hideTask(Json.obj("id",hidden));
        String ongoing=Json.text(service.task(Json.obj("title","계획 밖 진행 업무","status","in-progress")),"id");
        service=at("2026-11-09T00:00:00Z");assertEquals(Set.of(carry,ongoing),new HashSet<>(ids()));
        assertEquals("manual",Json.text(service.currentPlan(service.time.today()),"sort"));
        assertEquals(3,store.get("plan","2026-11-06").path("taskIds").size());
    }
    @Test void removingAutomaticTaskPersistsAndNewUrgentWorkStillAppears() {
        String first=Json.text(service.task(Json.obj("title","진행 중","status","in-progress")),"id");assertEquals(List.of(first),ids());
        service.planItem(Json.obj("id",first,"included",false));assertTrue(ids().isEmpty());assertTrue(ids().isEmpty());
        String second=Json.text(service.task(Json.obj("title","급한 요청","urgent",true)),"id");assertEquals(List.of(second),ids());
        service.planItem(Json.obj("id",first));assertEquals(Set.of(first,second),new HashSet<>(ids()));
    }
    @Test void sortAndDragOrderAreSavedAndStaleEditsCannotOverwriteNewerPlan() {
        String normal=add("일반"),urgent=Json.text(service.task(Json.obj("title","긴급","urgent",true)),"id"),due=Json.text(service.task(Json.obj("title","마감","due","2026-11-10")),"id");
        service.plan(Json.obj("taskIds",List.of(normal,urgent,due),"sort","priority"));assertEquals(urgent,ids().getFirst());
        service.plan(Json.obj("taskIds",List.of(normal,urgent,due),"sort","due"));assertEquals(due,ids().getFirst());
        ObjectNode old=service.currentPlan(service.time.today());service.plan(Json.obj("taskIds",List.of(due,normal,urgent),"sort","manual","version",old.path("version").asInt()));
        assertEquals(List.of(due,normal,urgent),ids());
        assertThrows(IllegalArgumentException.class,()->service.plan(Json.obj("taskIds",List.of(normal),"version",old.path("version").asInt())));
    }
    @Test void bulkHideValidatesAllIdsBeforeChangingAnythingAndCanRestore() {
        String a=add("a"),b=add("b");assertThrows(IllegalArgumentException.class,()->service.hideTasks(Json.obj("taskIds",List.of(a,"missing"))));assertEquals(2,service.tasks().size());
        service.hideTasks(Json.obj("taskIds",List.of(a,b)));assertTrue(service.tasks().isEmpty());
        service.hideTasks(Json.obj("taskIds",List.of(a,b),"hidden",false));assertEquals(2,service.tasks().size());
    }
    @Test void planChangesNeverCreateActivitiesOrChangeEnteredWorklogs() throws Exception {
        String id=add("개발");service.planItem(Json.obj("id",id));
        var log=service.saveWorklog(Json.obj("items",List.of(Json.obj("jiraKey","CTS-1","duration","2h 30m")))).deepCopy();
        service.planItem(Json.obj("id",id,"included",false));service.changeStatus(Json.obj("id",id,"status","done"));
        assertEquals(Json.write(log),Json.write(store.get("worklog","2026-11-06")));assertTrue(store.list("activity").isEmpty());assertTrue(ids().isEmpty());
        assertEquals(1,service.completed(service.time.today()).size());assertEquals("done",Json.text(store.list("taskChange").getFirst(),"to"));
    }
    @Test void quickAddAndFollowupAcceptanceAreIdempotentWithoutReopeningOriginalIssue() throws Exception {
        String original=add("기능 구현");service.changeStatus(Json.obj("id",original,"status","done"));
        service=at("2026-11-07T01:00:00Z");
        Agent agent=new Agent(service,new Reports(service),prompt->Json.obj("reply","","actions",List.of(Json.obj("type","task","payload",Json.write(Json.obj("title","리뷰 결과 반영","reason","활동에서 다음 검토가 필요하다고 확인했습니다.","sourceTaskId",original))))),k->Json.object(),"호박","호박아");
        assertEquals(1,agent.suggestFollowups(service.time.today()).path("count").asInt());assertTrue(ids().isEmpty());
        String suggestion=Json.text(store.list("followup").getFirst(),"id");var accepted=service.acceptFollowup(Json.obj("id",suggestion));
        assertEquals(accepted,service.acceptFollowup(Json.obj("id",suggestion)));assertEquals(List.of(Json.text(accepted,"id")),ids());
        assertEquals("done",Json.text(store.get("task",original),"status"));assertEquals("",Json.text(accepted,"jiraKey"));
        service.task(Json.obj("title","직접 입력","today",true));assertEquals(2,ids().size());
    }
    @Test void completionUsesKoreaDateAndReopeningClearsCurrentCompletion() throws Exception {
        String id=add("업무");service=at("2026-11-06T15:00:00Z");service.changeStatus(Json.obj("id",id,"status","done"));
        assertTrue(service.completed(LocalDate.of(2026,11,6)).isEmpty());assertEquals(1,service.completed(LocalDate.of(2026,11,7)).size());
        service.changeStatus(Json.obj("id",id,"status","in-progress"));assertTrue(service.completed(LocalDate.of(2026,11,7)).isEmpty());assertEquals(2,store.list("taskChange").size());
    }
    @Test void remoteCompletionObservationDoesNotInventACompletionDate() {
        store.put("task",Json.obj("id","remote","title","이슈","jiraKey","CTS-9","status","in-progress"));
        ObjectNode row=store.get("task","remote");row.put("status","done");service.saveTask(row);
        assertTrue(service.completed(service.time.today()).isEmpty());assertEquals("observed",Json.text(store.list("taskChange").getFirst(),"source"));
        row=store.get("task","remote");row.put("jiraChangedAt","2026-11-05T08:00:00Z");service.saveTask(row);
        assertEquals(1,service.completed(LocalDate.of(2026,11,5)).size());
    }
    @Test void repeatedAddingKeepsManualOrderAndExclusionBeforeNewActivityPersists() {
        String a=add("먼저 할 일"),b=add("나중에 할 일");service.plan(Json.obj("taskIds",List.of(a,b),"sort","manual"));
        service.planItem(Json.obj("id",a));assertEquals(List.of(a,b),ids());
        String remote=Json.text(service.task(Json.obj("title","오늘 하지 않을 일","jiraKey","CTS-12")),"id");
        service.planItem(Json.obj("id",remote,"included",false));
        service.activity(Json.obj("title","조사 기록","jiraKey","CTS-12"));assertFalse(ids().contains(remote));
    }
    @Test void creationSortUsesActualInstantsAndPutsMissingDatesLastInBothDirections() {
        String later=add("나중에 생성"),earlier=add("먼저 생성"),unknown=add("생성일 없음");
        ObjectNode a=store.get("task",later);a.put("createdAt","2026-11-05T08:00:00Z");store.put("task",a);
        ObjectNode b=store.get("task",earlier);b.put("createdAt","2026-11-05T16:00:00.000+0900");store.put("task",b);
        ObjectNode c=store.get("task",unknown);c.remove("createdAt");store.put("task",c);
        service.plan(Json.obj("taskIds",List.of(later,earlier,unknown),"sort","created-asc"));assertEquals(List.of(earlier,later,unknown),ids());
        service.plan(Json.obj("taskIds",List.of(later,earlier,unknown),"sort","created-desc"));assertEquals(List.of(later,earlier,unknown),ids());
    }
    @Test void existingJiraTasksUseOriginalCreationDateFromCachedIssue() {
        store.put("task",Json.obj("id","legacy","title","기존 업무","jiraKey","CTS-1","status","todo"));
        store.put("issue",Json.obj("id","CTS-1","createdAt","2026-07-01T09:00:00.000+0900"));
        service.plan(Json.obj("taskIds",List.of("legacy"),"sort","created-asc"));
        assertEquals("2026-07-01T00:00:00Z",Json.text(service.planned(service.time.today()).getFirst(),"createdAt"));
        assertEquals("2026-07-01T00:00:00Z",Json.text(service.tasks().getFirst(),"createdAt"));
    }
}
