package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.*;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

final class CalendarSync {
    private final Config config;
    private final WorkService service;
    private final GoogleBookingCalendar google;
    CalendarSync(Config config, WorkService service) throws Exception {
        this(config,service,GoogleBookingCalendar.configured(config)?new GoogleBookingCalendar(new GroupwareSettings(config)):null);
    }
    CalendarSync(Config config,WorkService service,GoogleBookingCalendar google) {this.config=config;this.service=service;this.google=google;}
    boolean configured() { return configured(config); }
    boolean writable() {
        ObjectNode last=service.store.get("sync","calendar");String role=last==null?"":Json.text(last,"accessRole");
        return google!=null&&(role.isBlank()||Set.of("owner","writer").contains(role));
    }
    static boolean configured(Config config) { return GoogleBookingCalendar.configured(config) || config.has("CALENDAR_ICS_URL") || config.has("CALENDAR_ICS_FILE"); }
    void retireLocalEvents() {
        List<ObjectNode> local=service.store.list("meeting").stream().filter(row->!Json.text(row,"source").equals("calendar")).toList();
        if(local.isEmpty())return;
        service.store.backup("before-google-calendar");
        service.store.transaction(()->local.forEach(row->{service.store.put("retiredMeeting",row);service.store.delete("meeting",Json.text(row,"id"));}));
    }
    synchronized ObjectNode sync() throws Exception {
        if(google!=null)return syncGoogle();
        if (!configured()) throw new IllegalArgumentException("캘린더 ICS 주소나 파일을 설정하세요.");
        byte[] bytes;
        if (config.has("CALENDAR_ICS_URL")) {
            URI uri = URI.create(config.get("CALENDAR_ICS_URL", "").replaceFirst("^webcal://", "https://"));
            HttpResponse<byte[]> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build()
                    .send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) throw new IllegalStateException("캘린더를 읽지 못했습니다(HTTP " + response.statusCode() + ").");
            bytes = response.body();
        } else bytes = Files.readAllBytes(Path.of(config.get("CALENDAR_ICS_FILE", "")));
        return importCalendar(new ByteArrayInputStream(bytes));
    }
    private ObjectNode syncGoogle() throws Exception {
        Instant from=service.time.today().minusDays(14).atStartOfDay(KoreaTime.ZONE).toInstant(),to=service.time.today().plusDays(60).atStartOfDay(KoreaTime.ZONE).toInstant();
        List<ObjectNode> rows=new ArrayList<>();String token="",role="";
        do {
            ObjectNode page=google.listEvents(from,to,token);role=page.path("accessRole").asText(role);
            for(JsonNode event:page.path("items"))if(!Json.text(event,"status").equals("cancelled"))rows.add(cached(event));
            token=Json.text(page,"nextPageToken");
        }while(!token.isBlank());
        service.store.transaction(()->{
            for(ObjectNode old:service.store.list("meeting"))if(Json.text(old,"source").equals("calendar"))service.store.delete("meeting",Json.text(old,"id"));
            rows.forEach(row->service.store.put("meeting",row));
        });
        ObjectNode result=Json.obj("id","calendar","at",service.time.instant().toString(),"count",rows.size(),"ok",true,"accessRole",role,"provider","google");
        service.store.put("sync",result);return result;
    }
    synchronized ObjectNode save(JsonNode input) throws Exception {
        if(google==null)throw new IllegalArgumentException("Google Calendar 로그인이 필요합니다. ./groupware.ps1 calendar-login을 실행하세요.");
        if(!writable())throw new IllegalArgumentException("이 Google 캘린더에는 쓰기 권한이 없습니다.");
        String operation=input.path("operation").asText("create"),id=Json.text(input,"id");
        if(!Set.of("create","update","delete").contains(operation))throw new IllegalArgumentException("일정 작업 종류를 확인하세요.");
        boolean update=operation.equals("update")||id.startsWith("calendar:");
        if(operation.equals("delete"))return delete(input);
        ObjectNode body,saved;
        try {body=eventBody(input);}catch(DateTimeException invalid){throw new IllegalArgumentException("날짜와 시각 형식을 확인하세요. 한국 시간 기준 날짜 또는 시작·종료 시각이 필요합니다.",invalid);}
        if(update) {
            ObjectNode old=requiredEvent(id),current=google.getEvent(Json.required(old,"googleEventId"));
            if(current==null)throw new IllegalArgumentException("Google에서 삭제된 일정입니다. 새로고침하세요.");
            String expected=input.path("etag").asText(Json.text(old,"etag"));
            if(!expected.isBlank()&&!expected.equals(Json.text(current,"etag")))throw new IllegalArgumentException("Google에서 일정이 변경됐습니다. 새로고침 후 다시 수정하세요.");
            saved=google.updateEvent(Json.text(old,"googleEventId"),body,Json.text(current,"etag"));
        } else {
            String seed=Json.text(input,"requestId");if(seed.isBlank())seed=id.isBlank()?UUID.randomUUID().toString():id;
            String eventId="a"+TranscriptImporter.hash("work-agent-event:"+google.calendarId()+":"+seed);
            saved=google.createEvent(eventId,body);
            if(!sameEvent(body,saved))throw new IllegalArgumentException("같은 요청으로 등록된 일정이 있습니다. Google 일정을 확인한 뒤 수정하세요.");
        }
        ObjectNode row=cached(saved);service.store.put("meeting",row);return row;
    }
    synchronized ObjectNode delete(JsonNode input) throws Exception {
        if(google==null)throw new IllegalArgumentException("Google Calendar 로그인이 필요합니다.");
        if(!writable())throw new IllegalArgumentException("이 Google 캘린더에는 쓰기 권한이 없습니다.");
        ObjectNode row=requiredEvent(Json.required(input,"id"));String expected=input.path("etag").asText(Json.text(row,"etag"));
        google.deleteEvent(Json.required(row,"googleEventId"),expected);service.store.delete("meeting",Json.text(row,"id"));
        return Json.obj("deleted",true,"title",Json.text(row,"title"),"calendarUrl",Json.text(row,"calendarUrl"));
    }
    private ObjectNode requiredEvent(String id) {
        ObjectNode row=service.store.get("meeting",id);
        if(row==null||Json.text(row,"googleEventId").isBlank())throw new IllegalArgumentException("Google 일정 ID를 찾지 못했습니다. 캘린더를 새로고침한 뒤 선택하세요.");
        return row;
    }
    static ObjectNode eventBody(JsonNode input) {
        ObjectNode body=Json.obj("summary",Json.required(input,"title"));
        if(input.path("allDay").asBoolean()) {
            LocalDate from=LocalDate.parse(Json.required(input,"date")),until=Json.text(input,"endDate").isBlank()?from:LocalDate.parse(Json.text(input,"endDate"));
            if(until.isBefore(from))throw new IllegalArgumentException("종일 일정의 마지막 날은 시작일보다 빠를 수 없습니다.");
            body.set("start",Json.obj("date",from.toString()));body.set("end",Json.obj("date",until.plusDays(1).toString()));
        } else {
            if(Json.text(input,"start").isBlank()||Json.text(input,"end").isBlank())throw new IllegalArgumentException("시작·종료 시각이 필요합니다. 시간이 없는 일정은 종일로 등록하세요.");
            Instant from=KoreaTime.parse(Json.text(input,"start")),until=KoreaTime.parse(Json.text(input,"end"));
            if(!until.isAfter(from))throw new IllegalArgumentException("종료 시각은 시작 시각보다 늦어야 합니다.");
            body.set("start",Json.obj("dateTime",from.atZone(KoreaTime.ZONE).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME),"timeZone","Asia/Seoul"));
            body.set("end",Json.obj("dateTime",until.atZone(KoreaTime.ZONE).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME),"timeZone","Asia/Seoul"));
        }
        return body;
    }
    private static boolean sameEvent(JsonNode expected,JsonNode actual) {
        if(!Json.text(expected,"summary").equals(Json.text(actual,"summary")))return false;
        for(String field:List.of("start","end")) {
            if(expected.path(field).has("date")) {if(!Json.text(expected.path(field),"date").equals(Json.text(actual.path(field),"date")))return false;}
            else if(!actual.path(field).hasNonNull("dateTime") || !KoreaTime.parse(Json.text(expected.path(field),"dateTime")).equals(KoreaTime.parse(Json.text(actual.path(field),"dateTime"))))return false;
        }
        return true;
    }
    private ObjectNode cached(JsonNode event) {
        boolean allDay=event.path("start").hasNonNull("date");
        Instant from=allDay?LocalDate.parse(Json.required(event.path("start"),"date")).atStartOfDay(KoreaTime.ZONE).toInstant():KoreaTime.parse(Json.required(event.path("start"),"dateTime"));
        Instant until=allDay?LocalDate.parse(Json.required(event.path("end"),"date")).atStartOfDay(KoreaTime.ZONE).toInstant():KoreaTime.parse(Json.required(event.path("end"),"dateTime"));
        String eventId=Json.required(event,"id");
        return Json.obj("id","calendar:"+TranscriptImporter.hash(google.calendarId()+":"+eventId),"googleEventId",eventId,"recurringEventId",Json.text(event,"recurringEventId"),"calendarId",google.calendarId(),"etag",Json.text(event,"etag"),"calendarUrl",Json.text(event,"htmlLink"),"title",event.path("summary").asText("제목 없는 일정"),"start",from.toString(),"end",until.toString(),"date",from.atZone(KoreaTime.ZONE).toLocalDate().toString(),"endDate",until.atZone(KoreaTime.ZONE).toLocalDate().minusDays(allDay?1:0).toString(),"allDay",allDay,"source","calendar","updatedAt",Json.text(event,"updated"));
    }
    ObjectNode importCalendar(InputStream input) throws Exception {
        var calendar = new CalendarBuilder().build(input);
        Instant from = service.time.today().minusDays(14).atStartOfDay(KoreaTime.ZONE).toInstant();
        Instant to = service.time.today().plusDays(60).atStartOfDay(KoreaTime.ZONE).toInstant();
        Period period = new Period(new DateTime(java.util.Date.from(from)), new DateTime(java.util.Date.from(to)));
        Set<String> overrides = new HashSet<>();
        List<VEvent> events = new ArrayList<>();
        for (CalendarComponent component : calendar.getComponents()) if (component instanceof VEvent event) {
            events.add(event);
            if (event.getRecurrenceId() != null) overrides.add(event.getUid().getValue() + ":" + event.getRecurrenceId().getDate().getTime());
        }
        List<ObjectNode> rows = new ArrayList<>();
        for (VEvent event : events) {
            if (event.getStatus() != null && event.getStatus().getValue().equals("CANCELLED")) continue;
            String uid = event.getUid() == null ? TranscriptImporter.hash(event.toString()) : event.getUid().getValue();
            for (Period occurrence : event.calculateRecurrenceSet(period)) {
                long anchor = event.getRecurrenceId() == null ? occurrence.getStart().getTime() : event.getRecurrenceId().getDate().getTime();
                if (event.getRecurrenceId() == null && overrides.contains(uid + ":" + anchor)) continue;
                Instant start = Instant.ofEpochMilli(occurrence.getStart().getTime()), end = Instant.ofEpochMilli(occurrence.getEnd().getTime());
                rows.add(Json.obj("id", "calendar:" + TranscriptImporter.hash(uid + ":" + anchor), "title", event.getSummary() == null ? "회의" : event.getSummary().getValue(),
                        "start", start.toString(), "end", end.toString(), "date", start.atZone(KoreaTime.ZONE).toLocalDate().toString(),
                        "product", "미분류", "jiraKey", "", "source", "calendar", "allDay", !(event.getStartDate().getDate() instanceof DateTime), "updatedAt", service.time.instant().toString()));
            }
        }
        service.store.transaction(() -> {
            for (ObjectNode old : service.store.list("meeting")) {
                Instant start = KoreaTime.parse(Json.text(old, "start"));
                if (Json.text(old, "source").equals("calendar") && !start.isBefore(from) && start.isBefore(to)) service.store.delete("meeting", Json.text(old, "id"));
            }
            rows.forEach(row -> service.store.put("meeting", row));
        });
        ObjectNode result = Json.obj("id", "calendar", "at", service.time.instant().toString(), "count", rows.size(), "ok", true);
        service.store.put("sync", result); return result;
    }
}
