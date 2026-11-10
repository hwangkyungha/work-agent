package local.work;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.Locale;
import java.util.concurrent.*;

final class Scheduler implements AutoCloseable {
    private static final int SYNC_INTERVAL_MINUTES = 5;
    private static final int BRIEFING_REFRESH_MINUTES = 5;
    @FunctionalInterface interface SessionStart { void run(String engine) throws Exception; }
    @FunctionalInterface interface Notifier { void send(String text) throws Exception; }
    @FunctionalInterface interface DraftBriefing { String prepare(LocalDate date) throws Exception; }
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final WorkService service;
    private final Reports reports;
    private final SlackConnection slack;
    private final Config config;
    private final Agent.Synchronizer synchronizer;
    private final SessionStart sessionStart;
    private final Notifier notifier;
    private final DraftBriefing draftBriefing;
    Scheduler(WorkService service, Reports reports, SlackConnection slack, Config config, Agent.Synchronizer synchronizer) {
        this(service, reports, slack, config, synchronizer, engine -> new CliModel(config).startSession(engine));
    }
    Scheduler(WorkService service, Reports reports, SlackConnection slack, Config config, Agent.Synchronizer synchronizer, SessionStart sessionStart) {
        this(service, reports, slack, config, synchronizer, sessionStart, slack::notifyOwner);
    }
    Scheduler(WorkService service, Reports reports, SlackConnection slack, Config config, Agent.Synchronizer synchronizer, SessionStart sessionStart, Notifier notifier) {
        this(service,reports,slack,config,synchronizer,sessionStart,notifier,null);
    }
    Scheduler(WorkService service, Reports reports, SlackConnection slack, Config config, Agent.Synchronizer synchronizer, SessionStart sessionStart, Notifier notifier,DraftBriefing draftBriefing) {
        this.service = service; this.reports = reports; this.slack = slack; this.config = config; this.synchronizer = synchronizer;
        this.sessionStart = sessionStart;
        this.notifier = notifier;
        this.draftBriefing=draftBriefing;
    }
    void start() {
        executor.scheduleWithFixedDelay(() -> { try { tick(); } catch (Exception e) { System.err.println("예약 작업을 완료하지 못했습니다: " + SlackConnection.friendly(e)); } }, 5, 30, TimeUnit.SECONDS);
    }
    void tick() throws Exception {
        ZonedDateTime now = service.time.now();
        startSessionIfDue(now, "claude", "Claude");
        startSessionIfDue(now, "codex", "Codex");
        if(slack.configured() && draftBriefing!=null) {
            String key="draft-results:"+now.toLocalDate(),hour=config.get("DRAFT_REVIEW_TIME","08:00");
            if(due(key,hour,now)) {
                sync("cli");
                if(service.jira.configured())sync("jira");
                String message=draftBriefing.prepare(now.toLocalDate());
                if(message!=null) {notifier.send(reports.preparation(now.toLocalDate())+message);service.store.put("scheduled",Json.obj("id",key,"at",now.toInstant().toString()));}
            }
        }
        boolean weekday = now.getDayOfWeek().getValue() <= 5;
        boolean automatic = config.get("AUTO_SYNC", "true").equals("true");
        if (automatic) syncIfDue("cli", SYNC_INTERVAL_MINUTES, now.toInstant());
        if (weekday && slack.configured()) sendMorningBriefing(now);
        if (automatic) {
            if (service.jira.configured()) syncIfDue("jira", SYNC_INTERVAL_MINUTES, now.toInstant());
            if (CalendarSync.configured(config)) syncIfDue("calendar", SYNC_INTERVAL_MINUTES, now.toInstant());
        }
        if (!weekday) return;
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
    private void syncIfDue(String kind, int minutes, Instant now) {
        ObjectNode last = service.store.get("sync", kind);
        if (last == null || Duration.between(KoreaTime.parse(Json.required(last, "at")), now).toMinutes() >= minutes) sync(kind);
    }
    private boolean sync(String kind) {
        try {
            synchronizer.sync(kind);
            return true;
        } catch (Exception e) {
            System.err.println("예약 동기화 실패(" + kind + "): " + SlackConnection.friendly(e));
            return false;
        }
    }
    private void sendMorningBriefing(ZonedDateTime now) throws Exception {
        String hour = config.get("BRIEFING_TIME", "09:00");
        String key = "morning:" + now.toLocalDate();
        if (!due(key, hour, now)) return;
        Instant scheduled = now.toLocalDate().atTime(LocalTime.parse(hour)).atZone(now.getZone()).toInstant();
        if (CalendarSync.configured(config)) {
            ObjectNode calendar = service.store.get("sync", "calendar");
            if (calendar != null && calendar.path("ok").asBoolean() && !KoreaTime.parse(Json.required(calendar, "at")).isBefore(scheduled)
                    && LeaveCalendar.onLeave(service, config, now.toLocalDate())) return;
            if (!refreshForBriefing("calendar", scheduled, now.toInstant())) return;
        }
        if (LeaveCalendar.onLeave(service, config, now.toLocalDate())) return;
        if (service.jira.configured() && !refreshForBriefing("jira", scheduled, now.toInstant())) return;
        if (!refreshForBriefing("cli", scheduled, now.toInstant())) return;
        sendOnce(key, hour, now, () -> reports.briefing(now.toLocalDate()));
    }
    private boolean refreshForBriefing(String kind, Instant scheduled, Instant now) {
        ObjectNode last = service.store.get("sync", kind);
        if (last != null) {
            Instant at = KoreaTime.parse(Json.required(last, "at"));
            if (!at.isBefore(scheduled) && Duration.between(at, now).toMinutes() < BRIEFING_REFRESH_MINUTES) return last.path("ok").asBoolean();
        }
        return sync(kind);
    }
    private void startSessionIfDue(ZonedDateTime now, String engine, String label) {
        String hour = config.get(engine.toUpperCase(Locale.ROOT) + "_SESSION_TIME", "");
        if (hour.isBlank()) return;
        LocalTime target = LocalTime.parse(hour);
        String id = engine + "-session:" + now.toLocalDate();
        if (now.toLocalTime().isBefore(target) || Duration.between(target, now.toLocalTime()).toMinutes() >= 60 || service.store.get("scheduled", id) != null) return;
        ObjectNode row = Json.obj("id", id, "at", now.toInstant().toString(), "status", "running");
        service.store.put("scheduled", row);
        String message;
        try { sessionStart.run(engine); row.put("status", "complete"); message = label + " 예약 요청을 완료했습니다. (한국 시간 " + hour + ")"; }
        catch (Exception e) { row.put("status", "failed"); message = label + " 예약 요청에 실패했습니다. 로그인 상태와 사용 한도를 확인하세요."; }
        service.store.put("scheduled", row);
        try { notifier.send(message); row.put("notified", true); }
        catch (Exception e) { row.put("notified", false); }
        service.store.put("scheduled", row);
    }
    private void sendOnce(String key, String hour, ZonedDateTime now, java.util.function.Supplier<String> text) throws Exception {
        if (!due(key, hour, now)) return;
        notifier.send(text.get());
        service.store.put("scheduled", Json.obj("id", key, "at", now.toInstant().toString()));
    }
    private boolean due(String key, String hour, ZonedDateTime now) {
        if (hour.isBlank() || service.store.get("scheduled", key) != null) return false;
        LocalTime target = LocalTime.parse(hour);
        return !now.toLocalTime().isBefore(target) && Duration.between(target, now.toLocalTime()).toMinutes() < 60;
    }
    @Override public void close() { executor.shutdownNow(); }
}
