package local.work;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorklogSyncTest {
    @TempDir Path temporary;
    Store store; WorkService service; FakeJira jira; WorklogSync sync;
    static class FakeJira extends JiraClient {
        Map<String,List<JsonNode>> logs = new LinkedHashMap<>(); boolean fail; int writes;
        FakeJira(Config config) { super(config); }
        @Override boolean configured() { return true; }
        @Override boolean cloud() { return true; }
        @Override JsonNode request(String method,String path,JsonNode body) { assertEquals("GET",method);assertEquals("/myself",path);return Json.obj("accountId","owner"); }
        @Override List<JsonNode> search(String jql,String fields) { assertTrue(jql.contains("worklogAuthor = \"owner\""));assertFalse(jql.contains("assignee"));return logs.keySet().stream().map(key->(JsonNode)Json.obj("key",key,"fields",Json.obj("summary",key+" 업무"))).toList(); }
        @Override List<JsonNode> worklogs(String key,LocalDate from,LocalDate to) throws Exception { if(fail)throw new IOException("offline");assertEquals(LocalDate.of(2026,11,2),from);assertEquals(LocalDate.of(2026,11,8),to);return logs.getOrDefault(key,List.of()); }
        @Override ObjectNode fetchIssue(String key) {return Json.obj("id",key,"title",key+" 회의");}
        @Override String issueUrl(String key) {return "https://example.atlassian.net/browse/"+key;}
        @Override String addWorklog(LocalDate day,JsonNode item) {writes++;return "new-"+writes;}
    }
    @BeforeEach void open() throws Exception {
        store=new Store(temporary.resolve("test.db"));jira=new FakeJira(new Config(temporary.resolve("none"),Map.of()));
        service=new WorkService(store,new KoreaTime(Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"),ZoneOffset.UTC)),jira);sync=new WorklogSync(service);
    }
    @AfterEach void close() throws Exception {store.close();}
    ObjectNode log(String id,String owner,String started,int hours) {return Json.obj("id",id,"author",Json.obj("accountId",owner),"started",started,"timeSpentSeconds",hours*3600,"comment","회의");}
    void localPosted() {
        store.put("worklog",Json.obj("id","2026-11-03","date","2026-11-03","updatedAt","2026-11-03T09:00:00Z","items",List.of(Json.obj("jiraKey","CT-1","seconds",14400,"state","posted","marker","work-agent:one","jiraId","one","comment","개발"))));
    }
    @Test void filtersOwnerAndKoreaDateDeduplicatesAndKeepsDrafts() throws Exception {
        localPosted();service.saveWorklog(Json.obj("date","2026-11-04","items",List.of(Json.obj("jiraKey","WOR-1","duration",""))));
        jira.logs.put("CT-1",List.of(log("one","owner","2026-11-03T00:00:00Z",4),log("other","someone","2026-11-03T00:00:00Z",8)));
        jira.logs.put("WOR-1517",List.of(log("two","owner","2026-11-03T14:59:59Z",4),log("late","owner","2026-11-08T15:00:00Z",1)));
        sync.sync(LocalDate.of(2026,11,3));sync.sync(LocalDate.of(2026,11,3));
        List<ObjectNode> rows=service.registeredWorklogs(LocalDate.of(2026,11,3),LocalDate.of(2026,11,3));assertEquals(2,rows.size());assertEquals(28800,rows.stream().mapToLong(row->row.path("seconds").asLong()).sum());
        assertEquals(2,store.list("jiraWorklog").size());assertEquals(0,store.get("worklog","2026-11-04").path("items").get(0).path("seconds").asInt());assertEquals(0,jira.writes);
        assertEquals("work-agent",rows.stream().filter(r->Json.text(r,"jiraId").equals("one")).findFirst().orElseThrow().path("source").asText());
    }
    @Test void editsDeletesAndFailedSyncKeepConsistentSnapshots() throws Exception {
        localPosted();jira.logs.put("CT-1",List.of(log("one","owner","2026-11-03T00:00:00Z",2)));sync.sync(LocalDate.of(2026,11,3));
        assertEquals(7200,service.registeredWorklogs(LocalDate.of(2026,11,3),LocalDate.of(2026,11,3)).getFirst().path("seconds").asInt());
        jira.fail=true;assertThrows(IOException.class,()->sync.sync(LocalDate.of(2026,11,3)));assertEquals(1,store.list("jiraWorklog").size());
        assertEquals(7200,service.registeredWorklogs(LocalDate.of(2026,11,3),LocalDate.of(2026,11,3)).getFirst().path("seconds").asInt());
        jira.fail=false;jira.logs.put("CT-1",List.of());sync.sync(LocalDate.of(2026,11,3));
        assertTrue(service.registeredWorklogs(LocalDate.of(2026,11,3),LocalDate.of(2026,11,3)).isEmpty());
        ObjectNode day=store.get("worklog","2026-11-03");day.put("updatedAt","2026-11-07T00:00:00Z");store.put("worklog",day);
        assertTrue(service.registeredWorklogs(LocalDate.of(2026,11,3),LocalDate.of(2026,11,3)).isEmpty());
    }
    @Test void favoritesAreIndependentFromTasksAndRecentIssuesComeFromRegisteredLogs() throws Exception {
        service.worklogFavorite(Json.obj("jiraKey","wor-1517"));assertEquals(1,store.list("worklogFavorite").size());assertTrue(service.tasks().isEmpty());
        jira.logs.put("WOR-1517",List.of(log("first","owner","2026-11-03T00:00:00Z",2),log("second","owner","2026-11-04T00:00:00Z",2)));
        sync.sync(LocalDate.of(2026,11,3));assertEquals(1,service.recentWorklogIssues().size());assertEquals("2026-11-04",Json.text(service.recentWorklogIssues().getFirst(),"lastDate"));
        service.worklogFavorite(Json.obj("jiraKey","WOR-1517","favorite",false));assertTrue(store.list("worklogFavorite").isEmpty());assertFalse(store.list("jiraWorklog").isEmpty());
    }
    @Test void addingTimeToPreviouslyPostedIssueDoesNotRepostPreviousEntry() throws Exception {
        localPosted();ObjectNode old=store.get("worklog","2026-11-03");JsonNode row=old.path("items").get(0);
        service.saveWorklog(Json.obj("date","2026-11-03","items",List.of(Json.obj("jiraKey","CT-1","duration","4h","comment","개발","marker",Json.text(row,"marker")),Json.obj("jiraKey","CT-1","duration","1h","marker","work-agent:new"))));
        service.postWorklog(LocalDate.of(2026,11,3));assertEquals(1,jira.writes);assertEquals(2,store.get("worklog","2026-11-03").path("items").size());
    }
    @Test void unknownDeliveryIsRecoveredByMarkerAndReportDoesNotCountItTwice() throws Exception {
        localPosted();ObjectNode day=store.get("worklog","2026-11-03");((ObjectNode)day.path("items").get(0)).put("state","unknown").put("jiraId","");store.put("worklog",day);
        jira.logs.put("CT-1",List.of(log("one","owner","2026-11-03T00:00:00Z",4).put("comment","분석 [work-agent:one]")));
        jira.logs.put("WOR-1517",List.of(log("two","owner","2026-11-03T00:00:00Z",4)));
        sync.sync(LocalDate.of(2026,11,3));
        assertEquals("posted",Json.text(store.get("worklog","2026-11-03").path("items").get(0),"state"));
        String report=new Reports(service).worklog(LocalDate.of(2026,11,3),LocalDate.of(2026,11,3));
        assertEquals(1,report.split("CT-1 / 4h",-1).length-1);assertTrue(report.contains("WOR-1517 / 4h"));assertEquals(0,jira.writes);
    }
    @Test void newPostsAfterSnapshotStartStayVisibleUntilNextSync() throws Exception {
        localPosted();ObjectNode day=store.get("worklog","2026-11-03");((ObjectNode)day.path("items").get(0)).put("registeredAt","2026-11-06T00:00:01Z");store.put("worklog",day);
        jira.logs.put("CT-1",List.of());sync.sync(LocalDate.of(2026,11,3));
        assertEquals(1,service.registeredWorklogs(LocalDate.of(2026,11,3),LocalDate.of(2026,11,3)).size());
        assertFalse(store.get("worklog","2026-11-03").path("items").get(0).path("snapshotSeen").asBoolean());
    }
}
