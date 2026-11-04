package local.work;

import java.time.*;
import java.util.concurrent.*;

final class Scheduler implements AutoCloseable {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final WorkService service;
    private final Reports reports;
    private final SlackConnection slack;
    private final Config config;
    private final Agent.Synchronizer synchronizer;
    Scheduler(WorkService service, Reports reports, SlackConnection slack, Config config, Agent.Synchronizer synchronizer) {
        this.service = service; this.reports = reports; this.slack = slack; this.config = config; this.synchronizer = synchronizer;
    }
    void start() {
        executor.scheduleWithFixedDelay(() -> { try { tick(); } catch (Exception e) { System.err.println("예약 작업을 완료하지 못했습니다: " + SlackConnection.friendly(e)); } }, 5, 30, TimeUnit.SECONDS);
    }
    void tick() throws Exception {
        ZonedDateTime now = service.time.now();
        boolean weekday = now.getDayOfWeek().getValue() <= 5;
        if (config.get("AUTO_SYNC", "true").equals("true")) {
            var last = service.store.get("sync", "automatic");
            if (last == null || Duration.between(KoreaTime.parse(Json.text(last, "at")), now.toInstant()).toMinutes() >= 5) {
                synchronizer.sync("all");
                service.store.put("sync", Json.obj("id", "automatic", "at", now.toInstant().toString(), "ok", true));
            }
        }
        if (!weekday) return;
        if (slack.configured()) sendOnce("morning:" + now.toLocalDate(), config.get("BRIEFING_TIME", "09:00"), now, () -> reports.briefing(now.toLocalDate()));
        if (now.getDayOfWeek() == DayOfWeek.FRIDAY) {
            if (slack.configured()) sendOnce("friday:" + now.toLocalDate(), config.get("FRIDAY_REVIEW_TIME", "16:30"), now,
                    () -> reports.worklog(service.time.monday(now.toLocalDate()), now.toLocalDate()));
            String key = "team:" + service.time.monday(now.toLocalDate());
            if (service.jira.configured() && service.store.get("scheduled", key) == null && now.toLocalTime().isAfter(LocalTime.of(16, 0))) {
                synchronizer.sync("team");
                service.store.put("scheduled", Json.obj("id", key, "at", now.toInstant().toString()));
            }
        }
    }
    private void sendOnce(String key, String hour, ZonedDateTime now, java.util.function.Supplier<String> text) throws Exception {
        if (hour.isBlank() || now.toLocalTime().isBefore(LocalTime.parse(hour)) || service.store.get("scheduled", key) != null) return;
        // Do not deliver an old morning notification when the app is first started late in the day.
        if (Duration.between(LocalTime.parse(hour), now.toLocalTime()).toMinutes() >= 60) return;
        slack.notifyOwner(text.get());
        service.store.put("scheduled", Json.obj("id", key, "at", now.toInstant().toString()));
    }
    @Override public void close() { executor.shutdownNow(); }
}
