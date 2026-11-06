package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

final class GoogleBookingCalendar implements BookingCalendar {
    static final String SCOPE = "https://www.googleapis.com/auth/calendar.events";
    private final GroupwareSettings settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    GoogleBookingCalendar(GroupwareSettings settings) { this.settings = settings; }
    ObjectNode status() throws Exception {
        HttpResponse<String> response = send("GET", events() + "?maxResults=1&fields=accessRole", null);
        if (response.statusCode() != 200) throw calendarError(response.statusCode());
        String role = Json.text(Json.read(response.body()), "accessRole");
        if (!Set.of("owner", "writer").contains(role)) throw new IllegalStateException("대상 구글 캘린더에 일정 쓰기 권한이 없습니다. 캘린더 공유 설정을 확인하세요.");
        return Json.obj("connected", true, "calendarId", settings.googleCalendar(), "accessRole", role);
    }
    public String create(String reservationId, GroupwareBooking.Request request, String room) throws Exception {
        String id = eventId(settings.baseUrl(), reservationId);
        String path = events() + "/" + id;
        HttpResponse<String> existing = send("GET", path, null);
        if (existing.statusCode() == 200) return Json.read(existing.body()).path("htmlLink").asText();
        if (existing.statusCode() != 404) throw calendarError(existing.statusCode());
        ObjectNode body = event(id, request, room);
        HttpResponse<String> saved = send("POST", events(), body);
        if (saved.statusCode() == 409) saved = send("GET", path, null);
        if (saved.statusCode() != 200 && saved.statusCode() != 201) throw calendarError(saved.statusCode());
        return Json.read(saved.body()).path("htmlLink").asText();
    }
    public void delete(String reservationId) throws Exception {
        HttpResponse<String> response = send("DELETE", events() + "/" + eventId(settings.baseUrl(), reservationId), null);
        if (!Set.of(204, 404, 410).contains(response.statusCode())) throw calendarError(response.statusCode());
    }
    static String eventId(String baseUrl, String reservationId) { return "a" + TranscriptImporter.hash(baseUrl + ":" + reservationId); }
    static ObjectNode event(String id, GroupwareBooking.Request request, String room) {
        return Json.obj("id", id, "summary", request.purpose(), "location", room,
                "start", Json.obj("dateTime", request.date().atTime(request.start()).atZone(KoreaTime.ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "timeZone", "Asia/Seoul"),
                "end", Json.obj("dateTime", request.date().atTime(request.end()).atZone(KoreaTime.ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "timeZone", "Asia/Seoul"));
    }
    private String events() { return "https://www.googleapis.com/calendar/v3/calendars/" + encode(settings.googleCalendar()) + "/events"; }
    private HttpResponse<String> send(String method, String url, ObjectNode body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken()).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
    private synchronized String accessToken() throws Exception {
        if (!Files.exists(settings.googleToken())) throw new IllegalStateException("구글 캘린더 로그인이 필요합니다. ./groupware.ps1 calendar-login을 실행하세요.");
        ObjectNode token;
        try { token = (ObjectNode) Json.read(new String(Crypt32Util.cryptUnprotectData(Files.readAllBytes(settings.googleToken())), StandardCharsets.UTF_8)); }
        catch (RuntimeException e) { throw new IllegalStateException("구글 로그인 정보를 읽지 못했습니다. ./groupware.ps1 calendar-login으로 다시 로그인하세요."); }
        if (token.path("expiresAt").asLong() <= Instant.now().getEpochSecond() + 60) {
            JsonNode client = client(settings);
            ObjectNode refreshed = token(Map.of("client_id", Json.required(client, "client_id"), "client_secret", Json.required(client, "client_secret"), "refresh_token", Json.required(token, "refresh_token"), "grant_type", "refresh_token"));
            refreshed.put("refresh_token", Json.required(token, "refresh_token"));
            saveToken(settings, refreshed); token = refreshed;
        }
        return Json.required(token, "access_token");
    }
    private ObjectNode token(Map<String, String> fields) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token")).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(fields))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("구글 로그인을 갱신하지 못했습니다. ./groupware.ps1 calendar-login으로 다시 로그인하세요.");
        ObjectNode result = (ObjectNode) Json.read(response.body());
        result.put("expiresAt", Instant.now().getEpochSecond() + result.path("expires_in").asLong(3600)); return result;
    }
    private static JsonNode client(GroupwareSettings settings) throws Exception {
        if (!Files.exists(settings.googleClient())) throw new IllegalStateException("공통 설정 폴더에 Google 데스크톱 앱 OAuth JSON을 google-client.json으로 저장하세요.");
        JsonNode client = Json.read(Files.readString(settings.googleClient())).path("installed");
        if (!client.isObject()) throw new IllegalArgumentException("Google OAuth JSON의 애플리케이션 유형은 데스크톱 앱이어야 합니다.");
        return client;
    }
    private static void saveToken(GroupwareSettings settings, ObjectNode token) throws Exception {
        GroupwareSettings.atomicWrite(settings.googleToken(), Crypt32Util.cryptProtectData(Json.write(token).getBytes(StandardCharsets.UTF_8)));
    }
    static void login(GroupwareSettings settings) throws Exception {
        JsonNode client = client(settings);
        String state = random(), verifier = random();
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        CompletableFuture<String> code = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String redirect = "http://127.0.0.1:" + server.getAddress().getPort() + "/callback";
        server.createContext("/callback", exchange -> {
            String message;
            int status;
            Map<String, String> query = new HashMap<>();
            String raw = exchange.getRequestURI().getRawQuery();
            if (raw != null) for (String field : raw.split("&")) { String[] pair = field.split("=", 2); if (pair.length == 2) query.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8)); }
            if (!MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), query.getOrDefault("state", "").getBytes(StandardCharsets.UTF_8))) { status = 400; message = "로그인 요청을 확인하지 못했습니다."; }
            else if (query.containsKey("code")) { code.complete(query.get("code")); status = 200; message = "로그인 응답을 받았습니다. work-agent 실행 창에서 연결 결과를 확인하세요."; }
            else { code.completeExceptionally(new IllegalStateException("구글 캘린더 접근이 승인되지 않았습니다.")); status = 400; message = "구글 캘린더 접근이 승인되지 않았습니다."; }
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            Map<String, String> fields = Map.of("client_id", Json.required(client, "client_id"), "redirect_uri", redirect, "response_type", "code", "scope", SCOPE, "access_type", "offline", "prompt", "consent", "state", state, "code_challenge", challenge, "code_challenge_method", "S256");
            URI url = URI.create("https://accounts.google.com/o/oauth2/v2/auth?" + form(fields));
            System.out.println("브라우저에서 구글 계정과 캘린더 접근 권한을 확인해 주세요. 5분 동안 기다립니다.");
            Desktop.getDesktop().browse(url);
            ObjectNode token = new GoogleBookingCalendar(settings).token(Map.of("client_id", Json.required(client, "client_id"), "client_secret", Json.required(client, "client_secret"), "code", code.get(5, TimeUnit.MINUTES), "code_verifier", verifier, "redirect_uri", redirect, "grant_type", "authorization_code"));
            if (Json.text(token, "refresh_token").isBlank()) throw new IllegalStateException("오프라인 접근 권한을 받지 못했습니다. 구글 캘린더 연결을 다시 실행하세요.");
            saveToken(settings, token);
            System.out.println("구글 캘린더 연결을 완료했습니다. 로그인 정보는 공통 폴더에 암호화해 저장했습니다.");
            System.out.println(Json.write(new GoogleBookingCalendar(settings).status()));
        } finally { server.stop(0); }
    }
    private static String random() { byte[] bytes = new byte[48]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String form(Map<String, String> fields) { return String.join("&", fields.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).toList()); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static IllegalStateException calendarError(int status) { return new IllegalStateException("구글 캘린더 요청이 실패했습니다 (HTTP " + status + "). Google Calendar API 활성화 여부와 대상 캘린더의 쓰기 권한을 확인하세요."); }
}
