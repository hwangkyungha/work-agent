package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftResultsTest {
    @TempDir Path temporary;
    Store store;WorkService service;Config config;Api api;AtomicInteger calls=new AtomicInteger();
    static class Api extends BitbucketClient {
        List<JsonNode> prs=new ArrayList<>();boolean fail,workspaceFailure,workspaceDiscoveryDenied;
        Api(Config config){super(config);}
        @Override JsonNode get(String path){assertEquals("/user",path);return Json.obj("uuid","owner");}
        @Override List<JsonNode> pages(String path)throws Exception {
            if(path.startsWith("/user/workspaces?")) {
                if(workspaceDiscoveryDenied)throw new Rejected(403);
                return workspaceFailure?List.of(Json.obj("workspace",Json.obj("slug","team")),Json.obj("workspace",Json.obj("slug","other"))):List.of(Json.obj("workspace",Json.obj("slug","team")));
            }
            if(path.contains("/other/permissions/repositories"))throw new Rejected(403);
            if(path.contains("/permissions/repositories"))return List.of(Json.obj("repository",Json.obj("full_name","team/repo")));
            assertTrue(path.contains("/pullrequests?"));
            if(fail)throw new Rejected(403);
            return prs;
        }
    }
    @BeforeEach void open()throws Exception {
        config=new Config(temporary.resolve("none"),Map.of());store=new Store(temporary.resolve("test.db"));
        service=new WorkService(store,new KoreaTime(Clock.fixed(Instant.parse("2026-11-09T22:30:00Z"),ZoneOffset.UTC)),new JiraClient(config));api=new Api(config);
    }
    @AfterEach void close()throws Exception{store.close();}
    ObjectNode pr(int id,String created){return Json.obj("id",id,"title","CT-"+id+" 기능 구현","description","다음 단계에서 배포 문서 작성이 필요합니다.","state","OPEN","draft",true,"created_on",created,"author",Json.obj("uuid","owner"),"source",Json.obj("commit",Json.obj("hash","head"+id),"branch",Json.obj("name","feature/CT-"+id)));}
    DraftResults results(){return new DraftResults(service,api,prompt->{calls.incrementAndGet();return Json.obj("reply","PR 설명에 따르면 구현을 마쳤습니다.","actions",List.of(Json.obj("type","task","payload",Json.write(Json.obj("title","배포 문서 작성","reason","PR 설명에 다음 단계로 적혀 있습니다.")))));},config);}
    @Test void collectsOnlyOwnOpenDraftsInTheKoreanOvernightWindowAndCachesProposals()throws Exception {
        api.prs.addAll(List.of(pr(1,"2026-11-08T15:00:00Z"),pr(2,"2026-11-09T16:00:00Z"),pr(3,"2026-11-09T22:30:00Z"),pr(4,"2026-11-08T14:59:59Z"),pr(5,"2026-11-09T22:30:00.001Z"),pr(6,"2026-11-09T12:00:00Z").put("draft",false),pr(7,"2026-11-09T12:00:00Z").put("state","MERGED")));
        ObjectNode other=pr(8,"2026-11-09T12:00:00Z");other.set("author",Json.obj("uuid","other"));api.prs.add(other);
        DraftResults results=results();LocalDate date=service.time.today();ObjectNode digest=results.generate(date,false);
        assertEquals(3,digest.path("prs").size());assertEquals(3,calls.get());assertTrue(service.planned(date).isEmpty());
        assertEquals("2026-11-08T15:00:00Z",Json.text(digest,"from"));
        results().generate(date,false);results.generate(date,true);assertEquals(3,calls.get());
        ((ObjectNode)api.prs.get(0).path("source").path("commit")).put("hash","changed-head");results.generate(date,true);assertEquals(4,calls.get());
    }
    @Test void acceptedReviewTaskIsLocalAndDoesNotReopenItsJiraIssue()throws Exception {
        api.prs.add(pr(1,"2026-11-09T20:00:00Z"));
        store.put("task",Json.obj("id","jira:CT-1","title","구현","jiraKey","CT-1","status","done"));
        ObjectNode digest=results().generate(service.time.today(),false);String suggestion=Json.text(digest.path("prs").get(0),"reviewSuggestionId");
        ObjectNode task=service.acceptFollowup(Json.obj("id",suggestion));
        assertEquals("",Json.text(task,"jiraKey"));assertEquals("done",Json.text(store.get("task","jira:CT-1"),"status"));
        assertEquals(task,service.acceptFollowup(Json.obj("id",suggestion)));assertEquals(1,service.planned(service.time.today()).size());
        service.reviewDraftResult(Json.obj("id",digest.path("prs").get(0).path("id").asText()));
        assertTrue(DraftResults.state(service,service.time.today()).path("prs").get(0).path("reviewed").asBoolean());
    }
    @Test void incompleteDiscoveryAndInvalidAiActionsStayVisibleWithoutExecutingAnything()throws Exception {
        api.fail=true;ObjectNode failed=results().generate(service.time.today(),false);assertEquals(1,failed.path("errors").size());assertEquals(0,calls.get());
        api.fail=false;api.prs.add(pr(1,"2026-11-09T20:00:00Z"));
        DraftResults invalid=new DraftResults(service,api,prompt->Json.obj("reply","","actions",List.of(Json.obj("type","meeting","payload","{}"))),config);
        ObjectNode digest=invalid.generate(service.time.today(),true);
        assertEquals(1,digest.path("prs").size());assertEquals(1,digest.path("errors").size());
        assertTrue(service.planned(service.time.today()).isEmpty());assertTrue(store.list("meeting").isEmpty());assertEquals(1,store.list("followup").size());
    }
    @Test void earlyManualCollectionDoesNotHideDraftsCreatedBeforeTheScheduledCutoff()throws Exception {
        service=new WorkService(store,new KoreaTime(Clock.fixed(Instant.parse("2026-11-09T21:00:00Z"),ZoneOffset.UTC)),new JiraClient(config));
        api.prs.add(pr(1,"2026-11-09T21:30:00Z"));assertTrue(results().generate(service.time.today(),false).path("prs").isEmpty());
        service=new WorkService(store,new KoreaTime(Clock.fixed(Instant.parse("2026-11-09T22:30:00Z"),ZoneOffset.UTC)),new JiraClient(config));
        assertEquals(1,results().generate(service.time.today(),false).path("prs").size());
    }
    @Test void discoveryFailuresKeepAccessibleResultsAndExplainFallbackScope()throws Exception {
        api.prs.add(pr(1,"2026-11-09T20:00:00Z"));api.workspaceFailure=true;
        ObjectNode partial=results().generate(service.time.today(),true);
        assertEquals(1,partial.path("prs").size());assertEquals(1,partial.path("errors").size());
        api.workspaceFailure=false;api.workspaceDiscoveryDenied=true;
        config=new Config(temporary.resolve("none"),Map.of("BITBUCKET_WORKSPACES","team"));
        ObjectNode fallback=results().generate(service.time.today(),true);
        assertEquals(1,fallback.path("prs").size());assertFalse(fallback.path("errors").isEmpty());
    }
    @Test void refreshingProposalsDoesNotOverwriteAConcurrentReviewDecision()throws Exception {
        api.prs.add(pr(1,"2026-11-09T20:00:00Z"));
        DraftResults failed=new DraftResults(service,api,prompt->{throw new IllegalStateException("unavailable");},config);
        ObjectNode old=failed.generate(service.time.today(),false);String id=Json.text(old.path("prs").get(0),"id");
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        DraftResults refreshing=new DraftResults(service,api,prompt->{started.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("timeout");return Json.obj("reply","새 요약","actions",List.of());},config);
        try(ExecutorService executor=Executors.newSingleThreadExecutor()) {
            Future<ObjectNode> result=executor.submit(()->refreshing.generate(service.time.today(),true));
            try {assertTrue(started.await(5,TimeUnit.SECONDS));service.reviewDraftResult(Json.obj("id",id,"reviewed",true));}
            finally {release.countDown();}
            ObjectNode row=(ObjectNode)result.get(5,TimeUnit.SECONDS).path("prs").get(0);
            assertTrue(row.path("reviewed").asBoolean());assertEquals("새 요약",Json.text(row,"summary"));
        }
    }
}
