package local.work;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Reads original logs without changing them. Cursors stop before unfinished lines. */
final class TranscriptImporter {
    private static final Pattern ISSUE = Pattern.compile("(?<![A-Z0-9_])([A-Z][A-Z0-9_]*-\\d+)(?![A-Z0-9])");
    private final WorkService service;
    private final Config config;
    TranscriptImporter(WorkService service, Config config) { this.service = service; this.config = config; }
    synchronized ObjectNode sync() throws Exception {
        int[] counts = {0, 0};
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Path home = Path.of(System.getProperty("user.home"));
        scan("codex", Path.of(config.has("CODEX_LOG_ROOT") ? config.get("CODEX_LOG_ROOT", "") : home.resolve(".codex/sessions").toString()), counts, errors);
        scan("claude", Path.of(config.has("CLAUDE_LOG_ROOT") ? config.get("CLAUDE_LOG_ROOT", "") : home.resolve(".claude/projects").toString()), counts, errors);
        new ActivityLinks(service.store).repair();
        SessionCatalog sessions = new SessionCatalog(service.store); sessions.refresh();
        Path codexHome = home.resolve(".codex");
        if (Files.isDirectory(codexHome)) try (var files = Files.list(codexHome)) {
            Path state = files.filter(path -> path.getFileName().toString().matches("state_[0-9]+\\.sqlite")).max(Comparator.comparingInt(path -> Integer.parseInt(path.getFileName().toString().replaceAll("[^0-9]", "")))).orElse(null);
            if (state != null) try { sessions.codexTitles(state); } catch (Exception e) { warnings.add("Codex 세션 제목을 확인하지 못했습니다. 수집한 기록은 유지합니다."); }
        }
        ObjectNode result = Json.obj("id", "cli", "at", service.time.instant().toString(), "files", counts[0], "messages", counts[1], "errors", errors, "warnings", warnings, "ok", errors.isEmpty());
        service.store.put("sync", result);
        return result;
    }
    private void scan(String source, Path root, int[] counts, List<String> errors) throws Exception {
        if (!Files.isDirectory(root)) return;
        Instant cutoff = service.time.instant().minus(Duration.ofDays(config.number("IMPORT_DAYS", 14)));
        try (var paths = Files.walk(root)) {
            List<Path> files = paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".jsonl"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/subagents/"))
                    .filter(p -> { try { return Files.getLastModifiedTime(p).toInstant().isAfter(cutoff); } catch (IOException e) { return false; } }).toList();
            for (Path file : files) {
                try { counts[1] += read(source, file, cutoff); counts[0]++; }
                catch (Exception e) { errors.add(file.getFileName() + ": " + e.getClass().getSimpleName()); }
            }
        }
    }
    int read(String source, Path file, Instant cutoff) throws Exception {
        int[] result = {0};
        try {
            service.store.transaction(() -> {
                try { result[0] = readFile(source, file, cutoff); }
                catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
            });
        } catch (java.util.concurrent.CompletionException e) { throw (Exception) e.getCause(); }
        return result[0];
    }
    private int readFile(String source, Path file, Instant cutoff) throws Exception {
        ActivityLinks links = new ActivityLinks(service.store);
        String cursorId = hash(source + ":" + file.toAbsolutePath().normalize());
        ObjectNode saved = service.store.get("cursor", cursorId);
        long position = saved == null ? 0 : saved.path("position").asLong();
        String session = saved == null ? file.getFileName().toString() : Json.text(saved, "session");
        String cwd = saved == null ? "" : Json.text(saved, "cwd");
        int count = 0;
        if (position > Files.size(file)) position = 0;
        try (InputStream raw = Files.newInputStream(file)) {
            raw.skipNBytes(position);
            InputStream stream = new BufferedInputStream(raw, 65536);
            while (true) {
                long begin = position;
                Line line = completeLine(stream);
                if (line == null) { position = begin; break; }
                position += line.length();
                JsonNode row;
                try { row = Json.read(line.text()); } catch (IllegalArgumentException e) { continue; }
                if ("session_meta".equals(Json.text(row, "type"))) {
                    session = row.path("payload").path("id").asText(session);
                    cwd = Json.text(row.path("payload"), "cwd"); continue;
                }
                if (row.hasNonNull("cwd")) cwd = Json.text(row, "cwd");
                if (row.hasNonNull("sessionId")) session = Json.text(row, "sessionId");
                if (Json.text(row, "type").equals("custom-title") && !Json.text(row, "customTitle").isBlank()) {
                    service.store.put("session", Json.obj("id", source + ":" + session, "name", Json.text(row, "customTitle"))); continue;
                }
                if (cwd.replace('\\', '/').endsWith("/work-agent/work/agent") || cwd.replace('\\', '/').contains("/review-worktrees/") || ActivitySummary.automatedPath(cwd)) continue;
                String timestamp = Json.text(row, "timestamp");
                if (timestamp.isBlank()) continue;
                Instant occurred;
                try { occurred = KoreaTime.parse(timestamp); } catch (RuntimeException e) { continue; }
                if (occurred.isBefore(cutoff)) continue;
                JsonNode message;
                if (source.equals("codex")) {
                    if (!"response_item".equals(Json.text(row, "type")) || !"message".equals(Json.text(row.path("payload"), "type"))) continue;
                    message = row.path("payload");
                } else {
                    if (row.path("isSidechain").asBoolean() || !Set.of("user", "assistant").contains(Json.text(row, "type"))) continue;
                    message = row.path("message");
                }
                String role = Json.text(message, "role");
                if (!Set.of("user", "assistant").contains(role)) continue;
                String text = messageText(message.path("content"));
                if (text.isBlank() || text.startsWith("<environment_context>") || text.startsWith("# AGENTS.md instructions") || text.startsWith("<external_codex_apps")) continue;
                String date = occurred.atZone(KoreaTime.ZONE).toLocalDate().toString();
                String id = hash(source + ":" + session + ":" + begin);
                Set<String> keys = new LinkedHashSet<>();
                Matcher matcher = ISSUE.matcher(text);
                while (matcher.find()) keys.add(matcher.group(1));
                String sessionDayId = hash(source + ":" + session + ":" + date);
                ObjectNode sessionDay = service.store.get("sessionDay", sessionDayId);
                if (sessionDay != null) for (JsonNode key : sessionDay.path("issueKeys")) keys.add(key.asText());
                service.store.put("sessionDay", Json.obj("id", sessionDayId, "issueKeys", keys));
                ObjectNode evidence = Json.obj("id", id, "source", source, "session", session, "cwd", cwd, "date", date,
                        "occurredAt", occurred.toString(), "role", role, "text", text.substring(0, Math.min(text.length(), 6000)),
                        "issueKeys", keys, "sourceRef", file.toAbsolutePath() + ":byte=" + begin);
                service.store.put("evidence", evidence);
                if (role.equals("assistant")) {
                    String activityId = sessionDayId;
                    ObjectNode old = service.store.get("activity", activityId);
                    String chosen = old != null && old.path("userLinked").asBoolean() ? Json.text(old, "jiraKey") : links.candidate(Json.MAPPER.valueToTree(keys));
                    String title = Arrays.stream(text.split("\\R")).map(s -> s.replaceAll("^[#*\\s-]+", "").trim()).filter(s -> !s.isBlank()).findFirst().orElse("작업 기록");
                    if (title.length() > 180) title = title.substring(0, 180) + "…";
                    ObjectNode activity = Json.obj("id", activityId, "title", title, "date", date, "jiraKey", chosen,
                            "candidateKeys", keys, "source", source, "sourceRef", evidence.get("sourceRef"), "session", session,
                            "product", cwd.isBlank() ? "미분류" : Optional.ofNullable(Path.of(cwd).getFileName()).map(Path::toString).orElse(cwd), "occurredAt", occurred.toString(),
                            "userLinked", old != null && old.path("userLinked").asBoolean(), "evidenceId", id);
                    if (old != null && old.has("taskId")) activity.set("taskId", old.get("taskId"));
                    service.store.put("activity", activity);
                }
                count++;
            }
        }
        service.store.put("cursor", Json.obj("id", cursorId, "position", position, "session", session, "cwd", cwd));
        return count;
    }
    static String messageText(JsonNode content) {
        if (content.isTextual()) return content.asText();
        StringBuilder text = new StringBuilder();
        if (content.isArray()) for (JsonNode block : content) {
            if (Set.of("text", "input_text", "output_text").contains(Json.text(block, "type"))) text.append(Json.text(block, "text")).append('\n');
        }
        return text.toString().trim();
    }
    private record Line(String text, long length) {}
    private static Line completeLine(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean tooLong = false;
        long length = 0;
        int b;
        while ((b = stream.read()) != -1) {
            length++;
            if (b == '\n') return new Line(tooLong ? "{}" : bytes.toString(StandardCharsets.UTF_8), length);
            if (bytes.size() < 4_000_000) bytes.write(b); else tooLong = true;
        }
        return null;
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 32); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
