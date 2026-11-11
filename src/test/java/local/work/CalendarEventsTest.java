package local.work;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.net.*;
import java.net.http.*;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CalendarEventsTest {
    @TempDir Path temporary;
    Store store; WorkService service; Config config;
    @BeforeEach void open() throws Exception {
        config=new Config(temporary.resolve("none"),Map.of("GROUPWARE_CONFIG",temporary.resolve("groupware/settings.json").toString()));
        store=new Store(temporary.resolve("work.db"));
        service=new WorkService(store,new KoreaTime(Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"),ZoneOffset.UTC)),new JiraClient(config));
    }
    @AfterEach void close() throws Exception { store.close(); }
    @Test void localOnlyRowsAreNotASourceOfCalendarEvents() {
        store.put("meeting",Json.obj("id","local","source","manual","title","서현지 휴가","start","2026-11-11T00:00:00Z","end",""));
        store.put("meeting",Json.obj("id","google","source","calendar","title","서현지 휴가","start","2026-11-10T15:00:00Z","end","2026-11-11T15:00:00Z","allDay",true));
        assertEquals(1,service.meetings(service.time.today()).size());
        assertTrue(service.meetings(service.time.today()).getFirst().path("allDay").asBoolean());
    }
    class Google extends GoogleBookingCalendar {
        final Map<String,ObjectNode> events=new LinkedHashMap<>();
        int writes;boolean timeoutAfterCreate,failList;String patchEtag="";ObjectNode patchBody;
        Google() throws Exception {super(new GroupwareSettings(config));}
        @Override HttpResponse<String> send(String method,String url,ObjectNode body,String etag) throws Exception {
            URI uri=URI.create(url);String path=uri.getPath(),id=path.substring(path.lastIndexOf('/')+1);
            if(method.equals("GET")&&id.equals("events")) {
                if(failList)throw new IOException("목록 연결 오류");
                return new Response(200,Json.write(Json.obj("accessRole","owner","items",events.values())));
            }
            if(method.equals("GET"))return new Response(events.containsKey(id)?200:404,Json.write(events.getOrDefault(id,Json.object())));
            if(method.equals("POST")) {
                id=Json.text(body,"id");if(events.containsKey(id))return new Response(409,"{}");
                ObjectNode saved=body.deepCopy().put("etag","v1").put("htmlLink","https://calendar.google.com/calendar/event?eid="+id);events.put(id,saved);writes++;
                if(timeoutAfterCreate){timeoutAfterCreate=false;throw new IOException("저장 응답 유실");}
                return new Response(201,Json.write(saved));
            }
            ObjectNode existing=events.get(id);if(existing==null)return new Response(404,"{}");
            if(!etag.isBlank()&&!etag.equals(Json.text(existing,"etag")))return new Response(412,"{}");
            if(method.equals("PATCH")){patchEtag=etag;patchBody=body.deepCopy();existing.setAll(body);existing.put("etag","v2");writes++;return new Response(200,Json.write(existing));}
            if(method.equals("DELETE")){events.remove(id);writes++;return new Response(204,"");}
            throw new AssertionError(method);
        }
    }
    record Response(int statusCode,String body) implements HttpResponse<String> {
        public HttpRequest request(){return null;}public Optional<HttpResponse<String>> previousResponse(){return Optional.empty();}
        public HttpHeaders headers(){return HttpHeaders.of(Map.of(),(a,b)->true);}public Optional<SSLSession> sslSession(){return Optional.empty();}
        public URI uri(){return URI.create("https://www.googleapis.com");}public HttpClient.Version version(){return HttpClient.Version.HTTP_1_1;}
    }
    ObjectNode leave() {return Json.obj("id","request:leave","title","서현지 휴가","allDay",true,"date","2026-11-11","endDate","2026-11-11");}
    @Test void allDayCreationUsesGoogleAndOneKoreaDateAndRetryDoesNotDuplicate() throws Exception {
        Google google=new Google();CalendarSync calendar=new CalendarSync(config,service,google);
        ObjectNode row=calendar.save(leave());calendar.save(leave());assertEquals(1,google.writes);assertEquals(1,google.events.size());
        ObjectNode event=google.events.values().iterator().next();assertEquals("2026-11-11",event.path("start").path("date").asText());assertEquals("2026-11-12",event.path("end").path("date").asText());
        assertEquals("2026-11-10T15:00:00Z",Json.text(row,"start"));assertTrue(row.path("allDay").asBoolean());assertFalse(Json.text(row,"calendarUrl").isBlank());
        assertEquals(1,service.meetings(LocalDate.of(2026,11,11)).size());assertTrue(service.meetings(LocalDate.of(2026,11,12)).isEmpty());
        calendar.sync();assertEquals(1,service.meetings(service.time.today()).size());
    }
    @Test void lostCreationReplyIsRecoveredAndFailureNeverCreatesALocalOnlyEvent() throws Exception {
        Google google=new Google();CalendarSync calendar=new CalendarSync(config,service,google);google.timeoutAfterCreate=true;
        assertThrows(IOException.class,()->calendar.save(leave()));assertTrue(service.meetings(service.time.today()).isEmpty());
        calendar.save(leave());assertEquals(1,google.events.size());assertEquals(1,google.writes);
        CalendarSync disconnected=new CalendarSync(config,service,null);assertThrows(IllegalArgumentException.class,()->disconnected.save(leave().put("id","other")));assertEquals(1,store.list("meeting").size());
    }
    @Test void updateSwitchesAllDayAndTimedWithoutLosingOtherGoogleFieldsAndRejectsStaleEdits() throws Exception {
        Google google=new Google();CalendarSync calendar=new CalendarSync(config,service,google);ObjectNode row=calendar.save(leave());
        google.events.values().iterator().next().put("description","기존 설명");
        ObjectNode timed=Json.obj("id",Json.text(row,"id"),"operation","update","title","휴가 시간 변경","allDay",false,"start","2026-11-11T13:00:00+09:00","end","2026-11-11T18:00:00+09:00","etag","v1");
        ObjectNode saved=calendar.save(timed);assertEquals("2026-11-11T04:00:00Z",Json.text(saved,"start"));assertFalse(saved.path("allDay").asBoolean());
        assertTrue(google.patchBody.path("start").path("date").isNull());assertEquals("v1",google.patchEtag);assertEquals("기존 설명",Json.text(google.events.values().iterator().next(),"description"));
        assertThrows(IllegalArgumentException.class,()->calendar.save(timed));
        calendar.save(leave().put("id",Json.text(saved,"id")).put("operation","update").put("etag","v2"));assertTrue(google.patchBody.path("start").path("dateTime").isNull());
    }
    @Test void deletionAffectsGoogleAndCacheAndExternalDeletionDisappearsOnRefresh() throws Exception {
        Google google=new Google();CalendarSync calendar=new CalendarSync(config,service,google);ObjectNode row=calendar.save(leave());
        calendar.delete(Json.obj("id",Json.text(row,"id"),"etag","v1"));assertTrue(google.events.isEmpty());assertTrue(store.list("meeting").isEmpty());
        calendar.save(leave().put("id","second"));google.events.clear();calendar.sync();assertTrue(store.list("meeting").isEmpty());
    }
    @Test void invalidTimeIsNotGuessedAndFailedRefreshKeepsTheLastGoogleSnapshot() throws Exception {
        Google google=new Google();CalendarSync calendar=new CalendarSync(config,service,google);
        assertThrows(IllegalArgumentException.class,()->calendar.save(Json.obj("title","회의","start","2026-11-11T09:00:00+09:00")));assertEquals(0,google.writes);
        assertThrows(IllegalArgumentException.class,()->calendar.save(leave().put("endDate","2026-11-10")));
        assertThrows(IllegalArgumentException.class,()->calendar.save(leave().put("date","날짜 없음")));
        calendar.save(leave());ObjectNode previous=store.list("meeting").getFirst();google.failList=true;assertThrows(IOException.class,calendar::sync);assertEquals(previous,store.list("meeting").getFirst());
    }
    @Test void localRetirementBacksUpAndArchivesInsteadOfRegisteringAnythingInGoogle() throws Exception {
        Google google=new Google();CalendarSync calendar=new CalendarSync(config,service,google);
        ObjectNode local=Json.obj("id","local","source","manual","title","서현지 휴가","start","2026-11-11T00:00:00Z");store.put("meeting",local);calendar.save(leave());calendar.retireLocalEvents();
        assertEquals(local,store.get("retiredMeeting","local"));assertNull(store.get("meeting","local"));assertEquals(1,store.list("meeting").size());assertEquals(1,google.writes);
        try(var files=Files.list(temporary.resolve("backups"))){assertEquals(1,files.count());}
    }
    @Test void slackRegistersAndEditsGoogleEventsAndReceivesActualIdsForFollowUp() throws Exception {
        Google google=new Google();CalendarSync calendar=new CalendarSync(config,service,google);
        Agent agent=new Agent(service,new Reports(service),prompt->Json.obj("reply","","actions",List.of(Json.obj("type","meeting","payload",Json.write(leave())))),kind->Json.object(),"호박","호박아").calendar(calendar);
        ObjectNode result=agent.chat("slack","leave","오늘 일정에 서현지 휴가를 종일로 등록해줘");assertTrue(Json.text(result,"text").contains("Google 캘린더에 반영"));assertTrue(Json.text(result,"text").contains("calendar.google.com"));
        JsonNode context=new AgentContext(service).forRequest("오늘 일정 수정",List.of());assertFalse(Json.text(context.path("meetings").get(0),"id").isBlank());assertEquals("v1",Json.text(context.path("meetings").get(0),"etag"));
    }
    @Test void pagedRefreshIsAtomicAndKeepsDistinctOccurrencesWithTheSameTitle() throws Exception {
        int[] calls={0};boolean[] fail={true};
        Google google=new Google(){@Override ObjectNode listEvents(Instant from,Instant to,String token)throws Exception{
            calls[0]++;assertEquals("2026-10-27T15:00:00Z",from.toString());
            if(token.isBlank())return Json.obj("accessRole","owner","nextPageToken","page2","items",List.of(Json.obj("id","one","summary","같은 제목","start",Json.obj("date","2026-11-11"),"end",Json.obj("date","2026-11-12"))));
            if(fail[0])throw new IOException("다음 페이지 실패");
            return Json.obj("items",List.of(Json.obj("id","two","summary","같은 제목","start",Json.obj("date","2026-11-11"),"end",Json.obj("date","2026-11-12"))));
        }};
        CalendarSync calendar=new CalendarSync(config,service,google);ObjectNode old=calendar.save(leave());assertThrows(IOException.class,calendar::sync);assertEquals(old,store.list("meeting").getFirst());
        fail[0]=false;calendar.sync();assertEquals(2,store.list("meeting").size());assertEquals(4,calls[0]);
    }
    @Test void readOnlyAccessCannotWriteOrDeleteAndDoesNotFallBackToLocalStorage() throws Exception {
        Google google=new Google();CalendarSync calendar=new CalendarSync(config,service,google);ObjectNode row=calendar.save(leave());
        store.put("sync",Json.obj("id","calendar","accessRole","reader","ok",true));assertFalse(calendar.writable());
        assertThrows(IllegalArgumentException.class,()->calendar.save(leave().put("id","other")));assertThrows(IllegalArgumentException.class,()->calendar.delete(Json.obj("id",Json.text(row,"id"))));
        assertEquals(1,google.writes);assertEquals(1,store.list("meeting").size());
    }
}
