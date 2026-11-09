package local.work;

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
    CalendarSync(Config config, WorkService service) { this.config = config; this.service = service; }
    boolean configured() { return configured(config); }
    static boolean configured(Config config) { return config.has("CALENDAR_ICS_URL") || config.has("CALENDAR_ICS_FILE"); }
    synchronized ObjectNode sync() throws Exception {
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
