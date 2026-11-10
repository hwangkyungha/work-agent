package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ScreenshotAnimations;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "work.ui.test", matches = "true")
class WebUiTest {
    Playwright playwright; Browser browser; Page page; ObjectNode state;
    List<JsonNode> removed = new ArrayList<>();
    List<JsonNode> updates = new ArrayList<>(), groupUpdates = new ArrayList<>(), statusUpdates = new ArrayList<>(), planUpdates = new ArrayList<>();
    List<JsonNode> savedWorklogs = new ArrayList<>(); boolean worklogSaveFails;
    List<JsonNode> favoriteUpdates = new ArrayList<>(); int worklogSyncCalls; boolean worklogSyncFails;
    List<ObjectNode> reviewJobs = new ArrayList<>(); List<JsonNode> reviewRequests = new ArrayList<>(); int reviewRefreshes, pendingSaves;
    List<String> acceptedFollowups=new ArrayList<>();
    List<JsonNode> workDayUpdates=new ArrayList<>(),addedPastWork=new ArrayList<>();
    List<ObjectNode> pastWorkCandidates=new ArrayList<>();
    List<JsonNode> workspaceUpdates=new ArrayList<>(),referenceUpdates=new ArrayList<>();
    static final String ORIGIN = "http://127.0.0.1:18765";
    ObjectNode task(String id, String key, String title) { return Json.obj("id", id, "jiraKey", key, "title", title, "status", "todo", "important", false, "urgent", false, "product", "개발", "due", "", "hidden", false, "createdAt", "2026-11-06T00:00:00Z"); }
    @BeforeEach void open() {
        state = Json.obj("date", "2026-11-06", "tasks", List.of(task("local", "", "한글 검색 확인"), task("jira:CT-42", "CT-42", "CI 보고서 검토")), "hiddenTasks", List.of(),
                "plan", List.of(task("local", "", "한글 검색 확인")), "planSaved", true, "planSort", "manual", "planVersion", 1,
                "jobs", List.of(), "meetings", List.of(), "yesterdayCompleted", List.of(), "followups", List.of(), "todayGroups", List.of(), "sync", List.of(Json.obj("id","worklogs:2026-11-02","at",Instant.now().toString(),"ok",true)), "dailyNote", "", "dailyDetails", Json.object(), "worklogs", List.of(), "activityGroups", List.of(), "registeredWorklogs", List.of(), "worklogFavorites", List.of(), "recentWorklogIssues", List.of());
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        browser = playwright.chromium().launch(); page = browser.newPage(new Browser.NewPageOptions().setViewportSize(1440, 1000));
        page.route("**/*", route -> {
            String path = URI.create(route.request().url()).getPath(); String content = "{}", type = "application/json";
            switch (path) {
                case "/", "/app.js", "/style.css" -> {
                    String resource = path.equals("/") ? "/web/index.html" : "/web" + path;
                    try (InputStream input = WebUiTest.class.getResourceAsStream(resource)) { content = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8); }
                    catch (Exception e) { throw new RuntimeException(e); }
                    type = path.equals("/") ? "text/html; charset=utf-8" : path.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/css; charset=utf-8";
                }
                case "/api/state" -> content = Json.write(state);
                case "/api/settings" -> content = Json.write(Json.obj("agentName", "호박", "agentCallName", "호박아", "slackConnected", true, "jiraConfigured", true, "engine", "codex"));
                case "/api/reviews" -> content = Json.write(Json.obj("configured", true, "automatic", true, "jobs", reviewJobs, "warnings", List.of(), "workspaces", List.of("team"), "repositoryCount", 48));
                case "/api/reviews/refresh" -> reviewRefreshes++;
                case "/api/reviews/workspace" -> workspaceUpdates.add(Json.read(route.request().postData()));
                case "/api/reviews/repositories" -> referenceUpdates.add(Json.read(route.request().postData()));
                case "/api/reviews/request" -> reviewRequests.add(Json.read(route.request().postData()));
                case "/api/reviews/edit" -> {
                    JsonNode payload = Json.read(route.request().postData());
                    ((ObjectNode) reviewJobs.getFirst().path("findings").get(0)).put("comment", Json.required(payload, "text"));
                }
                case "/api/reviews/save" -> { pendingSaves++; reviewJobs.getFirst().put("status", "saved"); }
                case "/api/tasks/group" -> {
                    JsonNode payload = Json.read(route.request().postData()); groupUpdates.add(payload);
                    for (JsonNode task : state.path("tasks")) for (JsonNode id : payload.path("taskIds")) if (id.asText().equals(Json.text(task, "id"))) {if(payload.has("workGroup"))((ObjectNode)task).set("workGroup",payload.get("workGroup"));if(payload.has("workType"))((ObjectNode)task).set("workType",payload.get("workType"));}
                    refreshPlan(); content = Json.write(Json.obj("count", payload.path("taskIds").size()));
                }
                case "/api/tasks" -> {
                    JsonNode payload = Json.read(route.request().postData()); updates.add(payload);
                    for (JsonNode task : state.path("tasks")) if (Json.text(task, "id").equals(Json.text(payload, "id"))) payload.fields().forEachRemaining(field -> ((ObjectNode) task).set(field.getKey(), field.getValue()));
                    refreshPlan();
                }
                case "/api/tasks/transitions" -> content = Json.write(Json.obj("current", Json.obj("jiraStatus", "In Progress"), "transitions", List.of(Json.obj("id", "31", "name", "Complete", "to", Json.obj("name", "Done")))));
                case "/api/tasks/status" -> {
                    JsonNode payload = Json.read(route.request().postData()); statusUpdates.add(payload);
                    for (JsonNode task : state.path("tasks")) if (Json.text(task, "id").equals(Json.text(payload, "id"))) { ((ObjectNode) task).put("status", payload.has("transitionId") ? "done" : Json.text(payload, "status")); ((ObjectNode) task).put("jiraStatus", payload.has("transitionId") ? "Done" : ""); }
                    refreshPlan();
                }
                case "/api/plan" -> {
                    JsonNode payload = Json.read(route.request().postData()); planUpdates.add(payload);
                    ArrayNode plan = Json.MAPPER.createArrayNode(); for (JsonNode id : payload.path("taskIds")) for (JsonNode task : state.path("tasks")) if (id.asText().equals(Json.text(task, "id"))) plan.add(task);
                    state.set("plan", plan); state.put("planSort", Json.text(payload, "sort")); state.put("planVersion", state.path("planVersion").asInt() + 1);
                }
                case "/api/plan/items" -> {
                    JsonNode payload=Json.read(route.request().postData());planUpdates.add(payload);
                    ArrayNode plan=(ArrayNode)state.path("plan");int before=plan.size();
                    for(JsonNode id:payload.path("taskIds")) for(JsonNode task:state.path("tasks")) if(id.asText().equals(Json.text(task,"id")) && plan.findValuesAsText("id").stream().noneMatch(value->value.equals(id.asText()))) plan.add(task);
                    content=Json.write(Json.obj("added",plan.size()-before));
                }
                case "/api/work-days/candidates" -> content=Json.write(pastWorkCandidates);
                case "/api/work-days/add" -> {
                    JsonNode body=Json.read(route.request().postData());addedPastWork.add(body);
                    ObjectNode source=pastWorkCandidates.stream().filter(row->Json.text(row,"workId").equals(Json.text(body,"workId"))).findFirst().orElse(null);
                    if(source==null)for(JsonNode task:state.path("tasks"))if(Json.text(task,"id").equals(Json.text(body,"taskId")))source=Json.obj("workId",Json.text(task,"id"),"taskId",Json.text(task,"id"),"title",Json.text(task,"title"),"jiraKey",Json.text(task,"jiraKey"));
                    if(source==null)source=Json.obj("workId","activity:manual","title",Json.text(body,"title"));
                    ObjectNode row=source.deepCopy();row.put("date",Json.text(body,"date")).put("outcome",Json.text(body,"outcome")).put("confirmed",true).put("observed",true);
                    if(!state.has("previousWork"))state.set("previousWork",Json.MAPPER.createArrayNode());((ArrayNode)state.path("previousWork")).add(row);
                }
                case "/api/work-days" -> { JsonNode body=Json.read(route.request().postData());workDayUpdates.add(body);for(JsonNode row:state.path("previousWork"))if(Json.text(row,"workId").equals(Json.text(body,"workId")))((ObjectNode)row).put("outcome",Json.text(body,"outcome")); }
                case "/api/followups/dismiss" -> { String id=Json.required(Json.read(route.request().postData()),"id");ArrayNode remaining=Json.MAPPER.createArrayNode();for(JsonNode row:state.path("followups"))if(!id.equals(Json.text(row,"id")))remaining.add(row);state.set("followups",remaining); }
                case "/api/followups/accept" -> {
                    String id=Json.required(Json.read(route.request().postData()),"id");acceptedFollowups.add(id);
                    ObjectNode accepted=task("accepted","","Draft PR 검토");((ArrayNode)state.path("tasks")).add(accepted);((ArrayNode)state.path("plan")).add(accepted);
                    for(JsonNode row:state.path("draftDigest").path("prs")) {
                        if(id.equals(Json.text(row.path("reviewSuggestion"),"id")))((ObjectNode)row.path("reviewSuggestion")).put("acceptedTaskId","accepted");
                        for(JsonNode suggestion:row.path("suggestions"))if(id.equals(Json.text(suggestion,"id")))((ObjectNode)suggestion).put("acceptedTaskId","accepted");
                    }
                }
                case "/api/draft-results/reviewed" -> {
                    JsonNode input=Json.read(route.request().postData());
                    for(JsonNode row:state.path("draftDigest").path("prs"))if(Json.text(row,"id").equals(Json.text(input,"id")))((ObjectNode)row).put("reviewed",input.path("reviewed").asBoolean());
                }
                case "/api/worklogs" -> {
                    JsonNode payload = Json.read(route.request().postData()); savedWorklogs.add(payload);
                    if (worklogSaveFails) { route.fulfill(new Route.FulfillOptions().setStatus(503).setContentType("application/json").setBody("{\"error\":\"저장 연결 오류\"}")); return; }
                    ArrayNode items = Json.MAPPER.createArrayNode(); long seconds = 0;
                    for (JsonNode item : payload.path("items")) {
                        String duration = Json.text(item, "duration"); int value = duration.isBlank() ? 0 : WorkDuration.seconds(duration); seconds += value;
                        items.add(Json.obj("jiraKey", Json.text(item, "jiraKey"), "seconds", value, "comment", Json.text(item, "comment"), "workId", Json.text(item,"workId"), "taskId", Json.text(item,"taskId"), "title", Json.text(item,"title"), "marker", "saved-" + Json.text(item, "jiraKey"), "state", "draft"));
                    }
                    ArrayNode days = Json.MAPPER.createArrayNode(); for (JsonNode day : state.path("worklogs")) if (!Json.text(day, "date").equals(Json.text(payload, "date"))) days.add(day);
                    ObjectNode saved = Json.obj("date", Json.text(payload, "date"), "items", items, "totalSeconds", seconds); days.add(saved); state.set("worklogs", days); content = Json.write(saved);
                }
                case "/api/worklogs/sync" -> {
                    worklogSyncCalls++;
                    if(worklogSyncFails){route.fulfill(new Route.FulfillOptions().setStatus(502).setContentType("application/json").setBody("{\"error\":\"Jira 연결 오류\"}"));return;}
                    state.set("sync",Json.MAPPER.valueToTree(List.of(Json.obj("id","worklogs:2026-11-02","at",Instant.now().toString(),"ok",true))));
                    content=Json.write(Json.obj("count",state.path("registeredWorklogs").size()));
                }
                case "/api/worklogs/favorite" -> {
                    JsonNode payload=Json.read(route.request().postData());favoriteUpdates.add(payload);
                    ArrayNode favorites=Json.MAPPER.createArrayNode();String key=Json.text(payload,"jiraKey");
                    for(JsonNode row:state.path("worklogFavorites"))if(!Json.text(row,"jiraKey").equals(key))favorites.add(row);
                    if(payload.path("favorite").asBoolean(true))favorites.add(Json.obj("jiraKey",key,"title","주간 회의"));state.set("worklogFavorites",favorites);
                }
                case "/api/tasks/remove" -> {
                    JsonNode payload = Json.read(route.request().postData()); removed.add(payload);
                    ArrayNode tasks = Json.MAPPER.createArrayNode(); ArrayNode hidden = (ArrayNode) state.path("hiddenTasks");
                    int deleted = 0, count = 0;
                    for (JsonNode task : state.path("tasks")) {
                        boolean selected = false; for (JsonNode id : payload.path("taskIds")) if (id.asText().equals(Json.text(task, "id"))) selected = true;
                        if (!selected) tasks.add(task);
                        else if (Json.text(task, "jiraKey").isBlank()) deleted++;
                        else { ((ObjectNode) task).put("hidden", true); hidden.add(task); count++; }
                    }
                    state.set("tasks", tasks); content = Json.write(Json.obj("deleted", deleted, "hidden", count));
                }
                case "/api/report" -> content = Json.write(Json.obj("text", "2026-11-06 데일리 브리핑\n진행한 일 (2026-11-05 목요일)\n- 보고서 오류 확인\n오늘 할 일\n- CI 보고서 검토"));
            }
            route.fulfill(new Route.FulfillOptions().setContentType(type).setBody(content));
        });
        page.navigate(ORIGIN); page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("오늘 할 일").setExact(true)).waitFor();
    }
    @AfterEach void close() { if (browser != null) browser.close(); if (playwright != null) playwright.close(); }
    void refreshPlan() {
        ArrayNode plan = Json.MAPPER.createArrayNode();
        for (JsonNode old : state.path("plan")) for (JsonNode task : state.path("tasks")) if (Json.text(old, "id").equals(Json.text(task, "id"))) plan.add(task);
        state.set("plan", plan);
    }
    void structuredTasks() {
        ObjectNode first = task("jira:CT-41", "CT-41", "출력 구조 정리").put("workGroup", "CI 리포트 개선").put("status", "done");
        ObjectNode second = task("jira:CT-42", "CT-42", "결과 화면 구현").put("workGroup", "CI 리포트 개선").put("status", "in-progress"); second.set("predecessorIds", Json.MAPPER.valueToTree(List.of("jira:CT-41")));
        ObjectNode third = task("jira:CT-43", "CT-43", "통합 검증").put("workGroup", "CI 리포트 개선"); third.set("predecessorIds", Json.MAPPER.valueToTree(List.of("jira:CT-42")));
        ObjectNode local = task("local", "", "팀 회의 준비").put("workGroup", "팀 운영");
        state.set("tasks", Json.MAPPER.valueToTree(List.of(third, second, first, local, task("other", "", "워크로그 정리"))));
        state.set("plan", Json.MAPPER.valueToTree(List.of(second, local)));
        page.reload(); page.locator("#today-list [data-today-id='jira:CT-42']").waitFor();
    }
    @Test void koreanCompositionKeepsOriginalInputAndFiltersAfterCommit() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("전체 할 일").setExact(true)).click();
        page.locator("#task-search").focus();
        page.evaluate("""
            () => {const field=document.querySelector('#task-search');window.originalSearch=field;field.dispatchEvent(new CompositionEvent('compositionstart',{bubbles:true}));field.value='ㅎ';field.dispatchEvent(new InputEvent('input',{bubbles:true,isComposing:true,inputType:'insertCompositionText'}));}
            """);
        assertTrue((Boolean) page.evaluate("window.originalSearch.isConnected && window.originalSearch===document.activeElement"), "한글 조합 중 입력란이 교체되면 안 됩니다.");
        page.evaluate("""
            () => {const field=window.originalSearch;field.value='한글';field.dispatchEvent(new CompositionEvent('compositionend',{bubbles:true,data:'한글'}));field.dispatchEvent(new InputEvent('input',{bubbles:true,isComposing:false}));}
            """);
        assertEquals("한글", page.locator("#task-search").inputValue());
        assertEquals(1, page.locator("#all-task-list .selectable-task").count());
        assertTrue(page.locator("#all-task-list").innerText().contains("한글 검색 확인"));
        assertTrue((Boolean) page.evaluate("window.originalSearch===document.activeElement"));
    }
    @Test void localAndJiraRowsExposeDeleteAndHideAndBulkActionExplainsBoth() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("전체 할 일").setExact(true)).click();
        page.locator(".task-menu").first().locator("summary").click();
        assertEquals("삭제", page.locator("[data-hide-task='local']").innerText());
        page.keyboard().press("Escape");
        assertFalse(page.locator("[data-hide-task='local']").isVisible());
        page.locator(".task-menu").nth(1).locator("summary").click();
        assertEquals("숨기기", page.locator("[data-hide-task='jira:CT-42']").innerText());
        page.locator("#select-all-tasks").check();
        assertTrue(page.locator("#bulk-hide").innerText().contains("삭제"));
        page.onDialog(Dialog::accept); page.locator("#bulk-hide").click();
        page.waitForCondition(() -> !removed.isEmpty());
        assertEquals(2, removed.getFirst().path("taskIds").size());
    }
    @Test void cancellingLocalDeletionKeepsTaskAndSendsNoRequest() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("전체 할 일").setExact(true)).click();
        page.locator(".task-menu").first().locator("summary").click();
        page.onDialog(Dialog::dismiss); page.locator("[data-hide-task='local']").click();
        assertTrue(removed.isEmpty()); assertEquals(1, page.locator("[data-hide-task='local']").count());
    }
    @Test void jiraHideDoesNotAskToDeleteAndDailyNoteFormHasOptionalSections() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("전체 할 일").setExact(true)).click();
        page.locator(".task-menu").nth(1).locator("summary").click();
        page.locator("[data-hide-task='jira:CT-42']").click(); page.waitForCondition(() -> removed.size() == 1);
        assertEquals("jira:CT-42", removed.getFirst().path("taskIds").get(0).asText());
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("오늘").setExact(true)).click();
        page.locator("[data-open='daily']").click();
        for (String name : List.of("problems", "help", "decisions", "text")) assertEquals(1, page.locator("#record-form textarea[name='" + name + "']:not([required])").count());
    }
    @Test void groupsShowPrecedenceAndTodayContainsOnlyChosenTasks() {
        structuredTasks();
        assertEquals(2, page.locator("#today-list .today-task").count());
        assertEquals(0, page.locator("#today-list [data-today-id='jira:CT-43']").count());
        assertTrue(page.locator("#today-list").innerText().contains("완료 1 / 전체 3 · 오늘 1건"));
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("전체 할 일").setExact(true)).click();
        page.locator("[data-filter='all']").click();
        Locator group = page.locator(".work-group").first();
        assertEquals(List.of("jira:CT-41", "jira:CT-42", "jira:CT-43"), group.locator(".task-heading [data-edit-task]").evaluateAll("els=>els.map(e=>e.dataset.editTask)"));
        assertTrue(group.innerText().contains("선행 작업 대기")); assertTrue(group.innerText().contains("선행 완료"));
        group.locator(":scope > summary").click(); assertFalse(group.locator(".work-group-list").isVisible());
    }
    @Test void bulkGroupingAndTaskEditorSendOnlyExplicitStructureChanges() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("전체 할 일").setExact(true)).click();
        page.locator("#select-all-tasks").check(); page.locator("#bulk-group").click();
        page.locator("#field-workGroup").fill("검색 개선"); page.locator("#group-form [type='submit']").click();
        page.waitForCondition(() -> !groupUpdates.isEmpty() && !page.locator("#modal").isVisible() && page.locator(".work-group-name").count() == 1);
        assertEquals(2, groupUpdates.getFirst().path("taskIds").size());
        page.locator(".task-heading [data-edit-task='local']").click();
        page.locator(".predecessor-picker > summary").click(); page.locator("[name='predecessorIds'][value='jira:CT-42']").check();
        page.locator("#record-form [type='submit']").click(); page.waitForCondition(() -> !updates.isEmpty());
        assertEquals("jira:CT-42", updates.getFirst().path("predecessorIds").get(0).asText());
        assertFalse(updates.getFirst().has("workGroup"), "편집하지 않은 자동/기존 묶음은 덮어쓰지 않습니다.");
        assertTrue(statusUpdates.isEmpty());
    }
    @Test void compactStatusUsesActualJiraTransitionAndUpdatesDependentWaitState() {
        structuredTasks(); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("전체 할 일").setExact(true)).click();
        page.locator("[data-open-status='jira:CT-42']").click(); page.locator("#status-choice").selectOption("31");
        page.waitForCondition(() -> !statusUpdates.isEmpty() && !page.locator("#modal").isVisible() && page.locator("#all-task-list [data-open-status='jira:CT-42']").count() == 0);
        assertEquals("31", Json.text(statusUpdates.getFirst(), "transitionId")); assertFalse(statusUpdates.getFirst().has("status"));
        assertFalse(page.locator("#all-task-list .dependency-line").innerText().contains("선행 작업 대기"));
    }
    @Test void draggingInsideGroupAndFlatPriorityOrderBothSave() {
        structuredTasks();
        ArrayNode plan = Json.MAPPER.createArrayNode(); for (JsonNode task : state.path("tasks")) if (List.of("jira:CT-42", "jira:CT-43", "local").contains(Json.text(task, "id"))) plan.add(task);
        state.set("plan", plan); page.reload(); page.locator("#today-list [data-today-id='jira:CT-43']").waitFor();
        BoundingBox source = page.locator("[data-drag-task='jira:CT-42']").boundingBox(); BoundingBox target = page.locator("[data-today-id='jira:CT-43']").boundingBox();
        page.mouse().move(source.x + source.width / 2, source.y + source.height / 2); page.mouse().down(); page.mouse().move(target.x + 40, target.y + 2, new Mouse.MoveOptions().setSteps(8)); page.mouse().up();
        page.waitForCondition(() -> !planUpdates.isEmpty() && (Boolean) page.evaluate("!savingPlan")); assertEquals("jira:CT-42", planUpdates.getFirst().path("taskIds").get(0).asText());
        page.locator("#group-today").uncheck(); page.locator("[data-drag-task='jira:CT-42']").focus(); page.keyboard().press("Alt+ArrowDown");
        page.waitForCondition(() -> planUpdates.size() == 2); assertEquals("jira:CT-43", planUpdates.get(1).path("taskIds").get(0).asText());
    }
    @Test void screenshotsAndResponsiveLayout() throws Exception {
        String output = System.getProperty("work.ui.screenshots", "");
        if (output.isBlank()) return;
        Path directory = Path.of(output); Files.createDirectories(directory);
        structuredTasks();
        page.screenshot(new Page.ScreenshotOptions().setPath(directory.resolve("work-agent-today.png")).setFullPage(true).setAnimations(ScreenshotAnimations.DISABLED));
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("전체 할 일").setExact(true)).click();
        page.waitForCondition(() -> "page".equals(page.locator("[data-view='plan']").getAttribute("aria-current")));
        page.screenshot(new Page.ScreenshotOptions().setPath(directory.resolve("work-agent-tasks.png")).setFullPage(true).setAnimations(ScreenshotAnimations.DISABLED));
        page.setViewportSize(390, 844);
        assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), "모바일 화면이 가로로 넘치면 안 됩니다.");
        page.screenshot(new Page.ScreenshotOptions().setPath(directory.resolve("work-agent-mobile.png")).setFullPage(true).setAnimations(ScreenshotAnimations.DISABLED));
    }
    @Test void unfinishedWorklogRowsSurviveDayChangesNavigationAndReload() {
        page.locator("[data-view='worklog']").click(); page.locator("#add-worklog").click();
        page.locator(".log-key").fill("WOR-1517"); page.locator(".log-duration").fill("2h");
        page.locator("#add-worklog").click(); page.locator(".log-key").nth(1).fill("WOR-1431");
        page.locator("[data-log-day='2026-11-03']").click(); page.locator("[data-log-day='2026-11-06']").click();
        assertEquals(2, page.locator("#worklog-rows .log-key").count());
        assertEquals("WOR-1517", page.locator(".log-key").first().inputValue()); assertEquals("2h", page.locator(".log-duration").first().inputValue());
        assertEquals("WOR-1431", page.locator(".log-key").nth(1).inputValue()); assertEquals("", page.locator(".log-duration").nth(1).inputValue());
        page.locator("[data-view='today']").click(); page.locator("[data-view='worklog']").click();
        assertEquals(2, page.locator(".log-key").count());
        page.reload(); page.locator("[data-view='worklog']").click();
        assertEquals("WOR-1431", page.locator(".log-key").nth(1).inputValue());
    }
    @Test void invalidTimeIsExplainedBelowItsInputAndDoesNotReplaceTotal() {
        page.locator("[data-view='worklog']").click(); page.locator("#add-worklog").click();
        page.locator(".log-key").fill("CT-42"); page.locator(".log-duration").fill("4");
        page.locator("#add-worklog").click(); page.locator(".log-key").nth(1).fill("WOR-1517"); page.locator(".log-duration").nth(1).fill("1h");
        assertEquals("true", page.locator(".log-duration").first().getAttribute("aria-invalid"));
        assertTrue(page.locator(".worklog-time").first().locator(".duration-error").isVisible());
        assertEquals("12px", page.locator(".duration-error").first().evaluate("e=>getComputedStyle(e).fontSize"));
        assertEquals("1h", page.locator("#duration-total").textContent());
    }
    @Test void worklogListSavesWithoutTimeAndCanBeRestoredFromServer() {
        page.locator("[data-view='worklog']").click(); page.locator("#add-worklog").click(); page.locator(".log-key").fill("WOR-1517");
        page.locator("#worklog-form [type='submit']").click();
        page.waitForCondition(() -> !savedWorklogs.isEmpty() && page.locator("#worklog-save-state").textContent().equals("저장된 초안"));
        assertEquals("", Json.text(savedWorklogs.getFirst().path("items").get(0), "duration"));
        assertTrue(page.locator("#post-worklog").isDisabled());
        assertNull(page.evaluate("localStorage.getItem('work-agent.worklog-editor:2026-11-06')"));
        page.reload(); page.locator("[data-view='worklog']").click(); assertEquals("WOR-1517", page.locator(".log-key").inputValue());
        assertEquals("", page.locator(".log-duration").inputValue());
    }
    @Test void failedWorklogSaveKeepsUnfinishedInputs() {
        worklogSaveFails = true;
        page.locator("[data-view='worklog']").click(); page.locator("#add-worklog").click(); page.locator(".log-key").fill("WOR-1431");
        page.locator("#worklog-form [type='submit']").click(); page.locator("#notice").filter(new Locator.FilterOptions().setHasText("저장 연결 오류")).waitFor();
        page.reload(); page.locator("[data-view='worklog']").click(); assertEquals("WOR-1431", page.locator(".log-key").inputValue());
    }
    @Test void serverPostedRecordReplacesStaleLocalEdit() {
        ObjectNode item = Json.obj("jiraKey", "CT-42", "seconds", 3600, "comment", "검토", "marker", "same-record", "state", "draft");
        state.set("worklogs", Json.MAPPER.valueToTree(List.of(Json.obj("date", "2026-11-06", "items", List.of(item)))));
        page.reload(); page.locator("[data-view='worklog']").click(); page.locator(".log-duration").fill("2h");
        ((ObjectNode) state.path("worklogs").get(0).path("items").get(0)).put("state", "posted");
        page.reload(); page.locator("[data-view='worklog']").click();
        assertEquals("1h", page.locator(".log-duration").inputValue()); assertTrue(page.locator(".log-duration").isDisabled());assertFalse(page.locator(".worklog-input-row[data-state='posted']").isVisible());
    }
    @Test void favoritesAndRecentIssuesAddToDraftWithoutAffectingTaskList() {
        state.set("recentWorklogIssues",Json.MAPPER.valueToTree(List.of(Json.obj("jiraKey","WOR-1431","title","실 주간 회의","lastDate","2026-11-05"))));
        page.reload();page.locator("[data-view='worklog']").click();
        page.locator("#favorite-issue-key").fill("WOR-1517");page.locator("#worklog-favorite-form [type='submit']").click();
        page.locator("[data-add-log-issue='WOR-1517']").waitFor();page.locator("[data-add-log-issue='WOR-1517']").click();page.locator(".log-duration").fill("2h");
        page.locator("[data-add-log-issue='WOR-1431']").click();page.locator("[data-add-log-issue='WOR-1431']").click();
        assertEquals(2,page.locator(".log-key").count());assertEquals(2,state.path("tasks").size());
        page.locator("[data-log-favorite='WOR-1517']").click();page.waitForCondition(()->favoriteUpdates.size()==2);
        assertFalse(favoriteUpdates.get(1).path("favorite").asBoolean());assertEquals("2h",page.locator(".log-duration").first().inputValue());
    }
    @Test void syncedRegisteredTimeIsReadOnlyAndCombinedWithUnfinishedDraft() {
        state.set("registeredWorklogs",Json.MAPPER.valueToTree(List.of(Json.obj("date","2026-11-06","jiraKey","CT-1","title","개발","seconds",14400,"source","work-agent","confirmed",true),Json.obj("date","2026-11-06","jiraKey","WOR-1517","title","회의","seconds",14400,"source","jira","confirmed",true))));
        page.reload();page.locator("[data-view='worklog']").click();
        assertEquals(2,page.locator(".registered-log").count());assertEquals("8h",page.locator(".registered-heading>strong").textContent());
        page.locator("#add-worklog").click();page.locator(".log-key").fill("CT-2");page.locator(".log-duration").fill("1h");
        page.locator("#sync-worklogs").click();page.waitForCondition(()->worklogSyncCalls==1&&(Boolean)page.evaluate("!syncingWorklogs"));
        assertEquals("1h",page.locator(".log-duration").inputValue());assertEquals("9h",page.locator("#combined-worklog-total").textContent());
        assertEquals(0,page.locator(".registered-log input").count());
    }
    @Test void autoSyncRunsOnFirstOpenAndFailureKeepsDraftAndPreviousSnapshot() {
        state.set("sync",Json.MAPPER.createArrayNode());worklogSyncFails=true;
        page.reload();
        page.locator("[data-view='worklog']").click();page.waitForCondition(()->worklogSyncCalls==1&&(Boolean)page.evaluate("!syncingWorklogs"));
        page.locator("#add-worklog").click();page.locator(".log-key").fill("WOR-1517");page.locator(".log-duration").fill("3h");
        page.locator("#sync-worklogs").click();page.waitForCondition(()->worklogSyncCalls==2&&(Boolean)page.evaluate("!syncingWorklogs"));
        assertEquals("3h",page.locator(".log-duration").inputValue());assertEquals(2,worklogSyncCalls);
    }
    @Test void worklogWarningScreenshotAndResponsiveLayout() throws Exception {
        String output = System.getProperty("work.ui.worklog.screenshots", ""); if (output.isBlank()) return;
        Path directory = Path.of(output); Files.createDirectories(directory);
        state.set("registeredWorklogs",Json.MAPPER.valueToTree(List.of(Json.obj("date","2026-11-06","jiraKey","CT2612-40","title","요구사항 테스트 연결","seconds",14400,"source","work-agent","confirmed",true),Json.obj("date","2026-11-06","jiraKey","WOR-1517","title","플랫폼 주간 회의","seconds",14400,"source","jira","confirmed",true))));
        state.set("worklogFavorites",Json.MAPPER.valueToTree(List.of(Json.obj("jiraKey","WOR-1517","title","플랫폼 주간 회의"),Json.obj("jiraKey","WOR-1431","title","실 주간 회의"))));
        state.set("recentWorklogIssues",Json.MAPPER.valueToTree(List.of(Json.obj("jiraKey","CT2612-465","title","CI용 커맨드 구현","lastDate","2026-11-05"))));
        page.reload();
        page.locator("[data-view='worklog']").click(); page.locator("#add-worklog").click();
        page.locator(".log-key").fill("CT2612-486"); page.locator(".log-duration").fill("4");
        page.locator("#add-worklog").click(); page.locator(".log-key").nth(1).fill("WOR-1517"); page.locator(".log-duration").nth(1).fill("1h");
        page.locator("#add-worklog").click(); page.locator(".log-key").nth(2).fill("WOR-1431");
        page.screenshot(new Page.ScreenshotOptions().setPath(directory.resolve("work-agent-worklog.png")).setFullPage(true).setAnimations(ScreenshotAnimations.DISABLED));
        page.setViewportSize(390, 844);
        assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"));
        page.screenshot(new Page.ScreenshotOptions().setPath(directory.resolve("work-agent-worklog-mobile.png")).setFullPage(true).setAnimations(ScreenshotAnimations.DISABLED));
    }
    @Test void reviewDraftsAreEditableEscapedAndSavedOnlyByExplicitButton() throws Exception {
        reviewJobs.add(Json.obj("id", "review", "kind", "review", "status", "ready", "title", "CI 실행 PR", "url", "https://bitbucket.org/team/repo/pull-requests/105", "sourceCommit", "abcdef123456", "summary", "검토 완료", "findings", List.of(Json.obj("id", "finding", "path", "file.go", "line", 12, "title", "실패 원인 누락", "comment", "<script>window.injected=true</script>", "evidence", List.of(Json.obj("path", "file.go", "line", 12, "side", "new", "quote", "return nil"))))));
        page.locator("[data-view='reviews']").click(); page.locator("[data-edit-review]").waitFor();
        assertNull(page.evaluate("window.injected")); assertEquals(0, pendingSaves);
        page.locator("[data-edit-review]").click(); page.locator("#review-text").fill("실패 원인이 표시되지 않습니다. 원인을 출력해 주세요.");
        page.locator("#review-edit [type='submit']").click(); page.waitForCondition(() -> !page.locator("#modal").isVisible() && page.locator(".review-comment").textContent().equals("실패 원인이 표시되지 않습니다. 원인을 출력해 주세요."));
        assertEquals("실패 원인이 표시되지 않습니다. 원인을 출력해 주세요.", page.locator(".review-comment").textContent());
        page.locator("[data-save-review]").click(); page.waitForCondition(() -> pendingSaves == 1 && page.locator(".review-card").textContent().contains("비공개 초안 저장됨"));
        assertTrue(page.locator(".review-card").textContent().contains("비공개 초안 저장됨"));
        page.locator("#refresh-reviews").click(); page.waitForCondition(() -> reviewRefreshes == 1 && !page.locator("#refresh-reviews").isDisabled());
        page.locator("#pr-url").fill("https://bitbucket.org/another/project/pull-requests/21"); page.locator("#review-request [type='submit']").click();
        page.waitForCondition(() -> reviewRequests.size() == 1); assertTrue(Json.text(reviewRequests.getFirst(), "url").contains("another/project"));
        String output = System.getProperty("work.ui.review.screenshots", "");
        if (!output.isBlank()) { Path directory = Path.of(output); Files.createDirectories(directory); page.screenshot(new Page.ScreenshotOptions().setPath(directory.resolve("work-agent-pr-reviews.png")).setFullPage(true)); }
        page.setViewportSize(390, 844); assertTrue((Boolean) page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"));
    }
    @Test void selectedTasksCanBeAddedToTodayInOneRequest() {
        page.locator("[data-view='plan']").click();
        page.locator("#select-all-tasks").check();
        assertFalse(page.locator("#bulk-today").isDisabled());
        page.locator("#bulk-today").click();
        page.waitForCondition(()->planUpdates.size()==1 && state.path("plan").size()==2);
        assertEquals(1,planUpdates.getFirst().path("taskIds").size());
        assertEquals("jira:CT-42",planUpdates.getFirst().path("taskIds").get(0).asText());
        assertEquals("local",state.path("plan").get(0).path("id").asText());
    }
    @Test void reviewDetailsStayOpenAcrossUnchangedAndChangedPollingResponses() {
        ObjectNode job=Json.obj("id","stable-review","kind","review","status","running","title","CI PR","url","https://bitbucket.org/team/repo/pull-requests/105","sourceCommit","head","findings",List.of(),"limitations",List.of("외부 실행은 하지 않았습니다."));
        reviewJobs.add(job);
        page.locator("[data-view='reviews']").click();
        Locator detail=page.locator(".review-card details").last();
        detail.locator("summary").click();detail.evaluate("el=>el.dataset.marker='kept'");
        page.evaluate("updateReviews()");
        assertEquals("kept",detail.getAttribute("data-marker"));
        assertTrue((Boolean)detail.evaluate("el=>el.open"));
        job.put("status","ready");page.evaluate("updateReviews()");
        assertTrue((Boolean)detail.evaluate("el=>el.open"));
        detail.locator("summary").click();job.put("summary","갱신 완료");page.evaluate("updateReviews()");
        assertFalse((Boolean)detail.evaluate("el=>el.open"));
    }
    @Test void draftResultsAndProposalsShareOneSectionAndRequireExplicitSelection() {
        state.set("draftDigest",Json.obj("generatedAt","2026-11-05T22:30:00Z","from","2026-11-04T15:00:00Z","until","2026-11-05T22:30:00Z","errors",List.of(),"prs",List.of(Json.obj("id","draft","number",105,"title","CT-42 CI 구현","url","https://bitbucket.org/team/repo/pull-requests/105","summary","PR 설명 요약","reviewed",false,"reviewSuggestion",Json.obj("id","review-draft"),"suggestions",List.of(Json.obj("id","followup-draft","title","배포 문서 작성","reason","PR에 남은 작업으로 기록돼 있습니다."))))));
        page.reload();page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("작업 결과와 다음 할 일").setExact(true)).waitFor();
        assertEquals(0,page.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("후속 작업 제안").setExact(true)).count());
        assertTrue(acceptedFollowups.isEmpty());
        page.locator("[data-accept-followup='review-draft']").click();
        page.waitForCondition(()->acceptedFollowups.size()==1&&page.locator("[data-accept-followup='review-draft']").isDisabled());
        assertEquals(List.of("review-draft"),acceptedFollowups);
        page.locator("[data-draft-reviewed='draft']").click();
        page.waitForCondition(()->page.locator("[data-draft-reviewed='draft']").textContent().equals("다시 검토 대기로"));
    }
    @Test void yesterdayStartsWithPlannedWorkAndAddsSelectedCompletedWorkWithoutChangingJira() throws Exception {
        state.put("previousWorkDate","2026-11-05");state.set("previousWork",Json.MAPPER.valueToTree(List.of(Json.obj("workId","local","taskId","local","title","계획만 한 업무","planned",true,"inInitialPlan",true,"inFinalPlan",true,"observed",false,"outcome","unconfirmed"),Json.obj("workId","jira:CT-42","taskId","jira:CT-42","title","두번째 계획","planned",true,"inInitialPlan",true,"inFinalPlan",true,"observed",false,"outcome","unconfirmed"))));
        pastWorkCandidates.add(Json.obj("workId","jira:CT2612-518","taskId","jira:CT2612-518","title","LCOV 옵션 추가","jiraKey","CT2612-518","issueUrl","https://example.atlassian.net/browse/CT2612-518","evidence",Json.obj("messageCount",42)));
        ((ArrayNode)state.path("tasks")).add(task("jira:CT2612-518","CT2612-518","LCOV 옵션 추가"));
        page.reload();page.locator(".work-reconciliation").waitFor();
        assertEquals(2,page.locator(".work-day-row").count());assertFalse(page.locator(".work-reconciliation").textContent().contains("LCOV 옵션 추가"));
        page.locator("[data-work-outcome='local']").selectOption("not-started");page.waitForCondition(()->workDayUpdates.size()==1);
        page.locator("#add-previous-work").click();page.locator("[data-choose-past-work='jira:CT2612-518']").waitFor();
        assertEquals("https://example.atlassian.net/browse/CT2612-518",page.locator(".past-work-candidate a").getAttribute("href"));
        page.locator("[data-choose-past-work='jira:CT2612-518']").click();page.locator("#past-work-outcome").selectOption("done");page.locator("#past-work-form [type='submit']").click();
        page.waitForCondition(()->addedPastWork.size()==1&&page.locator(".work-day-row").count()==3);
        assertEquals("2026-11-05",Json.text(addedPastWork.getFirst(),"date"));assertEquals("done",page.locator("[data-work-outcome='jira:CT2612-518']").inputValue());assertTrue(statusUpdates.isEmpty());
        assertFalse(page.locator(".work-reconciliation").textContent().contains("오늘 분량 완료"));
        assertEquals("https://example.atlassian.net/browse/CT2612-518",page.locator(".work-reconciliation a").getAttribute("href"));
        String screenshots=System.getProperty("work.ui.workflow.screenshots","");if(!screenshots.isBlank()){Path output=Files.createDirectories(Path.of(screenshots));page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve("workflow-desktop.png")).setFullPage(true));page.setViewportSize(390,844);assertTrue((Boolean)page.evaluate("document.documentElement.scrollWidth <= innerWidth"));page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve("workflow-mobile.png")).setFullPage(true));}
    }
    @Test void handwrittenPastWorkAndExistingTasksCanBeAddedWithoutCreatingATodayTask() {
        state.put("previousWorkDate","2026-11-05");state.set("previousWork",Json.MAPPER.createArrayNode());page.reload();
        page.locator("#add-previous-work").click();page.locator("#past-work-task").selectOption("jira:CT-42");page.locator("#past-work-outcome").selectOption("done");page.locator("#past-work-form [type='submit']").click();page.waitForCondition(()->addedPastWork.size()==1);
        assertEquals("jira:CT-42",Json.text(addedPastWork.getFirst(),"taskId"));
        page.locator("#add-previous-work").click();page.locator("#past-work-title").fill("기록이 없는 고객 미팅");page.locator("#past-work-outcome").selectOption("done");page.locator("#past-work-form [type='submit']").click();page.waitForCondition(()->addedPastWork.size()==2);
        assertEquals("기록이 없는 고객 미팅",Json.text(addedPastWork.get(1),"title"));assertEquals("",Json.text(addedPastWork.get(1),"taskId"));assertTrue(updates.isEmpty());assertTrue(planUpdates.isEmpty());
    }
    @Test void carryoverIncludesJiraLinksAndRecentWorkDate() {
        state.set("carryover",Json.MAPPER.valueToTree(List.of(Json.obj("taskId","jira:CT-42","title","다시 볼 업무","jiraKey","CT-42","issueUrl","https://example.atlassian.net/browse/CT-42","date","2026-11-05","reason","진행한 업무입니다."))));
        page.reload();page.locator(".carryover-row a").waitFor();assertEquals("https://example.atlassian.net/browse/CT-42",page.locator(".carryover-row a").getAttribute("href"));assertTrue(page.locator(".carryover-row").textContent().contains("2026-11-05"));
    }
    @Test void aPreviouslyCheckedSessionPrefillsTheSuggestedExistingTaskWhenLinking() {
        state.put("previousWorkDate","2026-11-05");state.set("previousWork",Json.MAPPER.valueToTree(List.of(Json.obj("workId","session:claude:legacy","title","CT-42 이전 작업명","jiraKey","CT-42","suggestedTaskId","jira:CT-42","outcome","progress","confirmed",true))));
        page.reload();page.locator("[data-link-work='session:claude:legacy']").click();assertEquals("jira:CT-42",page.locator("#link-task").inputValue());
    }
    @Test void unlinkedActualWorkCanBeSavedAndNewActivityCanBeAddedWithoutLosingEnteredTime() {
        state.set("worklogCandidates",Json.MAPPER.valueToTree(List.of(Json.obj("date","2026-11-06","workId","activity:first","title","Jira 발급 전 설계 논의","jiraKey",""))));
        page.reload();page.locator("[data-view='worklog']").click();assertEquals("",page.locator(".log-key").inputValue());
        assertTrue(page.locator(".worklog-task-title").textContent().contains("설계 논의"));page.locator(".log-duration").fill("2h");
        page.locator("#worklog-form [type='submit']").click();page.waitForCondition(()->!savedWorklogs.isEmpty());
        assertEquals("activity:first",Json.text(savedWorklogs.getFirst().path("items").get(0),"workId"));
        ((ArrayNode)state.path("worklogCandidates")).add(Json.obj("date","2026-11-06","workId","activity:second","title","추가 고객 지원","jiraKey",""));
        page.reload();page.locator("[data-view='worklog']").click();page.locator("[data-add-worklog-work='activity:second']").click();
        assertEquals(2,page.locator("#worklog-rows .worklog-input-row").count());assertEquals("2h",page.locator(".log-duration").first().inputValue());
        assertTrue(page.locator("#post-worklog").isDisabled());
    }
    @Test void followupsRemainAfterPrReviewAndCanBeExcludedFromTheCombinedSection() {
        state.set("followups",Json.MAPPER.valueToTree(List.of(Json.obj("id","remaining","kind","followup","sourceDraftResultId","reviewed-pr","sourcePrUrl","https://bitbucket.org/team/repo/pull-requests/1","title","검토 뒤 남은 배포 문서","reason","PR에서 다음 작업으로 확인했습니다."))));
        page.reload();page.locator("[data-accept-followup='remaining']").waitFor();assertTrue(page.locator(".side-stack").textContent().contains("남은 후속 작업"));
        page.locator("[data-dismiss-followup='remaining']").click();page.waitForCondition(()->page.locator("[data-accept-followup='remaining']").count()==0);
    }
    @Test void longTaskOptionsCannotOverflowTheLinkingDialogAndSearchUsesFullWidthControls() throws Exception {
        ((ArrayNode)state.path("tasks")).add(task("long","CT-99","긴 업무 제목 "+"VeryLongUnbrokenIssueTitle".repeat(25)));
        state.put("previousWorkDate","2026-11-05");state.set("previousWork",Json.MAPPER.valueToTree(List.of(Json.obj("workId","session:claude:overflow","title","기존 업무 연결","outcome","progress"))));
        page.reload();page.locator("[data-link-work='session:claude:overflow']").click();
        assertTrue((Boolean)page.locator("#modal").evaluate("el=>el.scrollWidth<=el.clientWidth+1"));
        for(int width:List.of(390,660)){page.setViewportSize(width,844);assertTrue((Boolean)page.locator("#modal").evaluate("el=>el.scrollWidth<=el.clientWidth+1"));}
        String screenshots=System.getProperty("work.ui.fixes.screenshots","");if(!screenshots.isBlank()){Path output=Files.createDirectories(Path.of(screenshots));page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve("link-dialog.png")));}
        page.locator("#close-modal").click();page.locator("#add-previous-work").click();
        assertTrue(page.locator("#past-work-search").boundingBox().height>=38);
        assertTrue((Boolean)page.locator("#past-work-search").evaluate("el=>el.getBoundingClientRect().width>=el.parentElement.clientWidth*0.9"));
        if(!screenshots.isBlank())page.screenshot(new Page.ScreenshotOptions().setPath(Path.of(screenshots).resolve("past-work-dialog.png")));
    }
    @Test void repositoryConnectionIsPrimaryAndExternalSpecsRemainOptional() {
        page.locator("[data-view='reviews']").click();
        page.locator("#review-repository").locator("..").locator("summary").first().click();
        assertTrue(page.locator("#review-local-path").isVisible());assertFalse(page.locator("#review-repository-sources").isVisible());
    }
    @Test void bulkTypeChangePreservesDifferentGroupsAndExplicitGroupRemovalPreservesTypes() {
        ((ObjectNode)state.path("tasks").get(0)).put("workGroup","첫 묶음");((ObjectNode)state.path("tasks").get(1)).put("workGroup","둘째 묶음");
        page.reload();page.locator("[data-view='plan']").click();page.locator("#select-all-tasks").check();page.locator("#bulk-group").click();
        page.locator("#bulk-work-type").selectOption("품질·성능");page.locator("#group-form [type='submit']").click();page.waitForCondition(()->groupUpdates.size()==1);
        assertEquals("품질·성능",Json.text(groupUpdates.getFirst(),"workType"));assertFalse(groupUpdates.getFirst().has("workGroup"));
        assertEquals("첫 묶음",Json.text(state.path("tasks").get(0),"workGroup"));
        page.locator("#bulk-group").click();page.locator("#clear-bulk-group").click();page.locator("#group-form [type='submit']").click();page.waitForCondition(()->groupUpdates.size()==2);
        assertEquals("",Json.text(groupUpdates.get(1),"workGroup"));assertFalse(groupUpdates.get(1).has("workType"));
    }
    @Test void savingALocalRepositoryDoesNotEraseUntouchedExternalReferences() {
        page.locator("[data-view='reviews']").click();page.locator("#review-repository").locator("..").locator("summary").first().click();
        page.locator("#review-repository-name").fill("team/repo");page.locator("#review-local-path").fill("D:/Git/repo");page.locator("#review-repository [type='submit']").click();
        page.waitForCondition(()->workspaceUpdates.size()==1);assertTrue(referenceUpdates.isEmpty());
        page.locator(".external-review-sources>summary").click();page.locator("#review-repository-sources").fill("https://bitbucket.org/team/specs/src/main/contract.md");page.locator("#review-repository [type='submit']").click();page.waitForCondition(()->referenceUpdates.size()==1);
        assertEquals(1,workspaceUpdates.size());
    }
    @Test void oldUnreviewedEmptySuggestionsAreRemovedWhileTypedTimeManualIssuesAndSavedDraftsSurvive() {
        ObjectNode saved=Json.obj("workId","session:saved","title","저장한 시간 미정 업무","jiraKey","CT-9","seconds",0,"marker","saved-marker","state","draft");
        state.set("worklogs",Json.MAPPER.valueToTree(List.of(Json.obj("date","2026-11-06","items",List.of(saved)))));
        page.evaluate("value=>localStorage.setItem('work-agent.worklog-editor:2026-11-06',JSON.stringify(value))",List.of(
                Map.of("workId","session:old-auto","title","하네스 자동 실행","jiraKey","","duration","","marker","auto"),
                Map.of("workId","session:typed","title","시간을 입력한 업무","jiraKey","","duration","2h","marker","typed"),
                Map.of("jiraKey","WOR-1517","duration","","marker","manual"),
                Map.of("workId","session:saved","title","저장한 시간 미정 업무","jiraKey","CT-9","duration","","marker","saved-marker")));
        page.reload();page.locator("[data-view='worklog']").click();
        assertEquals(3,page.locator("#worklog-rows .worklog-input-row").count());assertEquals("2h",page.locator(".log-duration").first().inputValue());assertFalse(page.locator("#worklog-rows").textContent().contains("하네스 자동 실행"));
        assertEquals("WOR-1517",page.locator(".log-key").nth(1).inputValue());assertEquals("CT-9",page.locator(".log-key").nth(2).inputValue());
    }
}
