package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

final class CliModel implements Agent.Model {
    private final Config config;
    private final Path directory;
    CliModel(Config config) throws Exception {
        this.config = config;
        directory = config.data().resolveSibling("agent");
        Files.createDirectories(directory);
    }
    @Override public JsonNode complete(String prompt) throws Exception {
        String engine = config.get("AGENT_ENGINE", "codex");
        JsonNode result = complete(prompt, engine, config.get("AGENT_EXECUTABLE", ""), config.get("AGENT_MODEL", ""), config.number("AGENT_TIMEOUT_SECONDS", 180), "action-schema.json");
        if (!result.path("actions").isArray() || !result.path("reply").isTextual()) throw new IllegalStateException("AI 응답 형식을 확인하지 못했습니다.");
        return result;
    }
    JsonNode review(String prompt) throws Exception {
        return complete(prompt, config.get("AGENT_ENGINE", "codex"), config.get("AGENT_EXECUTABLE", ""), config.get("AGENT_MODEL", ""), config.number("REVIEW_TIMEOUT_SECONDS", 600), "review-schema.json");
    }
    void startClaudeSession() throws Exception {
        JsonNode result = complete("1+1? reply에는 숫자 문자열 2만 넣고 actions는 빈 배열로 출력하세요.", "claude", config.get("CLAUDE_EXECUTABLE", ""), "", 60, "action-schema.json");
        if (!Json.text(result, "reply").trim().equals("2") || !result.path("actions").isEmpty()) throw new IllegalStateException("Claude 예약 요청의 응답을 확인하지 못했습니다.");
    }
    private JsonNode complete(String prompt, String engine, String executable, String model, int timeout, String schemaName) throws Exception {
        if (engine.equals("off")) throw new IllegalStateException("자연어 처리가 꺼져 있습니다. AGENT_ENGINE을 codex 또는 claude로 설정하세요.");
        Path schema = directory.resolve(schemaName);
        try (var in = CliModel.class.getResourceAsStream("/" + schemaName)) { Files.write(schema, Objects.requireNonNull(in).readAllBytes()); }
        Path output = Files.createTempFile(directory, "response-", ".json");
        Path stderr = Files.createTempFile(directory, "cli-", ".log");
        List<String> command = new ArrayList<>();
        if (engine.equals("claude")) {
            command.add(executable.isBlank() ? "claude" : executable);
            String schemaArgument = Json.write(Json.read(Files.readString(schema)));
            // Preserve JSON quotes through Java's Windows native command-line encoding.
            if (System.getProperty("os.name").startsWith("Windows")) schemaArgument = "\"" + schemaArgument.replace("\"", "\\\"") + "\"";
            command.addAll(List.of("-p", "--output-format", "json", "--json-schema", schemaArgument,
                    "--tools", "", "--strict-mcp-config", "--setting-sources", "", "--disable-slash-commands", "--no-session-persistence"));
        } else if (engine.equals("codex")) {
            Path npm = Path.of(System.getenv().getOrDefault("APPDATA", System.getProperty("user.home")), "npm/node_modules/@openai/codex/bin/codex.js");
            if (executable.isBlank() && System.getProperty("os.name").startsWith("Windows") && Files.exists(npm)) command.addAll(List.of("node", npm.toString()));
            else command.add(executable.isBlank() ? "codex" : executable);
            command.addAll(List.of("exec", "--skip-git-repo-check", "--ephemeral", "--ignore-user-config", "--sandbox", "read-only",
                    "--disable", "shell_tool", "--disable", "hooks", "--disable", "plugins", "--disable", "memories",
                    "-c", "web_search=\"disabled\"", "--output-schema", schema.toString(), "--output-last-message", output.toString(), "--color", "never"));
        } else throw new IllegalArgumentException("AGENT_ENGINE은 codex, claude 또는 off여야 합니다.");
        if (!model.isBlank()) command.addAll(List.of("--model", model));
        if (engine.equals("codex")) command.add("-");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectError(stderr.toFile());
            if (engine.equals("claude")) builder.redirectOutput(output.toFile()); else builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            for (String name : List.of("SLACK_APP_TOKEN", "SLACK_BOT_TOKEN", "SLACK_REFRESH_TOKEN", "SLACK_CLIENT_ID", "SLACK_CLIENT_SECRET", "REFRESH_TOKEN", "JIRA_TOKEN", "JIRA_API_TOKEN", "BITBUCKET_API_TOKEN", "CALENDAR_ICS_URL", "WORK_AGENT_TOKEN", "ANTHROPIC_API_KEY")) builder.environment().remove(name);
            if (engine.equals("claude")) builder.environment().put("CLAUDE_BOT_SESSION", "1");
            if (config.has("JIRA_TOKEN_ENV")) builder.environment().remove(config.get("JIRA_TOKEN_ENV", ""));
            if (config.has("BITBUCKET_TOKEN_ENV")) builder.environment().remove(config.get("BITBUCKET_TOKEN_ENV", ""));
            process = builder.start();
            try (var writer = process.outputWriter(StandardCharsets.UTF_8)) { writer.write(prompt); }
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
                throw new IllegalStateException("자연어 처리 시간이 초과됐습니다. 잠시 후 다시 요청하세요.");
            }
            if (process.exitValue() != 0 || Files.size(output) == 0) throw failure(engine, process.exitValue(), Files.readString(stderr, StandardCharsets.UTF_8));
            JsonNode result = Json.read(Files.readString(output));
            if (engine.equals("claude")) {
                if (result.path("is_error").asBoolean()) throw new IllegalStateException("Claude 요청에 실패했습니다. CLI 로그인 상태를 확인하세요.");
                if (result.hasNonNull("structured_output")) result = result.get("structured_output");
                else result = Json.read(Json.required(result, "result"));
            }
            return result;
        } finally {
            if (process != null && process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
            Files.deleteIfExists(output); Files.deleteIfExists(stderr);
        }
    }
    static IllegalStateException failure(String engine, int exit, String diagnostic) {
        String text = diagnostic.lines().map(line -> line.toLowerCase(Locale.ROOT)).filter(line -> line.startsWith("error") || line.startsWith("fatal") || line.contains(" error ") || line.contains("\"type\":\"error\"")).reduce("", (left, right) -> left + "\n" + right);
        String reason;
        if (text.contains("context_length_exceeded") || text.contains("context window") || text.contains("input is too long") || text.contains("request too large")) reason = "입력 크기가 모델이 처리할 수 있는 범위를 넘었습니다. 검토 범위를 나누어 다시 요청하세요.";
        else if (text.contains("usage limit") || text.contains("rate limit") || text.contains("too many requests") || text.contains("quota") || text.contains("429")) reason = "사용량 또는 요청 횟수 제한에 도달했습니다. CLI에서 사용량을 확인한 뒤 다시 요청하세요.";
        else if (text.contains("unauthorized") || text.contains("authentication") || text.contains("401") || text.contains("not logged in")) reason = "로그인 정보를 확인하지 못했습니다. CLI에 다시 로그인한 뒤 요청하세요.";
        else if (text.contains("connection") || text.contains("stream disconnected") || text.contains("timed out") || text.contains("502") || text.contains("503")) reason = "서비스 연결이 중단됐습니다. 잠시 후 다시 요청하세요.";
        else reason = "응답을 받지 못했습니다. 잠시 후 다시 요청하고, 반복되면 CLI 실행 설정을 확인하세요.";
        return new IllegalStateException(engine + " 실행 실패(종료 코드 " + exit + "): " + reason);
    }
}
