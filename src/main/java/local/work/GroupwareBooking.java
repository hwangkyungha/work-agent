package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import java.util.function.Function;

final class GroupwareBooking {
    interface Gateway extends AutoCloseable {
        JsonNode schedule(String category) throws Exception;
        List<JsonNode> daily(String category, LocalDate date) throws Exception;
        String reserve(JsonNode room, Request request) throws Exception;
        void cancel(JsonNode room, JsonNode reservation) throws Exception;
        default void close() throws Exception { }
    }
    @FunctionalInterface interface Connection { Gateway open(GroupwareSettings settings) throws Exception; }
    static final class Unconfirmed extends RuntimeException {
        Unconfirmed(Throwable cause) { super("예약 결과를 확인하지 못했습니다.", cause); }
    }
    record Request(LocalDate date, LocalTime start, LocalTime end, int attendees, String purpose, String room) {
        static Request parse(JsonNode p) {
            LocalDate date = LocalDate.parse(Json.required(p, "date"));
            LocalTime start = LocalTime.parse(Json.required(p, "start"));
            LocalTime end = LocalTime.parse(Json.required(p, "end"));
            int count = p.path("attendees").asInt();
            if (!end.isAfter(start)) throw new IllegalArgumentException("종료 시각은 시작 시각보다 늦어야 합니다. 하루 안의 시간을 지정하세요.");
            if (start.getSecond() != 0 || end.getSecond() != 0 || start.getNano() != 0 || end.getNano() != 0 || start.getMinute() % 30 != 0 || end.getMinute() % 30 != 0) throw new IllegalArgumentException("예약 시간은 30분 단위로 지정하세요.");
            if (count < 1 && (Json.text(p, "room").isBlank() || p.hasNonNull("attendees"))) throw new IllegalArgumentException("회의실을 자동으로 선택하려면 참석 인원을 입력하세요.");
            return new Request(date, start, end, count, Json.text(p, "purpose"), Json.text(p, "room"));
        }
    }
    private final Config config;
    private final KoreaTime time;
    private final Connection connection;
    private final Function<GroupwareSettings, BookingCalendar> calendar;
    GroupwareBooking(Config config, KoreaTime time) { this(config, time, GroupwareBrowser::new); }
    GroupwareBooking(Config config, KoreaTime time, Connection connection) { this(config, time, connection, GoogleBookingCalendar::new); }
    GroupwareBooking(Config config, KoreaTime time, Connection connection, Function<GroupwareSettings, BookingCalendar> calendar) { this.config = config; this.time = time; this.connection = connection; this.calendar = calendar; }

