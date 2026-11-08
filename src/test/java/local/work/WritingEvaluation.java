package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Runs controlled, read-only writing comparisons using the same bundled profiles as the application. */
public final class WritingEvaluation {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]); int repeats = Integer.parseInt(args[1]); Files.createDirectories(output);
        JsonNode cases = Json.read(Files.readString(Path.of("evals/writing/cases.json"), StandardCharsets.UTF_8)).path("outputs");
        Map<String, String> environment = new HashMap<>(System.getenv()); environment.put("WORK_AGENT_DATA", output.resolve("cli/data").toAbsolutePath().toString()); environment.put("AGENT_TIMEOUT_SECONDS", "240");
        CliModel model = new CliModel(new Config(Path.of("config.properties"), environment));
        List<ObjectNode> results = new ArrayList<>(); int criticalFailures = 0, qualityFailures = 0;
        for (int repeat = 1; repeat <= repeats; repeat++) {
            Map<String, JsonNode> versions = new LinkedHashMap<>();
            for (String mode : List.of("baseline", "candidate")) {
                List<ObjectNode> inputs = new ArrayList<>();
                for (JsonNode item : cases) {
                    String profile = item.path("format").asText().equals("message") ? (item.path("kind").asText().equals("pr") ? WritingStyle.reviewRules() : WritingStyle.messageRules()) : WritingStyle.documentRules(item.path("kind").asText());
                    inputs.add(Json.obj("id", Json.required(item, "id"), "request", Json.required(item, "prompt"), "instructions", mode.equals("candidate") ? profile : ""));
                }
                String prompt = "각 사례는 독립적인 글쓰기 요청입니다. 공통 기준과 각 사례의 instructions를 적용하고 바로 사용할 글을 작성하세요. 이 평가는 최종 출력만 비교하므로 파일 저장·외부 게시·별도 독자 호출은 수행하지 않습니다. reply에 JSON 배열 [{\"id\":\"사례 ID\",\"text\":\"글 본문\"}]을 문자열로 넣고 actions=[]로 출력하세요. 평가 의견은 글에 덧붙이지 마세요.\n사례(JSON):\n" + Json.write(inputs);
                JsonNode outputs = Json.read(reply(model.complete(WritingStyle.prompt(prompt))));
                if (!outputs.isArray() || outputs.size() != cases.size()) throw new IllegalStateException("평가 결과의 사례 수가 맞지 않습니다.");
                versions.put(mode, outputs); save(output.resolve(mode + "-" + repeat + ".json"), outputs);
            }
            List<ObjectNode> pairs = new ArrayList<>(); boolean reversed = repeat % 2 == 0;
            for (JsonNode item : cases) {
                String id = Json.required(item, "id"); String baseline = text(versions.get("baseline"), id), candidate = text(versions.get("candidate"), id);
                List<String> missing = new ArrayList<>(); for (JsonNode literal : item.path("required")) if (!candidate.contains(literal.asText())) missing.add(literal.asText());
                criticalFailures += missing.size();
                results.add(Json.obj("repeat", repeat, "id", id, "missingLiterals", missing, "baselineLength", baseline.length(), "candidateLength", candidate.length()));
                pairs.add(Json.obj("id", id, "request", item.path("prompt"), "criteria", item.path("criteria"), "A", reversed ? candidate : baseline, "B", reversed ? baseline : candidate));
            }
            String judge = "글을 작성한 맥락을 모르는 검토자입니다. 각 요청과 기준에 따라 A와 B를 따로 평가하세요. 출처 없는 사실, 조건·부정·수치·확신 정도 변경은 facts=false입니다. 요청한 형식과 길이를 지키고 불필요한 항목이 없으면 fit=true입니다. 취향만으로 실패를 만들지 마세요. reply에 JSON 배열 [{\"id\":\"사례 ID\",\"A\":{\"facts\":true,\"fit\":true},\"B\":{\"facts\":true,\"fit\":true},\"reason\":\"판단 근거\"}]을 문자열로 넣고 actions=[]로 출력하세요.\n" + Json.write(pairs);
            JsonNode judged = Json.read(reply(model.complete(judge))); save(output.resolve("judge-" + repeat + ".json"), judged);
            for (JsonNode item : cases) {
                String id = Json.required(item, "id"); JsonNode verdict = find(judged, id).path(reversed ? "A" : "B");
                if (!verdict.path("facts").isBoolean() || !verdict.path("fit").isBoolean()) throw new IllegalStateException("평가 판정의 형식이 맞지 않습니다.");
                if (!verdict.path("facts").asBoolean()) criticalFailures++;
                if (!verdict.path("fit").asBoolean()) qualityFailures++;
            }
            System.out.println(Json.write(Json.obj("repeat", repeat, "cases", cases.size(), "criticalFailures", criticalFailures, "qualityFailures", qualityFailures)));
        }
        save(output.resolve("output-summary.json"), Json.obj("repeats", repeats, "cases", cases.size(), "candidateOutputs", repeats * cases.size(), "criticalFailures", criticalFailures, "qualityFailures", qualityFailures, "baseline", "공통 기준만 사용", "candidate", "공통 기준과 작업별 스킬 사용", "limitations", "동일 모델의 소규모 반복 비교입니다. 자동 선택과 독자 검토 전체 절차는 별도로 평가합니다."));
        if (criticalFailures > 0 || qualityFailures > 0) throw new IllegalStateException("글쓰기 평가에서 확인할 실패가 있습니다. 저장된 결과를 검토하세요.");
    }
    private static String reply(JsonNode result) { if (!result.path("actions").isArray() || !result.path("actions").isEmpty()) throw new IllegalStateException("평가 중 실행 요청을 거부했습니다."); return Json.required(result, "reply"); }
    private static JsonNode find(JsonNode rows, String id) { for (JsonNode row : rows) if (id.equals(Json.text(row, "id"))) return row; throw new IllegalStateException("평가 사례가 누락됐습니다: " + id); }
    private static String text(JsonNode rows, String id) { return Json.required(find(rows, id), "text"); }
    private static void save(Path file, Object value) throws Exception { Files.writeString(file, Json.write(value), StandardCharsets.UTF_8); }
}
