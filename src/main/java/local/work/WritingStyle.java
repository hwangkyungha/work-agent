package local.work;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/** Supplies bundled writing guidance to model calls without depending on personal CLI settings. */
final class WritingStyle {
    private static final String GUIDE = load("work-agent-writing/common.md");
    static String prompt(String request) { return GUIDE + "\n\n" + request; }
    static String message(String request) { return prompt(reviewRules() + "\n\n" + request); }
    static String messageRules() { return load("write-message/SKILL.md"); }
    static String reviewRules() { return messageRules() + "\n\n" + load("write-message/references/pr-comments.md"); }
    static String documentRules(String kind) {
        if (!Set.of("brief", "meeting", "plan", "research", "decision").contains(kind)) throw new IllegalArgumentException("문서 종류를 확인하세요.");
        return load("write-document/SKILL.md") + "\n\n" + load("write-document/references/" + kind + ".md") + (kind.equals("decision") || kind.equals("plan") ? "\n\n" + load("write-document/references/reader-check.md") : "");
    }
    private static String load(String name) {
        try (InputStream input = WritingStyle.class.getResourceAsStream("/writing/skills/" + name)) {
            return new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
