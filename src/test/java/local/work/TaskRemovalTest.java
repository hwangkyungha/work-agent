package local.work;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskRemovalTest {
    @TempDir Path temporary;
    Store store; WorkService service;
    @BeforeEach void open() throws Exception {
        store = new Store(temporary.resolve("test.db"));
        service = new WorkService(store, new KoreaTime(Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC)), new JiraClient(new Config(temporary.resolve("none"), Map.of())));
    }
    @AfterEach void close() throws Exception { store.close(); }
    @Test void removesLocalTaskFromPlansButPreservesActivityAndWorklog() {
        ObjectNode task = service.task(Json.obj("title", "직접 작성한 업무")); String id = Json.text(task, "id");
        service.plan(Json.obj("date", "2026-11-06", "taskIds", List.of(id)));
        service.plan(Json.obj("date", "2026-11-09", "taskIds", List.of(id)));
        service.activity(Json.obj("title", "분석 기록", "jiraKey", "CT-12"));
        service.saveWorklog(Json.obj("items", List.of(Json.obj("jiraKey", "CT-12", "duration", "2h"))));
        ObjectNode result = service.removeTasks(Json.obj("taskIds", List.of(id)));
        assertEquals(1, result.path("deleted").asInt()); assertNull(store.get("task", id));
        assertTrue(service.currentPlan(LocalDate.of(2026, 11, 6)).path("taskIds").isEmpty());
        assertTrue(service.currentPlan(LocalDate.of(2026, 11, 9)).path("taskIds").isEmpty());
        assertEquals(1, store.list("activity").size()); assertEquals(1, store.list("worklog").size());
    }
    @Test void mixedBulkActionHidesJiraAndDeletesLocalTaskWithoutChangingJiraStatus() {
        store.put("task", Json.obj("id", "jira:CT-12", "title", "Jira 업무", "jiraKey", "CT-12", "status", "in-progress"));
        ObjectNode local = service.task(Json.obj("title", "로컬 업무"));
        ObjectNode result = service.removeTasks(Json.obj("taskIds", List.of("jira:CT-12", Json.text(local, "id"))));
        assertEquals(1, result.path("hidden").asInt()); assertEquals(1, result.path("deleted").asInt());
        ObjectNode jira = store.get("task", "jira:CT-12"); assertTrue(jira.path("hidden").asBoolean()); assertEquals("in-progress", Json.text(jira, "status"));
    }
    @Test void invalidBulkSelectionDoesNotPartiallyDeleteTasks() {
        ObjectNode task = service.task(Json.obj("title", "보존할 업무")); String id = Json.text(task, "id");
        assertThrows(IllegalArgumentException.class, () -> service.removeTasks(Json.obj("taskIds", List.of(id, "missing"))));
        assertNotNull(store.get("task", id));
    }
}
