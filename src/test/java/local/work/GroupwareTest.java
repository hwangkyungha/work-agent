package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GroupwareTest {
    @TempDir Path temporary;
    Config config;
    KoreaTime time = new KoreaTime(Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC));
    FakeGateway gateway;
    FakeCalendar calendar;
    GroupwareBooking booking;
    ObjectNode room(String id, int capacity) { return Json.obj("short", id, "name", "회의실 " + id, "capacity", capacity, "category_id", "27"); }
    @BeforeEach void open() throws Exception {
        Path file = temporary.resolve("shared/settings.json"); Files.createDirectories(file.getParent());
        Files.writeString(file, Json.write(Json.obj("baseUrl", "https://groupware.example", "cookieFile", "session.enc", "bookingFile", "bookings.jsonl", "rooms", List.of(room("A", 6), room("B", 10), room("C", 20)), "preferredRooms", List.of("A", "B"))));
        config = new Config(temporary.resolve("none"), Map.of("GROUPWARE_CONFIG", file.toString()));
        gateway = new FakeGateway(); calendar = new FakeCalendar();
        booking = new GroupwareBooking(config, time, s -> gateway, s -> calendar);
    }
    ObjectNode request(String operation) { return Json.obj("operation", operation, "date", "2026-11-07", "start", "10:00", "end", "11:00", "attendees", 6, "purpose", "CI '리포트' 검토"); }
    class FakeGateway implements GroupwareBooking.Gateway {
        String allowedDays = "1111111";
        public JsonNode schedule(String category) { return Json.obj("ableDays", allowedDays); }
        List<JsonNode> reservations = new ArrayList<>(); int writes, cancels; boolean uncertain, preflightFailure;
        public List<JsonNode> daily(String category, LocalDate date) { return List.copyOf(reservations); }
        public String reserve(JsonNode room, GroupwareBooking.Request request) {
            if (preflightFailure) throw new IllegalStateException("예약 폼을 찾지 못했습니다.");
            writes++;
            if (uncertain) throw new GroupwareBooking.Unconfirmed(new RuntimeException("connection lost"));
            reservations.add(reservation("r" + writes, Json.text(room, "name"), "2026-11-07T10:00:00+09:00", "2026-11-07T11:00:00+09:00"));
            return "r" + writes;
        }
        public void cancel(JsonNode room, JsonNode reservation) { cancels++; reservations.removeIf(n -> Json.text(n, "id").equals(Json.text(reservation, "id"))); }
    }
    class FakeCalendar implements BookingCalendar {
        int writes, deletes; boolean fail;
        public String create(String id, GroupwareBooking.Request request, String room) { writes++; if (fail) throw new IllegalStateException("calendar unavailable"); return "https://calendar.example/event/" + id; }
        public void delete(String id) { deletes++; if (fail) throw new IllegalStateException("calendar unavailable"); }
    }
    ObjectNode reservation(String id, String name, String start, String end) { return Json.obj("id", id, "itemId", "item", "itemName", name, "startTime", start, "endTime", end); }
    @Test void initializesSettingsFromBundledDefaults() throws Exception {
        Config isolated = new Config(temporary.resolve("none"), Map.of("LOCALAPPDATA", temporary.toString()));
        GroupwareSettings settings = new GroupwareSettings(isolated);
        assertEquals(temporary.resolve("Suresoft/groupware/settings.json"), settings.file);
        assertTrue(Files.isRegularFile(settings.file)); assertEquals(29, settings.rooms().size());
    }
    @Test void readsChangedSharedRoomSettingsOnNextRequest() throws Exception {
        GroupwareSettings settings = new GroupwareSettings(config);
        ObjectNode saved = (ObjectNode) Json.read(Files.readString(settings.file)); saved.set("rooms", Json.MAPPER.valueToTree(List.of(room("D", 5)))); Files.writeString(settings.file, Json.write(saved));
        assertEquals("D", Json.text(new GroupwareSettings(config).rooms().getFirst(), "short"));
    }
    @Test void filtersCapacityAndAllowsAdjacentReservation() throws Exception {
        gateway.reservations.add(reservation("old", "회의실 B", "2026-11-07T09:00:00+09:00", "2026-11-07T10:00:00+09:00"));
        ObjectNode result = booking.execute("check", request("check").put("attendees", 8));
        assertEquals(List.of("B", "C"), Json.MAPPER.convertValue(result.path("rooms"), List.class).stream().map(r -> ((Map<?, ?>) r).get("short")).toList());
        assertEquals(0, gateway.writes); assertEquals(0, calendar.writes);
    }
    @Test void unavailableWeekdayIsExplainedBeforeReservationAndCalendarWrites() throws Exception {
        gateway.allowedDays = "0111110";
        ObjectNode result = booking.execute("weekend", request("book"));
        assertEquals("unavailable", Json.text(result, "status")); assertTrue(Json.text(result, "message").contains("토요일"));
        assertEquals(0, gateway.writes); assertEquals(0, calendar.writes);
        assertEquals("unavailable", Json.text(booking.execute("weekend-check", request("check")), "status"));
    }
    @Test void dayMaskUsesSundayFirstAndDoesNotAssumeAllWeekendsAreBlocked() {
        assertTrue(GroupwareBooking.allowedDay(Json.obj("ableDays", "1000000"), LocalDate.of(2026, 11, 8)));
        assertTrue(GroupwareBooking.allowedDay(Json.obj("ableDays", "0111110"), LocalDate.of(2026, 11, 9)));
        assertThrows(IllegalStateException.class, () -> GroupwareBooking.allowedDay(Json.object(), LocalDate.of(2026, 11, 9)));
    }
    @Test void recognizesUtcAndOvernightConflictsInKorea() {
        var req = GroupwareBooking.Request.parse(request("check"));
        assertTrue(GroupwareBooking.conflicts(reservation("old", "회의실 A", "2026-11-06T14:00:00Z", "2026-11-07T02:00:00Z"), room("A", 6), req));
        assertFalse(GroupwareBooking.conflicts(reservation("old", "회의실 A", "2026-11-07T02:00:00Z", "2026-11-07T03:00:00Z"), room("A", 6), req));
    }
    @Test void reservesPreferredRoomAndAvoidsDuplicateAcrossNewRequestIds() throws Exception {
        ObjectNode first = booking.execute("one", request("book"));
        ObjectNode second = booking.execute("two", request("book"));
        assertEquals("registered", Json.text(first, "calendarStatus")); assertEquals("r1", Json.text(second, "reservationId")); assertEquals(1, gateway.writes);
    }
    @Test void asksForRoomOutsidePreferredListWithoutBooking() throws Exception {
        ObjectNode payload = request("book").put("attendees", 15);
        assertEquals("choose-room", Json.text(booking.execute("one", payload), "status")); assertEquals(0, gateway.writes);
        assertEquals("booked", Json.text(booking.execute("two", payload.put("room", "C")), "status"));
    }
    @Test void specifiedRoomCanBeReservedWithoutAskingForHeadcount() throws Exception {
        ObjectNode payload = request("book").put("room", "B"); payload.remove("attendees");
        assertEquals("booked", Json.text(booking.execute("specified", payload), "status"));
        assertEquals("회의실 B", Json.text(gateway.reservations.getFirst(), "itemName"));
        assertEquals(1, gateway.writes); assertEquals(1, calendar.writes);
    }
    @Test void automaticRoomSelectionStillRequiresHeadcount() {
        ObjectNode payload = request("book"); payload.remove("attendees");
        assertThrows(IllegalArgumentException.class, () -> booking.execute("automatic", payload));
        assertEquals(0, gateway.writes);
    }
    @Test void unavailableSpecifiedRoomDoesNotDescribeOmittedHeadcountAsZeroPeople() throws Exception {
        gateway.reservations.add(reservation("other", "회의실 B", "2026-11-07T10:00:00+09:00", "2026-11-07T11:00:00+09:00"));
        ObjectNode payload = request("check").put("room", "B"); payload.remove("attendees");
        String message = Json.text(booking.execute("occupied", payload), "message");
        assertTrue(message.contains("지정한 회의실")); assertFalse(message.contains("0명")); assertEquals(0, gateway.writes);
    }
    @Test void calendarFailureCanRetryWithoutReservingAnotherRoom() throws Exception {
        calendar.fail = true; assertEquals("pending", Json.text(booking.execute("one", request("book")), "calendarStatus"));
        calendar.fail = false; assertEquals("registered", Json.text(booking.execute("two", request("book")), "calendarStatus"));
        assertEquals(1, gateway.writes); assertEquals(2, calendar.writes);
    }
    @Test void uncertainSubmissionBlocksDuplicateInBothRequestIds() throws Exception {
        gateway.uncertain = true;
        assertThrows(IllegalStateException.class, () -> booking.execute("one", request("book")));
        assertThrows(IllegalStateException.class, () -> booking.execute("two", request("book")));
        assertEquals(1, gateway.writes);
    }
    @Test void failureBeforeSubmittingDoesNotBlockCorrectedRetry() throws Exception {
        gateway.preflightFailure = true; assertThrows(IllegalStateException.class, () -> booking.execute("one", request("book")));
        gateway.preflightFailure = false; assertEquals("booked", Json.text(booking.execute("two", request("book")), "status"));
        assertEquals(1, gateway.writes);
    }
    @Test void cancelsOnlyRecordedReservationAndRetriesOnlyCalendar() throws Exception {
        booking.execute("one", request("book")); calendar.fail = true;
        assertEquals("pending", Json.text(booking.execute("two", request("cancel")), "calendarStatus"));
        calendar.fail = false; assertEquals("deleted", Json.text(booking.execute("three", request("cancel")), "calendarStatus"));
        assertEquals(1, gateway.cancels);
        assertEquals("booked", Json.text(booking.execute("four", request("book")), "status")); assertEquals(2, gateway.writes);
    }
    @Test void rejectsInvalidTimeAndCapacityBeforeContactingGroupware() {
        assertThrows(IllegalArgumentException.class, () -> booking.execute("one", request("book").put("end", "09:00")));
        assertThrows(IllegalArgumentException.class, () -> booking.execute("one", request("book").put("start", "10:15")));
        assertThrows(IllegalArgumentException.class, () -> booking.execute("one", request("book").put("attendees", 0)));
        assertThrows(IllegalArgumentException.class, () -> booking.execute("one", request("book").put("date", "2026-11-05")));
        assertEquals(0, gateway.writes);
    }
    @Test void locksSameSharedBookingAcrossInstances() throws Exception {
        GroupwareSettings settings = new GroupwareSettings(config);
        try (var lock = settings.lock()) { assertThrows(IllegalStateException.class, () -> new GroupwareSettings(config).lock()); }
        try (var lock = settings.lock()) { assertNotNull(lock); }
    }
    @Test void calendarEventUsesKoreaTimeAndDeterministicValidId() {
        String id = GoogleBookingCalendar.eventId("https://groupware.example", "r1");
        assertTrue(id.matches("[0-9a-v]{5,1024}")); assertEquals(id, GoogleBookingCalendar.eventId("https://groupware.example", "r1"));
        ObjectNode event = GoogleBookingCalendar.event(id, GroupwareBooking.Request.parse(request("book")), "A");
        assertEquals("2026-11-07T10:00:00+09:00", Json.text(event.path("start"), "dateTime")); assertEquals("Asia/Seoul", Json.text(event.path("start"), "timeZone"));
        assertEquals("CI '리포트' 검토", Json.text(event, "summary"));
    }
    @Test void savedSessionUsesPythonPlaywrightCookieFormat() {
        var cookie = new com.microsoft.playwright.options.Cookie("session", "test-value").setDomain("example.test").setPath("/").setExpires(-1).setSameSite(com.microsoft.playwright.options.SameSiteAttribute.LAX);
        JsonNode saved = GroupwareBrowser.savedCookies(List.of(cookie));
        assertEquals("Lax", Json.text(saved.get(0), "sameSite")); assertFalse(saved.get(0).has("url"));
        assertEquals("test-value", GroupwareBrowser.cookies(saved).getFirst().value);
    }
    @Test void agentCallsBookingAndCachesReplayedSlackEvent() throws Exception {
        try (Store store = new Store(temporary.resolve("test.db"))) {
            WorkService service = new WorkService(store, time, new JiraClient(config));
            Agent agent = new Agent(service, new Reports(service), prompt -> {
                assertTrue(prompt.contains("room_booking")); return Json.obj("reply", "", "actions", List.of(Json.obj("type", "room_booking", "payload", Json.write(request("book")))));
            }, kind -> Json.object(), "호박", "호박아", booking);
            assertTrue(Json.text(agent.chat("slack", "event", "호박아, 회의실 예약해 줘"), "text").contains("회의실을 예약했습니다"));
            agent.chat("slack", "event", "호박아, 회의실 예약해 줘"); assertEquals(1, gateway.writes); assertEquals(1, calendar.writes);
        }
    }
}