    synchronized ObjectNode execute(String requestId, JsonNode payload) throws Exception {
        Request request = Request.parse(payload);
        if (request.date().isBefore(time.today()) || request.date().equals(time.today()) && !request.start().isAfter(time.now().toLocalTime())) throw new IllegalArgumentException("앞으로 사용할 예약 시간을 지정하세요. 모든 시간은 한국 시간입니다.");
        String operation = Json.required(payload, "operation");
        if (!Set.of("check", "book", "cancel").contains(operation)) throw new IllegalArgumentException("회의실 작업은 check, book, cancel 중 하나여야 합니다.");
        if (!operation.equals("check") && request.purpose().isBlank()) throw new IllegalArgumentException("회의 목적을 입력하세요.");
        GroupwareSettings settings = new GroupwareSettings(config);
        try (var lock = settings.lock(); var gateway = connection.open(settings)) {
            List<JsonNode> rooms = settings.rooms();
            if (!request.room().isBlank()) {
                rooms = rooms.stream().filter(r -> request.room().equals(Json.text(r, "short"))).toList();
                if (rooms.isEmpty()) throw new IllegalArgumentException("설정에 없는 회의실입니다: " + request.room());
            }
            if (!operation.equals("cancel")) {
                List<JsonNode> permitted = new ArrayList<>();
                Map<String, JsonNode> schedules = new HashMap<>();
                for (JsonNode room : rooms) {
                    String category = Json.required(room, "category_id");
                    if (!schedules.containsKey(category)) schedules.put(category, gateway.schedule(category));
                    if (allowedDay(schedules.get(category), request.date())) permitted.add(room);
                }
                rooms = permitted;
                if (rooms.isEmpty()) return Json.obj("status", "unavailable", "rooms", List.of(), "message", request.date() + "(" + request.date().getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.KOREAN) + ")은 그룹웨어에서 예약할 수 없는 요일입니다. 다른 날짜로 요청해 주세요.");
            }
            Map<String, List<JsonNode>> daily = new HashMap<>();
            for (JsonNode room : rooms) {
                String category = Json.required(room, "category_id");
                if (!daily.containsKey(category)) daily.put(category, gateway.daily(category, request.date()));
            }
            if (operation.equals("cancel")) return cancel(settings, gateway, request, rooms, daily);
            for (JsonNode old : settings.history()) {
                if (Set.of("cancelled", "failed").contains(Json.text(old, "state"))) continue;
                if (!Json.text(old, "date").equals(request.date().toString()) || !Json.text(old, "start").equals(request.start().toString())) continue;
                if (!Json.text(old, "requestId").equals(requestId) && !(Json.text(old, "end").equals(request.end().toString()) && Json.text(old, "purpose").equals(request.purpose()))) continue;
                if (!request.room().isBlank() && !request.room().equals(Json.text(old, "room_short"))) continue;
                if (operation.equals("book")) {
                    if (Json.text(old, "state").equals("pending")) throw new IllegalStateException("앞선 예약의 결과를 확인해야 합니다. 그룹웨어 예약 내역을 확인한 뒤 다시 요청하세요. 중복 예약을 만들지 않았습니다.");
                    JsonNode existing = daily.getOrDefault(Json.text(old, "category_id"), List.of()).stream().filter(n -> Json.text(n, "id").equals(Json.text(old, "reservationId"))).findFirst().orElse(null);
                    if (existing != null) return booked(settings, request, Json.text(old, "room_short"), Json.text(existing, "id"), true);
                }
            }
            List<JsonNode> available = rooms.stream().filter(r -> r.path("capacity").asInt() >= request.attendees())
                    .filter(r -> daily.get(Json.text(r, "category_id")).stream().noneMatch(n -> conflicts(n, r, request)))
                    .sorted(Comparator.comparingInt(r -> { int i = settings.preferred().indexOf(Json.text(r, "short")); return i < 0 ? Integer.MAX_VALUE : i; })).toList();
            if (operation.equals("check") || available.isEmpty()) return Json.obj("status", "available", "rooms", available, "message", available.isEmpty() ? (request.attendees() == 0 ? "해당 시간에 지정한 회의실을 사용할 수 없습니다." : "해당 시간에 " + request.attendees() + "명이 사용할 수 있는 회의실이 없습니다.") : "사용 가능한 회의실 (한국 시간 " + request.date() + " " + request.start() + "~" + request.end() + "): " + String.join(", ", available.stream().map(r -> Json.text(r, "short") + " (" + r.path("capacity").asInt() + "명)").toList()));
            JsonNode room = available.getFirst();
            if (request.room().isBlank() && !settings.preferred().contains(Json.text(room, "short"))) return Json.obj("status", "choose-room", "rooms", available, "message", "우선 사용 회의실이 비어 있지 않습니다. 다음 중 예약할 회의실을 골라 주세요: " + String.join(", ", available.stream().map(r -> Json.text(r, "short")).toList()));
            if (gateway.daily(Json.text(room, "category_id"), request.date()).stream().anyMatch(n -> conflicts(n, room, request))) throw new IllegalStateException("방금 다른 예약이 생겼습니다. 빈 회의실을 다시 조회하세요.");
            ObjectNode entry = Json.obj("requestId", requestId, "timestamp", time.now().toString(), "date", request.date().toString(), "start", request.start().toString(), "end", request.end().toString(), "purpose", request.purpose(), "room_short", Json.text(room, "short"), "room_name", Json.text(room, "name"), "category_id", Json.text(room, "category_id"), "state", "pending");
            settings.record(entry);
            String reservationId;
            try { reservationId = gateway.reserve(room, request); }
            catch (Unconfirmed e) { throw new IllegalStateException("예약 결과를 확인하지 못했습니다. 그룹웨어 예약 내역을 먼저 확인하세요. 같은 요청으로 다른 회의실을 추가 예약하지 않습니다.", e); }
            catch (Exception e) { entry.put("state", "failed"); settings.record(entry); throw e; }
            if (reservationId.isBlank()) throw new IllegalStateException("예약 번호를 확인하지 못했습니다. 그룹웨어 예약 내역을 확인하세요.");
            entry.put("state", "booked"); entry.put("reservationId", reservationId); settings.record(entry);
            return booked(settings, request, Json.text(room, "short"), reservationId, false);
        }
    }
    private ObjectNode cancel(GroupwareSettings settings, Gateway gateway, Request request, List<JsonNode> rooms, Map<String, List<JsonNode>> daily) throws Exception {
        for (JsonNode old : settings.history()) {
            if (!Json.text(old, "date").equals(request.date().toString()) || !Json.text(old, "start").equals(request.start().toString()) || !Json.text(old, "end").equals(request.end().toString()) || !Json.text(old, "purpose").equals(request.purpose())) continue;
            if (!request.room().isBlank() && !request.room().equals(Json.text(old, "room_short"))) continue;
            if (!Set.of("booked", "cancelled").contains(Json.text(old, "state"))) continue;
            JsonNode room = rooms.stream().filter(r -> Json.text(r, "short").equals(Json.text(old, "room_short"))).findFirst().orElse(null);
            if (room == null) continue;
            JsonNode reservation = daily.get(Json.text(room, "category_id")).stream().filter(n -> Json.text(n, "id").equals(Json.text(old, "reservationId"))).findFirst().orElse(null);
            if (reservation != null) gateway.cancel(room, reservation);
            ObjectNode entry = ((ObjectNode) old).deepCopy(); entry.put("state", "cancelled"); settings.record(entry);
            String message = "회의실 예약을 취소했습니다: " + describe(request, Json.text(room, "short"));
            try { calendar.apply(settings).delete(Json.required(old, "reservationId")); message += ". 구글 캘린더 일정도 삭제했습니다."; }
            catch (Exception e) { return Json.obj("status", "cancelled", "calendarStatus", "pending", "message", message + ". 캘린더 삭제는 대기 중입니다: " + SlackConnection.friendly(e)); }
            return Json.obj("status", "cancelled", "calendarStatus", "deleted", "message", message);
        }
        throw new IllegalArgumentException("work-agent로 예약한 내역을 찾지 못했습니다. 날짜, 시간, 회의 목적과 회의실을 확인하세요.");
    }
    private ObjectNode booked(GroupwareSettings settings, Request request, String room, String reservationId, boolean existing) {
        String message = (existing ? "이미 예약했습니다: " : "회의실을 예약했습니다: ") + describe(request, room);
        try {
            String url = calendar.apply(settings).create(reservationId, request, room);
            return Json.obj("status", "booked", "reservationId", reservationId, "calendarStatus", "registered", "calendarUrl", url, "message", message + ". 구글 캘린더에도 등록했습니다.");
        } catch (Exception e) {
            return Json.obj("status", "booked", "reservationId", reservationId, "calendarStatus", "pending", "message", message + ". 캘린더 등록은 대기 중입니다: " + SlackConnection.friendly(e) + " 같은 예약을 다시 요청하면 회의실을 추가 예약하지 않고 캘린더 등록을 다시 시도합니다.");
        }
    }
    static boolean conflicts(JsonNode booking, JsonNode room, Request request) {
        if (!Json.text(booking, "itemName").equals(Json.text(room, "name"))) return false;
        String start = Json.text(booking, "startTime"), end = Json.text(booking, "endTime");
        return !start.isBlank() && !end.isBlank() && KoreaTime.parse(start).isBefore(request.date().atTime(request.end()).atZone(KoreaTime.ZONE).toInstant()) && KoreaTime.parse(end).isAfter(request.date().atTime(request.start()).atZone(KoreaTime.ZONE).toInstant());
    }
    static boolean allowedDay(JsonNode schedule, LocalDate date) {
        String days = Json.text(schedule, "ableDays");
        if (!days.matches("[01]{7}")) throw new IllegalStateException("그룹웨어의 예약 가능 요일을 확인하지 못했습니다. 예약을 실행하지 않았습니다.");
        return days.charAt(date.getDayOfWeek().getValue() % 7) == '1';
    }
    private static String describe(Request request, String room) { return request.date() + " " + request.start() + "~" + request.end() + " (한국 시간), " + room + ", " + request.purpose(); }
}
