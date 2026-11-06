package local.work;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecommendationTest {
    @TempDir Path temporary;
    Store store; WorkService service; LocalDate day=LocalDate.of(2026,11,5);
    @BeforeEach void open() throws Exception {
        store=new Store(temporary.resolve("data.db"));
        service=new WorkService(store,new KoreaTime(Clock.fixed(Instant.parse("2026-11-04T15:00:00Z"),ZoneOffset.UTC)),new JiraClient(new Config(temporary.resolve("none"),Map.of())));
    }
    @AfterEach void close() throws Exception {store.close();}
    ObjectNode task(String key,String status,String due,boolean important,boolean urgent) {
        return store.put("task",Json.obj("id",key,"title",key+" 업무","jiraKey",key,"status",status,"due",due,"important",important,"urgent",urgent,"hidden",false));
    }
    void session(String key,int daysAgo) {
        String date=day.minusDays(daysAgo).toString(),session=key+date;
        for(String role:List.of("user","assistant"))store.put("evidence",Json.obj("id",session+role,"source","codex","session",session,"date",date,"role",role,"text",key+" 작업","issueKeys",List.of(key)));
    }
    List<String> keys(String group) {return service.todayGroups(day).stream().filter(g->Json.text(g,"id").equals(group)).findFirst().orElseThrow().path("tasks").findValuesAsText("jiraKey");}
    @Test void recentWorkRequiresInProgressAndSessionsFromExactlyYesterdayThroughThreeDaysAgo() {
        for(int i=0;i<=4;i++){String key="CTS-"+i;task(key,"in-progress","",false,false);session(key,i);}
        task("CTS-5","todo","",false,false);session("CTS-5",1);
        task("CTS-6","done","",false,false);session("CTS-6",1);
        service.hideTask(Json.obj("id","CTS-2"));
        assertEquals(List.of("CTS-1","CTS-3"),keys("recent"));assertTrue(service.planned(day).isEmpty());
    }
    @Test void manualNotesAndUnrelatedOrUnlinkedSessionsDoNotMakeOngoingWorkRecent() {
        task("CTS-10","in-progress","",false,false);
        service.activity(Json.obj("date",day.minusDays(1).toString(),"jiraKey","CTS-10","title","수동 기록"));
        task("CTS-11","in-progress","",false,false);session("CTS-99",1);
        store.put("evidence",Json.obj("id","unlinked","source","claude","session","unlinked","date",day.minusDays(1).toString(),"role","assistant","text","이슈 미지정 세션","issueKeys",List.of()));
        assertTrue(keys("recent").isEmpty());
    }
    @Test void deadlineWindowIncludesTodayAndSeventhDayAndExcludesPastAndEighthDay() {
        for(int offset:List.of(-1,0,3,7,8))task("CTS-"+(offset+20),"todo",day.plusDays(offset).toString(),false,false);
        task("CTS-30","done",day.toString(),false,false);
        assertEquals(List.of("CTS-20","CTS-23","CTS-27"),keys("due"));
    }
    @Test void markedWorkCanAppearInEachMatchingCategoryAndAddingRemovesItFromAllCandidates() {
        task("CTS-40","in-progress",day.toString(),true,true);session("CTS-40",1);
        task("CTS-41","todo","",false,true);task("CTS-42","todo","",true,false);task("CTS-43","todo","",false,false);
        assertEquals(List.of("CTS-40","CTS-41","CTS-42"),keys("priority"));assertTrue(keys("recent").contains("CTS-40"));assertTrue(keys("due").contains("CTS-40"));
        service.planItem(Json.obj("id","CTS-40"));for(String group:List.of("recent","due","priority"))assertFalse(keys(group).contains("CTS-40"));
        service.plan(Json.obj("taskIds",List.of()));assertTrue(service.planned(day).isEmpty());assertTrue(keys("recent").contains("CTS-40"));
        service.hideTask(Json.obj("id","CTS-41"));assertFalse(keys("priority").contains("CTS-41"));
    }
    @Test void briefingDoesNotTurnCandidatesIntoTodaysPlanAndQuickRegistrationRemainsExplicit() {
        task("CTS-50","in-progress",day.toString(),true,true);session("CTS-50",1);
        String report=new Reports(service).briefing(day);String plan=report.substring(report.indexOf("\n오늘 할 일"));
        assertTrue(plan.contains("직접 선택"));assertFalse(plan.contains("CTS-50"));assertNull(store.get("plan",day.toString()));
        ObjectNode added=service.task(Json.obj("title","직접 등록한 오늘 업무","today",true));
        assertEquals(List.of(Json.text(added,"id")),service.planned(day).stream().map(t->Json.text(t,"id")).toList());
    }
}
