package local.work;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImportTest {
    @TempDir Path temporary;
    Store store; WorkService service; TranscriptImporter importer;
    @BeforeEach void open() throws Exception {
        store=new Store(temporary.resolve("db.sqlite"));Config config=new Config(temporary.resolve("none"),Map.of());
        service=new WorkService(store,new KoreaTime(Clock.fixed(Instant.parse("2026-11-04T01:00:00Z"),ZoneOffset.UTC)),new JiraClient(config));
        importer=new TranscriptImporter(service,config);
    }
    @AfterEach void close() throws Exception {store.close();}
    String codex(String role,String text,String timestamp) {
        return Json.write(Json.obj("type","response_item","timestamp",timestamp,"payload",Json.obj("type","message","role",role,"content",List.of(Json.obj("type","output_text","text",text)))))+"\n";
    }
    @Test void partialLastLineIsReadAfterItFinishes() throws Exception {
        Path file=temporary.resolve("session.jsonl");String full=codex("assistant","수정했습니다.","2026-11-03T15:30:00Z");
        Files.writeString(file,full.substring(0,full.length()-2));
        assertEquals(0,importer.read("codex",file,Instant.EPOCH));assertTrue(store.list("activity").isEmpty());
        Files.writeString(file,full.substring(full.length()-2),StandardOpenOption.APPEND);
        assertEquals(1,importer.read("codex",file,Instant.EPOCH));assertEquals(1,store.list("activity").size());
        assertEquals(0,importer.read("codex",file,Instant.EPOCH));
    }
    @Test void sessionMessagesBecomeOneActivityPerKoreaDayAndKeepVerifiedIssueFromUser() throws Exception {
        store.put("issue",Json.obj("id","CT-123","title","실제 업무"));
        Path file=temporary.resolve("session.jsonl");
        Files.writeString(file,codex("user","CT-123을 수정해 줘.","2026-11-03T15:01:00Z")+codex("assistant","분석했습니다.","2026-11-03T15:02:00Z")+codex("assistant","수정 결과입니다.","2026-11-03T15:03:00Z"));
        assertEquals(3,importer.read("codex",file,Instant.EPOCH));
        assertEquals(1,store.list("activity").size());var a=store.list("activity").get(0);
        assertEquals("2026-11-04",Json.text(a,"date"));assertEquals("CT-123",Json.text(a,"jiraKey"));assertEquals("수정 결과입니다.",Json.text(a,"title"));
        assertEquals(3,store.list("evidence").size());
    }
    @Test void multipleIssuesAreNotArbitrarilyAssigned() throws Exception {
        Path file=temporary.resolve("session.jsonl");
        Files.writeString(file,codex("user","CT-123과 CT-456 관련 분석", "2026-11-04T00:00:00Z")+codex("assistant","분석 결과입니다.","2026-11-04T00:01:00Z"));
        importer.read("codex",file,Instant.EPOCH);assertEquals("",Json.text(store.list("activity").get(0),"jiraKey"));
    }
    @Test void claudeCustomTitleWithoutTimestampNamesTheSession() throws Exception {
        Path file=temporary.resolve("named.jsonl");
        Files.writeString(file,Json.write(Json.obj("type","custom-title","sessionId","named-session","customTitle","리뷰 준비"))+"\n"
                +Json.write(Json.obj("type","user","sessionId","named-session","timestamp","2026-11-04T00:00:00Z","message",Json.obj("role","user","content","리뷰 준비를 도와줘.")))+"\n");
        assertEquals(1,importer.read("claude",file,Instant.EPOCH));
        var groups=new ActivitySummary(store).group(LocalDate.parse("2026-11-04"),LocalDate.parse("2026-11-04"));
        assertEquals("리뷰 준비",Json.text(groups.getFirst(),"title"));
        assertEquals(1,groups.getFirst().path("messageCount").asInt());
    }
    @Test void userLinkedIssueSurvivesLaterImport() throws Exception {
        Path file=temporary.resolve("session.jsonl");Files.writeString(file,codex("assistant","CT-1 수정","2026-11-04T00:01:00Z"));
        importer.read("codex",file,Instant.EPOCH);var a=store.list("activity").get(0);a.put("jiraKey","CT-9").put("userLinked",true);store.put("activity",a);
        Files.writeString(file,codex("assistant","CT-2도 확인했습니다.","2026-11-04T00:02:00Z"),StandardOpenOption.APPEND);
        importer.read("codex",file,Instant.EPOCH);assertEquals("CT-9",Json.text(store.list("activity").get(0),"jiraKey"));
    }
    @Test void claudeToolResultsAndSidechainsDoNotBecomeActivities() throws Exception {
        Path file=temporary.resolve("claude.jsonl");
        String tool=Json.write(Json.obj("type","user","timestamp","2026-11-04T00:00:00Z","message",Json.obj("role","user","content",List.of(Json.obj("type","tool_result","content","기밀 출력")))))+"\n";
        String assistant=Json.write(Json.obj("type","assistant","sessionId","abc","timestamp","2026-11-04T00:01:00Z","message",Json.obj("role","assistant","content",List.of(Json.obj("type","thinking","thinking","비공개"),Json.obj("type","text","text","CT-2 검증 결과")))))+"\n";
        Files.writeString(file,tool+assistant);assertEquals(1,importer.read("claude",file,Instant.EPOCH));assertEquals(1,store.list("activity").size());
        assertFalse(Json.write(store.list("evidence")).contains("비공개"));assertFalse(Json.write(store.list("evidence")).contains("기밀 출력"));
    }
}
