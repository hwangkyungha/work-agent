package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class WritingStyleTest {
    @TempDir Path temporary;
    @Test void bundledGuideReachesAgentWithoutChangingRequestOrEnablingActions() throws Exception {
        Config config = new Config(temporary.resolve("none"), Map.of());
        try (Store store = new Store(temporary.resolve("test.db"))) {
            WorkService service = new WorkService(store, new KoreaTime(Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC)), new JiraClient(config));
            AtomicReference<String> received = new AtomicReference<>();
            Agent agent = new Agent(service, new Reports(service), prompt -> {
                received.set(prompt); return Json.obj("reply", "CT2612-486에 4h를 입력했습니다. Jira 등록 여부는 확인하지 못했습니다.", "actions", List.of());
            }, kind -> Json.object(), "호박", "호박아");
            String request = "CT2612-486에 4h 입력했지만 Jira 등록 여부는 확인하지 못했습니다. 이 문장만 다듬어줘.";
            JsonNode result = agent.chat("web", "writing-check", request);
            assertTrue(received.get().startsWith(Files.readString(Path.of("skills/work-agent-writing/common.md"), StandardCharsets.UTF_8)));
            assertTrue(received.get().endsWith("현재 사용자 요청:\n" + request));
            assertTrue(Json.text(result, "text").contains("CT2612-486에 4h"));
            assertTrue(store.list("task").isEmpty()); assertTrue(store.list("worklog").isEmpty());
        }
    }
    @Test void writingUsesRequestedProfileAndPreservesTheOriginalInputWithoutActions() throws Exception {
        Config config = new Config(temporary.resolve("none"), Map.of());
        try (Store store = new Store(temporary.resolve("writing.db"))) {
            WorkService service = new WorkService(store, new KoreaTime(Clock.systemUTC()), new JiraClient(config));
            List<String> prompts = new ArrayList<>();
            Agent agent = new Agent(service, new Reports(service), prompt -> {
                prompts.add(prompt);
                return prompts.size() == 1 ? Json.obj("reply", "", "actions", List.of(Json.obj("type", "write_text", "payload", Json.write(Json.obj("format", "message", "source", "none"))))) : Json.obj("reply", "CT-42에 2.5h를 입력했습니다. Jira 등록은 확인하지 못했습니다.", "actions", List.of());
            }, kind -> Json.object(), "호박", "호박아");
            String request = "CT-42에 2.5h 입력했고 Jira 등록은 미확인입니다. Slack 문장만 다듬어줘.";
            JsonNode reply = agent.chat("web", "draft", request);
            assertTrue(prompts.get(1).contains("name: write-message")); assertTrue(prompts.get(1).contains(request));
            assertTrue(Json.text(reply, "text").contains("2.5h")); assertTrue(store.list("worklog").isEmpty()); assertTrue(store.list("task").isEmpty());
        }
    }
    @Test void importantDocumentReaderReceivesOnlyDraftAndAudience() throws Exception {
        Config config = new Config(temporary.resolve("none"), Map.of());
        try (Store store = new Store(temporary.resolve("document.db"))) {
            WorkService service = new WorkService(store, new KoreaTime(Clock.systemUTC()), new JiraClient(config));
            List<String> prompts = new ArrayList<>();
            Agent agent = new Agent(service, new Reports(service), prompt -> {
                prompts.add(prompt);
                return switch (prompts.size()) {
                    case 1 -> Json.obj("reply", "", "actions", List.of(Json.obj("type", "write_text", "payload", Json.write(Json.obj("format", "document", "kind", "decision", "audience", "경영진")))));
                    case 2 -> Json.obj("reply", "2주 시험 적용 제안을 검토해 주세요. 비용은 미산정입니다.", "actions", List.of());
                    default -> Json.obj("reply", "{\"clear\":true,\"feedback\":\"\"}", "actions", List.of());
                };
            }, kind -> Json.object(), "호박", "호박아");
            agent.chat("web", "decision", "배경 PRIVATE_BACKGROUND를 참고해서 2주 시험 적용 검토 문서를 써줘. 비용은 미산정이다.");
            assertEquals(3, prompts.size()); assertTrue(prompts.get(1).contains("name: write-document"));
            assertFalse(prompts.get(2).contains("PRIVATE_BACKGROUND")); assertTrue(prompts.get(2).contains("경영진")); assertTrue(prompts.get(2).contains("비용은 미산정"));
        }
    }
    @Test void writingResponseCannotApplyAnExtraAction() throws Exception {
        Config config = new Config(temporary.resolve("none"), Map.of());
        try (Store store = new Store(temporary.resolve("bad-writing.db"))) {
            WorkService service = new WorkService(store, new KoreaTime(Clock.systemUTC()), new JiraClient(config));
            List<String> prompts = new ArrayList<>();
            Agent agent = new Agent(service, new Reports(service), prompt -> {
                prompts.add(prompt);
                return prompts.size() == 1 ? Json.obj("reply", "", "actions", List.of(Json.obj("type", "write_text", "payload", "{\"format\":\"message\"}"))) : Json.obj("reply", "초안", "actions", List.of(Json.obj("type", "task", "payload", "{\"title\":\"임의 업무\"}")));
            }, kind -> Json.object(), "호박", "호박아");
            assertThrows(IllegalArgumentException.class, () -> agent.chat("web", "bad-action", "문장만 다듬어줘.")); assertTrue(store.list("task").isEmpty());
        }
    }
    @Test void writingRequestCannotBeCombinedWithAMutation() throws Exception {
        Config config = new Config(temporary.resolve("none"), Map.of());
        try (Store store = new Store(temporary.resolve("mixed-writing.db"))) {
            WorkService service = new WorkService(store, new KoreaTime(Clock.systemUTC()), new JiraClient(config));
            Agent agent = new Agent(service, new Reports(service), prompt -> Json.obj("reply", "", "actions", List.of(Json.obj("type", "task", "payload", "{\"title\":\"임의 업무\"}"), Json.obj("type", "write_text", "payload", "{\"format\":\"message\"}"))), kind -> Json.object(), "호박", "호박아");
            assertThrows(IllegalArgumentException.class, () -> agent.chat("web", "mixed", "메시지 초안만 써줘."));
            assertTrue(store.list("task").isEmpty()); assertTrue(store.list("action").isEmpty());
        }
    }
}
