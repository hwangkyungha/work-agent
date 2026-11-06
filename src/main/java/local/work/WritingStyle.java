package local.work;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Supplies bundled writing guidance to model calls without depending on personal CLI settings. */
final class WritingStyle {
    private static final String GUIDE = load();
    static String prompt(String request) { return GUIDE + "\n\n" + request; }
    private static String load() {
        try (InputStream input = WritingStyle.class.getResourceAsStream("/writing-guidelines.md")) {
            return new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
