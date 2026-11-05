package local.work;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class Main {
    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone(KoreaTime.ZONE));
        System.setProperty("net.fortuna.ical4j.timezone.update.enabled", "false");
        Path configFile = Path.of("config.properties");
        Integer portOverride = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configFile = Path.of(args[++i]);
                case "--port" -> portOverride = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("지원하지 않는 인수: " + args[i]);
            }
        }
        Config config = new Config(configFile);
        KoreaTime time = new KoreaTime(Clock.systemUTC());
        Store store = new Store(config.data().resolve("work-agent.db"));
        WorkService service = new WorkService(store, time, new JiraClient(config));
        Reports reports = new Reports(service);
        TranscriptImporter importer = new TranscriptImporter(service, config);
        CalendarSync calendar = new CalendarSync(config, service);
        Agent.Synchronizer sync = kind -> {
            ObjectNode result = Json.object();
            for (String target : kind.equals("all") ? List.of("cli", "jira", "calendar") : List.of(kind)) {
                try {
                    switch (target) {
                        case "cli" -> result.set("cli", importer.sync());
                        case "jira" -> { if (service.jira.configured()) { service.jira.syncIssues(service); result.put("jira", "확인했습니다."); } else result.put("jira", "연결 설정이 필요합니다."); }
                        case "calendar" -> { if (calendar.configured()) result.set("calendar", calendar.sync()); else result.put("calendar", "연결 설정이 필요합니다."); }
                        case "team" -> { service.jira.syncTeam(service); result.put("team", "확인했습니다."); }
                        default -> throw new IllegalArgumentException("수집 대상을 확인하세요.");
                    }
                } catch (Exception e) {
                    store.put("sync", Json.obj("id", target, "at", time.instant().toString(), "ok", false, "error", SlackConnection.friendly(e)));
                    if (!kind.equals("all")) throw e;
                    result.put(target, SlackConnection.friendly(e));
                }
            }
            return result;
        };
        Agent agent = new Agent(service, reports, new CliModel(config), sync, config.agentName(), config.agentCallName());
        SlackConnection slack = new SlackConnection(config, store, agent);
        Scheduler scheduler = new Scheduler(service, reports, slack, config, sync);
        int port = portOverride == null ? config.number("PORT", 8765) : portOverride;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        String token = config.has("WORK_AGENT_TOKEN") ? config.get("WORK_AGENT_TOKEN", "") : UUID.randomUUID().toString() + UUID.randomUUID();
        ExecutorService httpWorkers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(httpWorkers);
        server.createContext("/", exchange -> {
            try {
                String host = exchange.getRequestHeaders().getFirst("Host");
                String origin = exchange.getRequestHeaders().getFirst("Origin");
                Set<String> allowedHosts = Set.of("127.0.0.1:" + port, "localhost:" + port);
                if (!allowedHosts.contains(host) || (origin != null && !Set.of("http://127.0.0.1:" + port, "http://localhost:" + port).contains(origin))) { json(exchange, 403, Json.obj("error", "이 PC의 업무 관리 화면에서 요청하세요.")); return; }
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'");
                if (path.equals("/") && method.equals("GET")) {
                    exchange.getResponseHeaders().add("Set-Cookie", "WA_SESSION=" + token + "; Path=/; HttpOnly; SameSite=Strict");
                    resource(exchange, "/web/index.html", "text/html; charset=utf-8"); return;
                }
                if (method.equals("GET") && Set.of("/app.js", "/style.css").contains(path)) { resource(exchange, "/web" + path, path.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/css; charset=utf-8"); return; }
                if (path.equals("/api/health") && method.equals("GET")) { json(exchange, 200, Json.obj("ok", true, "timezone", KoreaTime.ZONE.toString())); return; }
                if (!authorized(exchange, token)) { json(exchange, 401, Json.obj("error", "업무 관리 화면을 다시 열어 주세요.")); return; }
                Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
                LocalDate date = query.containsKey("date") ? LocalDate.parse(query.get("date")) : time.today();
                if (method.equals("GET")) {
                    switch (path) {
                        case "/api/state" -> json(exchange, 200, service.state(date));
                        case "/api/team" -> json(exchange, 200, service.team());
                        case "/api/tasks/transitions" -> json(exchange, 200, service.transitions(query.getOrDefault("id", "")));
                        case "/api/messages" -> json(exchange, 200, store.list("message").stream().filter(n -> Json.text(n, "conversation").equals("web")).toList());
                        case "/api/settings" -> json(exchange, 200, Json.obj("agentName", config.agentName(), "agentCallName", config.agentCallName(), "engine", config.get("AGENT_ENGINE", "codex"), "slackConfigured", slack.configured(), "slackConnected", slack.connected(), "slackError", slack.lastError(), "jiraConfigured", service.jira.configured(), "calendarConfigured", calendar.configured(), "briefingTime", config.get("BRIEFING_TIME", "09:00"), "fridayTime", config.get("FRIDAY_REVIEW_TIME", "16:30"), "claudeSessionTime", config.get("CLAUDE_SESSION_TIME", ""), "claudeSession", store.get("scheduled", "claude-session:" + time.today()), "timezone", "Asia/Seoul"));
                        case "/api/evidence" -> {
                            ObjectNode activity = store.get("activity", query.getOrDefault("id", ""));
                            ObjectNode evidence = activity == null ? null : store.get("evidence", Json.text(activity, "evidenceId"));
                            json(exchange, 200, evidence == null ? Json.obj("text", "원본이 연결되지 않은 활동입니다.") : evidence);
                        }
                        case "/api/report", "/report.html" -> {
                            String kind = query.getOrDefault("kind", "briefing");
                            String text = switch (kind) {
                                case "daily" -> reports.daily(date);
                                case "briefing" -> reports.briefing(date);
                                case "worklog" -> reports.worklog(time.monday(date), date);
                                case "oneonone" -> reports.oneOnOne(query.getOrDefault("memberId", ""));
                                default -> throw new IllegalArgumentException("보고서 종류를 확인하세요.");
                            };
                            if (path.equals("/report.html")) {
                                exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'");
                                String html = "<!doctype html><html lang=\"ko\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>업무 기록 " + date + "</title><style>body{max-width:820px;margin:48px auto;padding:0 24px;color:#24334b;font:16px/1.8 'Malgun Gothic',sans-serif}pre{font:inherit;white-space:pre-wrap;overflow-wrap:anywhere}@media print{body{margin:0;max-width:none}}</style><body><pre>" + escape(text) + "</pre></body></html>";
                                send(exchange, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
                            } else json(exchange, 200, Json.obj("text", text));
                        }
                        default -> json(exchange, 404, Json.obj("error", "페이지를 찾지 못했습니다."));
                    }
                } else if (method.equals("POST")) {
                    if (!Optional.ofNullable(exchange.getRequestHeaders().getFirst("Content-Type")).orElse("").startsWith("application/json")) throw new IllegalArgumentException("JSON 요청을 보내세요.");
                    byte[] bytes = exchange.getRequestBody().readNBytes(262145);
                    if (bytes.length > 262144) throw new IllegalArgumentException("요청 내용이 너무 큽니다.");
                    JsonNode body = Json.read(new String(bytes, StandardCharsets.UTF_8));
                    Object result = switch (path) {
                        case "/api/chat" -> agent.chat("web", body.path("requestId").asText(UUID.randomUUID().toString()), Json.required(body, "text"));
                        case "/api/tasks" -> service.task(body);
                        case "/api/tasks/status" -> service.changeStatus(body);
                        case "/api/tasks/hide" -> service.hideTask(body);
                        case "/api/tasks/hide-many" -> service.hideTasks(body);
                        case "/api/activities" -> service.activity(body);
                        case "/api/plan" -> service.plan(body);
                        case "/api/plan/item" -> service.planItem(body);
                        case "/api/followups" -> agent.suggestFollowups(date);
                        case "/api/followups/accept" -> service.acceptFollowup(body);
                        case "/api/meetings" -> service.meeting(body);
                        case "/api/daily" -> service.dailyNote(body);
                        case "/api/worklogs" -> service.saveWorklog(body);
                        case "/api/worklogs/post" -> service.postWorklog(LocalDate.parse(Json.required(body, "date")));
                        case "/api/members" -> service.member(body);
                        case "/api/observations" -> service.observation(body);
                        case "/api/jobs" -> service.job(body);
                        case "/api/sync" -> sync.sync(body.path("kind").asText("all"));
                        default -> throw new IllegalArgumentException("지원하지 않는 요청입니다.");
                    };
                    json(exchange, 200, result);
                } else json(exchange, 405, Json.obj("error", "지원하지 않는 요청 방식입니다."));
            } catch (IllegalArgumentException e) { json(exchange, 400, Json.obj("error", e.getMessage())); }
            catch (Exception e) { System.err.println(e.getClass().getSimpleName() + ": " + SlackConnection.friendly(e)); json(exchange, 502, Json.obj("error", SlackConnection.friendly(e))); }
            finally { exchange.close(); }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { scheduler.close(); slack.close(); server.stop(1); httpWorkers.shutdownNow(); try { store.close(); } catch (Exception ignored) {} }));
        server.start(); slack.start(); scheduler.start();
        System.out.println("work-agent (" + config.agentName() + "): http://127.0.0.1:" + port + " (Asia/Seoul)");
        System.out.println("데이터: " + config.data());
    }
    private static boolean authorized(HttpExchange exchange, String token) {
        String header = exchange.getRequestHeaders().getFirst("X-Work-Agent-Token");
        if (header != null && MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) return true;
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return false;
        return Arrays.stream(cookie.split(";\\s*")).anyMatch(c -> c.startsWith("WA_SESSION=") && MessageDigest.isEqual(c.substring(11).getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8)));
    }
    private static Map<String, String> query(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw != null) for (String field : raw.split("&")) { String[] pair = field.split("=", 2); result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length < 2 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8)); }
        return result;
    }
    private static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private static void resource(HttpExchange e, String name, String contentType) throws java.io.IOException {
        try (var in = Main.class.getResourceAsStream(name)) {
            if (in == null) send(e, 404, "text/plain; charset=utf-8", "파일을 찾지 못했습니다.".getBytes(StandardCharsets.UTF_8));
            else send(e, 200, contentType, in.readAllBytes());
        }
    }
    private static void json(HttpExchange e, int status, Object value) throws java.io.IOException { send(e, status, "application/json; charset=utf-8", Json.write(value).getBytes(StandardCharsets.UTF_8)); }
    private static void send(HttpExchange e, int status, String contentType, byte[] bytes) throws java.io.IOException {
        e.getResponseHeaders().set("Content-Type", contentType); e.sendResponseHeaders(status, bytes.length); e.getResponseBody().write(bytes);
    }
}
