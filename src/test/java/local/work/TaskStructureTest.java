package local.work;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskStructureTest {
    @TempDir Path temporary;
    Store store; WorkService service; JiraClient jira;
    @BeforeEach void open() throws Exception {
        store = new Store(temporary.resolve("test.db"));
        jira = new JiraClient(new Config(temporary.resolve("none"), Map.of()));
        service = new WorkService(store, new KoreaTime(Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC)), jira);
    }
    @AfterEach void close() throws Exception { store.close(); }
    String id(ObjectNode task) { return Json.text(task, "id"); }
    @Test void configuredTypesApplyToNewAndBulkTasksWhileOldValuesRemainEditable() throws Exception {
        Config configured = new Config(temporary.resolve("none"), Map.of("WORK_TYPES", "회의, 버그 수정,회의,,사용자 분류"));
        assertEquals(List.of("회의", "버그 수정", "사용자 분류"), configured.workTypes());
        String existing = id(service.task(Json.obj("title", "기존 업무", "workType", "기능")));
        WorkService changed = new WorkService(store, service.time, jira, configured.workTypes());
        assertEquals("기능", Json.text(changed.task(Json.obj("id", existing, "title", "제목 수정", "workType", "기능")), "workType"));
        assertThrows(IllegalArgumentException.class, () -> changed.task(Json.obj("title", "새 업무", "workType", "기능")));
        String meeting = id(changed.task(Json.obj("title", "미팅", "workType", "회의")));
        changed.groupTasks(Json.obj("taskIds", List.of(existing, meeting), "workType", "버그 수정"));
        assertEquals("버그 수정", Json.text(store.get("task", existing), "workType"));
        Config empty = new Config(temporary.resolve("none"), Map.of("WORK_TYPES", ""));
        assertTrue(empty.workTypes().isEmpty());
        assertTrue(new Config(temporary.resolve("none"), Map.of()).workTypes().containsAll(List.of("회의", "버그 수정")));
    }
    @Test void bulkClassificationCanChangeTypeWithoutClearingDifferentGroupsAndRejectsInvalidBatches() {
        String first=id(service.task(Json.obj("title","첫 업무","workGroup","첫 묶음","workType","기능"))),second=id(service.task(Json.obj("title","둘째 업무","workGroup","둘째 묶음","workType","운영")));
        service.groupTasks(Json.obj("taskIds",List.of(first,second),"workType","품질·성능"));
        assertEquals("첫 묶음",Json.text(store.get("task",first),"workGroup"));assertEquals("둘째 묶음",Json.text(store.get("task",second),"workGroup"));
        for(String task:List.of(first,second))assertEquals("품질·성능",Json.text(store.get("task",task),"workType"));
        assertThrows(IllegalArgumentException.class,()->service.groupTasks(Json.obj("taskIds",List.of(first,second),"workType","없는 성격","workGroup","잘못된 변경")));
        assertThrows(IllegalArgumentException.class,()->service.groupTasks(Json.obj("taskIds",List.of(first,"missing"),"workType","운영")));
        assertEquals("품질·성능",Json.text(store.get("task",first),"workType"));assertEquals("첫 묶음",Json.text(store.get("task",first),"workGroup"));
        service.groupTasks(Json.obj("taskIds",List.of(first,second),"workGroup","공통 묶음","workType",""));
        assertEquals("공통 묶음",Json.text(store.get("task",second),"workGroup"));assertEquals("",Json.text(store.get("task",second),"workType"));
    }
    @Test void localTasksKeepGroupAndPredecessorsWithoutChangingDailyPlan() {
        ObjectNode first = service.task(Json.obj("title", "출력 구조 변경", "workGroup", " CI 리포트 개선 "));
        ObjectNode next = service.task(Json.obj("title", "화면 구현", "workGroup", "CI 리포트 개선", "predecessorIds", List.of(id(first), id(first))));
        assertEquals("CI 리포트 개선", Json.text(first, "workGroup"));
        assertEquals(List.of(id(first)), TaskStructure.predecessors(next));
        assertTrue(service.planned(service.time.today()).isEmpty());
        service.task(Json.obj("id", id(first), "status", "done"));
        assertEquals(List.of(id(first)), TaskStructure.predecessors(store.get("task", id(next))));
    }
    @Test void invalidOrCyclicDependenciesDoNotChangeSavedTask() {
        ObjectNode first = service.task(Json.obj("title", "먼저 할 일"));
        ObjectNode next = service.task(Json.obj("title", "다음 일", "predecessorIds", List.of(id(first))));
        for (String invalid : List.of(id(first), id(next), "missing")) {
            assertThrows(IllegalArgumentException.class, () -> service.task(Json.obj("id", id(first), "predecessorIds", List.of(invalid))));
            assertFalse(store.get("task", id(first)).has("predecessorIds"));
        }
    }
    @Test void bulkGroupingIsAtomicAndCanBeCleared() {
        ObjectNode first = service.task(Json.obj("title", "개발")), next = service.task(Json.obj("title", "검증"));
        assertThrows(IllegalArgumentException.class, () -> service.groupTasks(Json.obj("taskIds", List.of(id(first), "missing"), "workGroup", "기능 개선")));
        assertFalse(store.get("task", id(first)).has("workGroup"));
        service.groupTasks(Json.obj("taskIds", List.of(id(first), id(next)), "workGroup", "기능 개선"));
        assertEquals("기능 개선", Json.text(store.get("task", id(next)), "workGroup"));
        service.groupTasks(Json.obj("taskIds", List.of(id(first), id(next)), "workGroup", ""));
        assertEquals("", TaskStructure.group(store.get("task", id(first))));
    }
    @Test void jiraParentAndBlockedByAreImportedAndManualOverridesSurviveSync() {
        ObjectNode linked = service.task(Json.obj("title", "화면 구현", "jiraKey", "CTS-2"));
        ObjectNode previous = service.task(Json.obj("title", "구조 변경", "jiraKey", "CTS-1"));
        ObjectNode remote = jira.summarize(Json.obj("key", "CTS-2", "fields", Json.obj("summary", "화면 구현", "status", Json.obj("name", "To Do", "statusCategory", Json.obj("key", "new")),
                "parent", Json.obj("key", "CTS-100", "fields", Json.obj("summary", "리포트 개선")),
                "issuelinks", List.of(Json.obj("type", Json.obj("inward", "is blocked by"), "inwardIssue", Json.obj("key", "CTS-1", "fields", Json.obj("summary", "구조 변경"))),
                        Json.obj("type", Json.obj("inward", "relates to"), "inwardIssue", Json.obj("key", "CTS-3")),
                        Json.obj("type", Json.obj("inward", "is blocked by", "outward", "blocks"), "outwardIssue", Json.obj("key", "CTS-4")),
                        Json.obj("type", Json.obj("inward", "is depended on by", "outward", "depends on"), "outwardIssue", Json.obj("key", "CTS-5"))))));
        ObjectNode updated = jira.updateTask(service, linked, remote);
        assertEquals("CTS-100 리포트 개선", TaskStructure.group(updated));
        assertEquals(List.of("jira:CTS-1", "jira:CTS-5"), TaskStructure.predecessors(updated));
        assertNotNull(store.get("issue", "CTS-2"));
        service.task(Json.obj("id", id(linked), "workGroup", "이번 릴리스", "predecessorIds", List.of(id(previous))));
        updated = jira.updateTask(service, linked, remote);
        assertEquals("이번 릴리스", TaskStructure.group(updated));
        assertEquals(List.of(id(previous)), TaskStructure.predecessors(updated));
        ObjectNode current = updated;
        assertThrows(IllegalArgumentException.class, () -> service.task(Json.obj("id", id(previous), "predecessorIds", List.of(id(current)))));
    }
    @Test void deletingLocalPredecessorRemovesDanglingReferenceButHidingDoesNot() {
        ObjectNode local = service.task(Json.obj("title", "준비")), remote = service.task(Json.obj("title", "검토", "jiraKey", "CTS-1"));
        ObjectNode next = service.task(Json.obj("title", "배포", "predecessorIds", List.of(id(local), id(remote))));
        service.removeTasks(Json.obj("taskIds", List.of(id(local), id(remote))));
        assertEquals(List.of(id(remote)), TaskStructure.predecessors(store.get("task", id(next))));
        assertTrue(store.get("task", id(remote)).path("hidden").asBoolean());
    }
}
