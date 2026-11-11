package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class GroupwareSettings {
    final Path file;
    private final ObjectNode values;

    static Path location(Config config) {
        String local = config.environment("LOCALAPPDATA");
        Path home = local.isBlank() ? Path.of(System.getProperty("user.home"), "AppData", "Local") : Path.of(local);
        return (config.has("GROUPWARE_CONFIG") ? Path.of(config.get("GROUPWARE_CONFIG", "")) : home.resolve("Suresoft/groupware/settings.json")).toAbsolutePath().normalize();
    }
    GroupwareSettings(Config config) throws Exception {
        file = location(config);
        Files.createDirectories(file.getParent());
        try (var in = GroupwareSettings.class.getResourceAsStream("/groupware-defaults.json")) {
            values = (ObjectNode) Json.read(new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8));
        }
        if (Files.exists(file)) {
            JsonNode saved = Json.read(Files.readString(file));
            if (!saved.isObject()) throw new IllegalArgumentException("그룹웨어 공통 설정은 JSON 객체여야 합니다.");
            values.setAll((ObjectNode) saved);
        }
        if (!Files.exists(file)) atomicWrite(file, Json.write(values).getBytes(StandardCharsets.UTF_8));
    }
    String baseUrl() {
        String url = Json.required(values, "baseUrl").replaceAll("/+$", "");
        if (!url.startsWith("https://")) throw new IllegalArgumentException("그룹웨어 주소는 https:// 주소를 사용하세요.");
        return url;
    }
    Path cookies() { return resolve("cookieFile"); }
    Path bookings() { return resolve("bookingFile"); }
    Path googleClient() { return file.getParent().resolve(values.path("googleClientFile").asText("google-client.json")); }
    Path googleToken() { return file.getParent().resolve(values.path("googleTokenFile").asText("google-token.enc")); }
    String googleCalendar() { return values.path("googleCalendarId").asText("primary"); }
    List<JsonNode> rooms() {
        List<JsonNode> rooms = new ArrayList<>(); values.path("rooms").forEach(rooms::add); return rooms;
    }
    List<String> preferred() {
        List<String> rooms = new ArrayList<>(); values.path("preferredRooms").forEach(n -> rooms.add(n.asText())); return rooms;
    }
    private Path resolve(String name) { return file.getParent().resolve(Json.required(values, name)).normalize(); }
    static void atomicWrite(Path path, byte[] bytes) throws Exception {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), ".groupware-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
    }
    List<JsonNode> history() throws Exception {
        if (!Files.exists(bookings())) return List.of();
        List<JsonNode> result = new ArrayList<>();
        for (String line : Files.readAllLines(bookings())) if (!line.isBlank()) result.add(Json.read(line));
        Collections.reverse(result);
        Set<String> seen = new HashSet<>();
        return result.stream().filter(n -> Json.text(n, "requestId").isBlank() || seen.add(Json.text(n, "requestId"))).toList();
    }
    void record(ObjectNode entry) throws Exception {
        Files.createDirectories(bookings().getParent());
        Files.writeString(bookings(), Json.write(entry) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    BookingLock lock() throws Exception { return new BookingLock(file.getParent().resolve("booking.lock")); }
    static final class BookingLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        BookingLock(Path path) throws Exception {
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock acquired;
            try { acquired = channel.tryLock(0, 1, false); }
            catch (OverlappingFileLockException e) { acquired = null; }
            if (acquired == null) { channel.close(); throw new IllegalStateException("다른 프로그램에서 회의실을 처리 중입니다. 잠시 후 다시 요청하세요."); }
            lock = acquired;
        }
        public void close() throws Exception { try { lock.release(); } finally { channel.close(); } }
    }
}
