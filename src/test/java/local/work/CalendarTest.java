package local.work;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CalendarTest {
    @TempDir Path temporary;
    Store store;WorkService service;CalendarSync calendar;
    @BeforeEach void open() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        store=new Store(temporary.resolve("db.sqlite"));Config config=new Config(temporary.resolve("none"),Map.of());
        service=new WorkService(store,new KoreaTime(Clock.fixed(Instant.parse("2026-11-04T01:00:00Z"),ZoneOffset.UTC)),new JiraClient(config));calendar=new CalendarSync(config,service);
    }
    @AfterEach void close() throws Exception {store.close();}
    void importText(String events) throws Exception {
        String text="BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Work Agent//KO\r\n"+events+"END:VCALENDAR\r\n";
        calendar.importCalendar(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
    String event(String uid,String dates,String extras) {return "BEGIN:VEVENT\r\nUID:"+uid+"\r\nDTSTAMP:20261101T000000Z\r\nSUMMARY:기능 리뷰\r\n"+dates+extras+"END:VEVENT\r\n";}
    @Test void recurringUtcMeetingsAreConvertedToKoreanDaysAndExceptionsRemoved() throws Exception {
        importText(event("review","DTSTART:20261103T150000Z\r\nDTEND:20261103T160000Z\r\n","RRULE:FREQ=DAILY;COUNT=3\r\nEXDATE:20261104T150000Z\r\n"));
        assertEquals(2,store.list("meeting").size());assertEquals(1,service.meetings(LocalDate.of(2026,11,4)).size());assertTrue(service.meetings(LocalDate.of(2026,11,5)).isEmpty());
    }
    @Test void cancellationRemovesImportedMeetingButKeepsManualMeeting() throws Exception {
        String dates="DTSTART:20261104T010000Z\r\nDTEND:20261104T020000Z\r\n";
        importText(event("review",dates,""));store.put("meeting",Json.obj("source","manual","title","개인 일정","start","2026-11-04T10:00:00+09:00"));
        importText(event("review",dates,"STATUS:CANCELLED\r\n"));assertEquals(1,store.list("meeting").size());assertEquals("개인 일정",Json.text(store.list("meeting").get(0),"title"));
    }
    @Test void recurrenceOverrideDoesNotCreateDuplicateOriginalTime() throws Exception {
        importText(event("review","DTSTART:20261104T010000Z\r\nDTEND:20261104T020000Z\r\n","RRULE:FREQ=DAILY;COUNT=2\r\n")
                +event("review","DTSTART:20261105T030000Z\r\nDTEND:20261105T040000Z\r\n","RECURRENCE-ID:20261105T010000Z\r\n"));
        assertEquals(2,store.list("meeting").size());assertEquals("2026-11-05T03:00:00Z",Json.text(service.meetings(LocalDate.of(2026,11,5)).get(0),"start"));
    }
    @Test void floatingTimeAndAllDayUseKoreaTime() throws Exception {
        importText(event("floating","DTSTART:20261104T100000\r\nDTEND:20261104T110000\r\n","")
                +event("holiday","DTSTART;VALUE=DATE:20261105\r\nDTEND;VALUE=DATE:20261106\r\n",""));
        assertEquals("2026-11-04T01:00:00Z",Json.text(service.meetings(LocalDate.of(2026,11,4)).get(0),"start"));
        assertEquals(1,service.meetings(LocalDate.of(2026,11,5)).size());assertTrue(service.meetings(LocalDate.of(2026,11,5)).get(0).path("allDay").asBoolean());
    }
}
