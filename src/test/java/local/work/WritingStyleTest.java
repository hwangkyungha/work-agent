package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
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
            assertTrue(received.get().startsWith("# 사람이 읽는 업무 글"));
            assertTrue(received.get().endsWith("현재 사용자 요청:\n" + request));
            assertTrue(Json.text(result, "text").contains("CT2612-486에 4h"));
            assertTrue(store.list("task").isEmpty()); assertTrue(store.list("worklog").isEmpty());
        }
    }
}
