package local.work;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;

final class KoreaTime {
    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private final Clock clock;
    KoreaTime(Clock clock) { this.clock = clock; }
    Instant instant() { return clock.instant(); }
    ZonedDateTime now() { return instant().atZone(ZONE); }
    LocalDate today() { return now().toLocalDate(); }
    LocalDate monday(LocalDate day) { return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); }
    static LocalDate dateOf(String timestamp) { return parse(timestamp).atZone(ZONE).toLocalDate(); }
    static Instant parse(String timestamp) {
        try { return Instant.parse(timestamp); }
        catch (RuntimeException ignored) {
            try { return OffsetDateTime.parse(timestamp).toInstant(); }
            catch (RuntimeException e) { return LocalDateTime.parse(timestamp).atZone(ZONE).toInstant(); }
        }
    }
    static String jiraStarted(LocalDate date) {
        return date.atTime(9, 0).atZone(ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"));
    }
}
