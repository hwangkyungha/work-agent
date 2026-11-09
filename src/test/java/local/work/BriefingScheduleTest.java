package local.work;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BriefingScheduleTest {
    @TempDir Path temporary;
    Store store;
    List<String> sent = new ArrayList<>();
    @BeforeEach void open() throws Exception { store = new Store(temporary.resolve("test.db")); }
    @AfterEach void close() throws Exception { store.close(); }
    WorkService service(String now) throws Exception {
        return new WorkService(store, new KoreaTime(Clock.fixed(Instant.parse(now), ZoneOffset.UTC)), new JiraClient(new Config(temporary.resolve("none"), Map.of())));
    }
    void tick(String now) throws Exception {
        Config config = new Config(temporary.resolve("none"), Map.of("AUTO_SYNC", "false", "CALENDAR_OWNER_NAME", "홍길동", "SLACK_APP_TOKEN", "test", "SLACK_BOT_TOKEN", "test", "SLACK_USER_ID", "test", "FRIDAY_REVIEW_TIME", ""));
        tick(now, config, k -> Json.object(), sent::add);
    }
    private void tick(String now, Config config, Agent.Synchronizer sync, Scheduler.Notifier notifier) throws Exception {
        WorkService service = new WorkService(store, new KoreaTime(Clock.fixed(Instant.parse(now), ZoneOffset.UTC)), new JiraClient(config));
        Agent agent = new Agent(service, new Reports(service), p -> Json.object(), k -> Json.object(), "테스트", "테스트야");
        try (SlackConnection slack = new SlackConnection(config, store, agent); Scheduler scheduler = new Scheduler(service, new Reports(service), slack, config, sync, engine -> {}, notifier)) { scheduler.tick(); }
    }
    private Config connected(boolean automatic) throws Exception {
        return new Config(temporary.resolve("none"), Map.of("AUTO_SYNC", Boolean.toString(automatic), "CALENDAR_OWNER_NAME", "홍길동",
                "CALENDAR_ICS_FILE", "test.ics", "JIRA_URL", "https://jira.example", "JIRA_TOKEN", "test",
                "SLACK_APP_TOKEN", "test", "SLACK_BOT_TOKEN", "test", "SLACK_USER_ID", "test"));
    }
    private void synced(String kind, String at, boolean ok) {
        store.put("sync", Json.obj("id", kind, "at", at, "ok", ok));
    }
    @Test void allSourcesSyncEveryFiveMinutes() throws Exception {
        Config config = connected(true);
        List<String> calls = new ArrayList<>();
        for (String now : List.of("2026-11-09T04:00:00Z", "2026-11-09T04:04:59Z", "2026-11-09T04:05:00Z",
                "2026-11-09T04:09:59Z", "2026-11-09T04:10:00Z")) {
            tick(now, config, kind -> {
                calls.add(kind);
                synced(kind, now, true);
                return Json.object();
            }, sent::add);
        }
        assertEquals(List.of("cli", "jira", "calendar", "cli", "jira", "calendar", "cli", "jira", "calendar"), calls);
        assertTrue(sent.isEmpty());
    }
    @Test void morningRefreshPrecedesDeliveryEvenWhenPeriodicSyncIsOff() throws Exception {
        Config config = connected(false);
        List<String> calls = new ArrayList<>();
        synced("jira", "2026-11-08T23:59:00Z", true);
        synced("calendar", "2026-11-08T23:59:00Z", true);
        synced("cli", "2026-11-08T23:59:00Z", true);
        String now = "2026-11-09T00:00:00Z";
        tick(now, config, kind -> { calls.add(kind); synced(kind, now, true); return Json.object(); }, message -> calls.add("briefing"));
        assertEquals(List.of("calendar", "jira", "cli", "briefing"), calls);
    }
    @Test void freshLeaveStopsBriefingAndAvoidsExtraCalendarDownloads() throws Exception {
        Config config = connected(false);
        List<String> calls = new ArrayList<>();
        for (String now : List.of("2026-11-09T00:00:00Z", "2026-11-09T00:10:00Z")) {
            tick(now, config, kind -> {
                calls.add(kind);
                synced(kind, now, true);
                store.put("meeting", Json.obj("id", "leave", "title", "홍길동 휴가", "start", "2026-11-09T00:00:00+09:00", "end", "2026-11-10T00:00:00+09:00"));
                return Json.object();
            }, sent::add);
        }
        assertEquals(List.of("calendar"), calls);
        assertTrue(sent.isEmpty());
    }
    @Test void failedMorningRefreshWaitsBeforeRetryingAndSending() throws Exception {
        Config config = connected(false);
        List<String> calls = new ArrayList<>();
        for (String now : List.of("2026-11-09T00:00:00Z", "2026-11-09T00:01:00Z", "2026-11-09T00:05:00Z")) {
            tick(now, config, kind -> {
                calls.add(kind);
                boolean ok = !now.equals("2026-11-09T00:00:00Z");
                synced(kind, now, ok);
                if (!ok) throw new IOException("offline");
                return Json.object();
            }, message -> calls.add("briefing"));
        }
        assertEquals(List.of("calendar", "calendar", "jira", "cli", "briefing"), calls);
    }
    @Test void sendsOnceAtNineInKoreaAndNeverOnWeekendsOrLateStartup() throws Exception {
        tick("2026-11-05T23:59:59Z"); assertTrue(sent.isEmpty());
        tick("2026-11-06T00:00:00Z"); tick("2026-11-06T00:01:00Z"); assertEquals(1, sent.size());
        tick("2026-11-07T00:00:00Z"); tick("2026-11-08T00:00:00Z"); tick("2026-11-09T01:00:00Z"); assertEquals(1, sent.size());
    }
    @Test void skipsOwnLeaveAndBothHalfDaysButNotAnotherPersonsLeave() throws Exception {
        for (String type : List.of("휴가", "연차", "오전 반차", "오후 반차")) {
            store.put("meeting", Json.obj("id", "leave", "title", "홍길동 " + type, "start", "2026-11-06T00:00:00+09:00", "end", "2026-11-07T00:00:00+09:00", "allDay", true));
            tick("2026-11-06T00:00:00Z"); assertTrue(sent.isEmpty(), type);
        }
        store.put("meeting", Json.obj("id", "leave", "title", "김다른 휴가", "start", "2026-11-06T00:00:00+09:00", "end", "2026-11-07T00:00:00+09:00"));
        tick("2026-11-06T00:00:00Z"); assertEquals(1, sent.size());
    }
    @Test void mondayUsesFridayEvenIfWeekendHasSessionsAndHolidaysFallBackToLatestWork() throws Exception {
        WorkService service = service("2026-11-09T00:00:00Z");
        service.activity(Json.obj("title", "금요일 진행 기록", "date", "2026-11-06"));
        service.activity(Json.obj("title", "토요일 개인 기록", "date", "2026-11-07"));
        String monday = new Reports(service).briefing(LocalDate.of(2026, 11, 9));
        assertTrue(monday.contains("2026-11-06 금요일")); assertFalse(monday.contains("금요일 진행 기록")); assertFalse(monday.contains("토요일 개인 기록"));
        service.activity(Json.obj("title", "수요일 마지막 진행 기록", "date", "2026-11-04"));
        store.list("activity").stream().filter(a -> Json.text(a, "date").equals("2026-11-06")).forEach(a -> store.delete("activity", Json.text(a, "id")));
        String afterLeave = new Reports(service).briefing(LocalDate.of(2026, 11, 9));
        assertTrue(afterLeave.contains("2026-11-04 수요일")); assertFalse(afterLeave.contains("수요일 마지막 진행 기록"));
    }
    @Test void failedSlackDeliveryIsRetriedWithoutMarkingDayComplete() throws Exception {
        Config config = new Config(temporary.resolve("none"), Map.of("AUTO_SYNC", "false", "SLACK_APP_TOKEN", "test", "SLACK_BOT_TOKEN", "test", "SLACK_USER_ID", "test", "FRIDAY_REVIEW_TIME", ""));
        WorkService service = service("2026-11-06T00:00:00Z");
        Agent agent = new Agent(service, new Reports(service), p -> Json.object(), k -> Json.object(), "테스트", "테스트야");
        try (SlackConnection slack = new SlackConnection(config, store, agent); Scheduler scheduler = new Scheduler(service, new Reports(service), slack, config, k -> Json.object(), engine -> {}, message -> { throw new IOException("offline"); })) {
            assertThrows(IOException.class, scheduler::tick); assertNull(store.get("scheduled", "morning:2026-11-06"));
        }
        tick("2026-11-06T00:01:00Z"); assertEquals(1, sent.size());
    }
}
