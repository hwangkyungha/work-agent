package local.work;

import java.math.*;
import java.util.regex.*;

final class WorkDuration {
    private static final Pattern FORMAT = Pattern.compile("(?i)^\\s*(?:(\\d+(?:\\.\\d+)?)\\s*h)?\\s*(?:(\\d+(?:\\.\\d+)?)\\s*m)?\\s*(?:(\\d+(?:\\.\\d+)?)\\s*s)?\\s*$");
    static int seconds(String value) {
        Matcher match = FORMAT.matcher(value);
        if (!match.matches() || (match.group(1) == null && match.group(2) == null && match.group(3) == null)) throw invalid();
        try {
            BigDecimal hours = match.group(1) == null ? BigDecimal.ZERO : new BigDecimal(match.group(1));
            BigDecimal minutes = match.group(2) == null ? BigDecimal.ZERO : new BigDecimal(match.group(2));
            BigDecimal remainder = match.group(3) == null ? BigDecimal.ZERO : new BigDecimal(match.group(3));
            int seconds = hours.multiply(BigDecimal.valueOf(3600)).add(minutes.multiply(BigDecimal.valueOf(60))).add(remainder).intValueExact();
            if (seconds <= 0) throw invalid();
            return seconds;
        } catch (ArithmeticException e) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("시간을 3h, 2.5h 또는 2h 30m 형식으로 입력하세요. 0보다 큰 초 단위 시간이어야 합니다."); }
}
