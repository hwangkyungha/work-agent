package local.work;

import java.time.*;
import java.util.regex.Pattern;

final class LeaveCalendar {
    private static final Pattern LEAVE = Pattern.compile("휴가|연차|반차");
    static boolean onLeave(WorkService service, Config config, LocalDate date) {
        String name = config.get("CALENDAR_OWNER_NAME", "").replaceAll("\\s+", "");
        if (name.isBlank()) return false;
        return service.meetings(date).stream().map(event -> Json.text(event, "title").replaceAll("\\s+", ""))
                .anyMatch(title -> title.contains(name) && LEAVE.matcher(title).find());
    }
}
