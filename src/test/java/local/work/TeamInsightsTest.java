package local.work;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TeamInsightsTest {
    @TempDir Path temporary;
    Store store; WorkService service; StatsJira jira; String member;
    @BeforeEach void open() throws Exception {
        store=new Store(temporary.resolve("data.db"));
        jira=new StatsJira(new Config(temporary.resolve("none"),Map.of("JIRA_URL","https://jira.example.invalid","JIRA_KIND","server")));
        service=new WorkService(store,new KoreaTime(Clock.fixed(Instant.parse("2026-11-04T08:00:00Z"),ZoneOffset.UTC)),jira);
        member=Json.text(service.member(Json.obj("name","팀원","jiraUser","person")),"id");
    }
    @AfterEach void close() throws Exception {store.close();}
    static final class StatsJira extends JiraClient {
        List<JsonNode> issues=new ArrayList<>();Map<String,List<JsonNode>> histories=new HashMap<>(),logs=new HashMap<>(),notes=new HashMap<>();String jql;
        StatsJira(Config config){super(config);}
        @Override boolean configured(){return true;}
        @Override String resolveUser(String user){return user;}
        @Override List<JsonNode> search(String value,String fields){jql=value;return issues;}
        @Override List<JsonNode> changelog(String key){return histories.getOrDefault(key,List.of());}
        @Override List<JsonNode> worklogs(String key){return logs.getOrDefault(key,List.of());}
        @Override List<JsonNode> comments(String key){return notes.getOrDefault(key,List.of());}
    }
    static ObjectNode issue(String key,String assignee) {
        return Json.obj("key",key,"fields",Json.obj("summary",key+" 작업","created","2026-06-01T00:00:00.000+0000","assignee",Json.obj("accountId",assignee),"description",Json.obj("type","doc","content",List.of(Json.obj("type","paragraph","content",List.of(Json.obj("type","text","text","검증 환경을 기다렸습니다.")))))));
    }
    static JsonNode change(String at,String from,String to){return Json.obj("created",at,"items",List.of(Json.obj("fieldId","assignee","from",from,"to",to)));}
    static JsonNode log(String at,String who,int seconds){return Json.obj("started",at,"author",Json.obj("accountId",who),"timeSpentSeconds",seconds);}
    ObjectNode query(){return Json.obj("memberId",member,"from","2026-07-01","to","2026-09-30","issuePrefix","CTS-*");}
    @Test void historicalAssignmentCountsAndWorklogsUseExactKoreaBoundaries() throws Exception {
        jira.issues.addAll(List.of(issue("CTS-1","other"),issue("CTS-2","person"),issue("CTS-3","person"),issue("CTS-4","other"),issue("OTHER-1","person")));
        jira.histories.put("CTS-1",List.of(change("2026-08-01T00:00:00Z","person","other")));
        jira.histories.put("CTS-2",List.of(change("2026-10-01T00:00:00Z","other","person")));
        jira.histories.put("CTS-4",List.of(change("2026-06-30T15:00:00Z","person","other")));
        jira.logs.put("CTS-1",List.of(log("2026-06-30T15:00:00.000+0000","person",3600),log("2026-07-01T00:00:00Z","other",7200)));
        jira.logs.put("CTS-2",List.of(log("2026-09-30T14:59:59Z","person",9000)));
        jira.logs.put("CTS-3",List.of(log("2026-09-30T15:00:00Z","person",10800)));
        jira.logs.put("CTS-4",List.of(log("2026-06-30T14:59:59Z","person",10000)));
        ObjectNode result=new TeamInsights(service).query(query());
        assertEquals(2,result.path("assignedIssueCount").asInt());assertEquals(12600,result.path("loggedSeconds").asLong());
        assertEquals(3600,result.path("byMonth").path("2026-07").asLong());assertEquals(9000,result.path("byMonth").path("2026-09").asLong());
        assertEquals(3,result.path("issues").size());assertTrue(jira.jql.contains("assignee WAS"));assertTrue(jira.jql.contains("project = \"CTS\""));
    }
    @Test void reasonsIncludeAdfCommentsAndOnlyRequestedMemberObservations() throws Exception {
        jira.issues.add(issue("CTS-1","person"));
        jira.notes.put("CTS-1",List.of(Json.obj("created","2026-08-01T00:00:00Z","body",Json.obj("type","doc","content",List.of(Json.obj("type","text","text","재현 환경 접근을 기다리는 중입니다."))))));
        service.observation(Json.obj("memberId",member,"date","2026-08-05","text","팀원이 환경 문제를 공유했습니다."));
        service.observation(Json.obj("memberId",member,"date","2026-10-05","text","범위 밖 기록"));
        String another=Json.text(service.member(Json.obj("name","다른 팀원","jiraUser","other")),"id");
        service.observation(Json.obj("memberId",another,"date","2026-08-05","text","다른 사람 기록"));
        ObjectNode input=query();input.put("includeReasons",true);ObjectNode result=new TeamInsights(service).query(input);
        assertTrue(Json.write(result).contains("재현 환경 접근"));assertTrue(Json.write(result).contains("검증 환경"));assertTrue(Json.write(result).contains("환경 문제를 공유"));assertFalse(Json.write(result).contains("범위 밖 기록"));assertFalse(Json.write(result).contains("다른 사람 기록"));
    }
    @Test void futurePartOfHalfYearIsNotPresentedAsObservedStatistics() throws Exception {
        ObjectNode input=query();input.put("to","2026-12-31");ObjectNode result=new TeamInsights(service).query(input);
        assertEquals("2026-11-04",Json.text(result,"to"));assertEquals("2026-12-31",Json.text(result,"requestedTo"));
    }
    @Test void invalidPrefixAndUnknownMemberCannotBecomeArbitraryJql() {
        ObjectNode input=query();input.put("issuePrefix","CTS OR 1=1");assertThrows(IllegalArgumentException.class,()->new TeamInsights(service).query(input));
        input.put("memberId","unknown");assertThrows(IllegalArgumentException.class,()->new TeamInsights(service).query(input));
    }
    @Test void naturalLanguageAnalysisGetsFetchedFactsAndCannotExecuteSuggestedActions() throws Exception {
        jira.issues.add(issue("CTS-1","person"));
        AtomicInteger calls=new AtomicInteger();
        Agent.Model model=prompt->{
            if(calls.getAndIncrement()==0)return Json.obj("reply","", "actions",List.of(Json.obj("type","team_query","payload",Json.write(query()))));
            assertTrue(prompt.contains("assignedIssueCount"));assertTrue(prompt.contains("담당 건수는 현재 담당자가 아니라"));
            return Json.obj("reply","해당 기간에 담당한 이슈가 있습니다. 지연 원인은 현재 근거로 확인되지 않았습니다.","actions",List.of());
        };
        Agent agent=new Agent(service,new Reports(service),model,k->Json.object(),"호박","호박아");
        String text=Json.text(agent.chat("web","stats","호박아, 이 팀원의 3분기 CTS 업무를 정리해 줘."),"text");
        assertEquals(2,calls.get());assertTrue(text.contains("담당 이슈 1개"));assertTrue(text.contains("원인은 현재 근거로 확인되지 않았습니다"));
        AtomicInteger badCalls=new AtomicInteger();
        Agent bad=new Agent(service,new Reports(service),prompt->badCalls.getAndIncrement()==0?Json.obj("reply","","actions",List.of(Json.obj("type","team_query","payload",Json.write(query())))):Json.obj("reply","","actions",List.of(Json.obj("type","task","payload","{}"))),k->Json.object(),"호박","호박아");
        assertThrows(IllegalStateException.class,()->bad.chat("web","bad-stats","팀원 통계를 보여 줘."));assertTrue(store.list("task").isEmpty());
    }
}
