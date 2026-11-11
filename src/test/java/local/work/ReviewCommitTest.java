package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ReviewCommitTest {
    static final String HEAD="3a836581aaf1"+"a".repeat(28), BASE="f68e4484aac8"+"b".repeat(28);
    @TempDir Path temporary;
    Store store;PullRequestReview reviews;Api api;WorkService service;
    class Api extends BitbucketClient {
        boolean abbreviated=true;String head=HEAD;List<String> commits=new ArrayList<>();
        Api(Config config){super(config);}
        @Override JsonNode pullRequest(PullRequest pr){return Json.obj("state","OPEN","title","축약 해시 PR","author",Json.obj("uuid","other"),"source",Json.obj("commit",Json.obj("hash",abbreviated?head.substring(0,12):head),"repository",Json.obj("full_name","fork/repo")),"destination",Json.obj("commit",Json.obj("hash",abbreviated?BASE.substring(0,12):BASE),"repository",Json.obj("full_name","team/repo")));}
        @Override JsonNode get(String path){
            if(path.equals("/user"))return Json.obj("uuid","owner");
            if(path.contains("/commit/")){commits.add(path);return Json.obj("hash",path.startsWith("/repositories/fork/")?head:BASE);}
            return Json.obj("hash",BASE);
        }
        @Override String request(String method,String path,JsonNode body){assertEquals("GET",method);return "diff --git a/foo.java b/foo.java\n--- a/foo.java\n+++ b/foo.java\n@@ -1 +1 @@\n-old\n+new\n";}
        @Override List<JsonNode> pages(String path){return List.of(Json.obj("lines_added",1,"lines_removed",1,"old",Json.obj("path","foo.java"),"new",Json.obj("path","foo.java")));}
        @Override List<JsonNode> comments(PullRequest pr){return List.of();}
        @Override String source(String repo,String commit,String path)throws Exception{if(!path.equals("foo.java"))throw new BitbucketClient.Rejected(404);return commit.equals(head)?"new\n":"old\n";}
    }
    @BeforeEach void open()throws Exception{
        Config config=new Config(temporary.resolve("none"),Map.of());store=new Store(temporary.resolve("work.db"));api=new Api(config);
        service=new WorkService(store,new KoreaTime(Clock.systemUTC()),new JiraClient(config));
    }
    @AfterEach void close()throws Exception{if(reviews!=null)reviews.close();store.close();}
    JsonNode result(){return Json.obj("summary","검토 완료","findings",List.of(),"limitations",List.of(),"reply","","replyKind","fact","replyConfidence","low","replyEvidence",List.of(),"relatedPaths",List.of());}
    ObjectNode await(ObjectNode row)throws Exception{
        long until=System.nanoTime()+5_000_000_000L;
        do{row=store.get("prReview",Json.text(row,"id"));if(!PullRequestReview.inProgress(row))return row;Thread.sleep(10);}while(System.nanoTime()<until);
        throw new AssertionError("review did not finish");
    }
    @Test void abbreviatedPullRequestCommitsArePinnedAsFullHashesBeforeReviewStarts()throws Exception{
        reviews=new PullRequestReview(service,api,prompt->result());
        ObjectNode job=reviews.request("https://bitbucket.org/team/repo/pull-requests/119",0,false);
        assertEquals(HEAD,Json.text(job,"sourceCommit"));assertEquals(BASE,Json.text(job,"destinationCommit"));
        assertTrue(api.commits.contains("/repositories/fork/repo/commit/"+HEAD.substring(0,12)));
        assertTrue(api.commits.contains("/repositories/team/repo/commit/"+BASE.substring(0,12)));
    }
    @Test void hashPresentationChangesDoNotTriggerAnotherReviewButANewCommitDoes()throws Exception{
        AtomicInteger calls=new AtomicInteger();reviews=new PullRequestReview(service,api,prompt->{calls.incrementAndGet();return result();});
        ObjectNode first=await(reviews.request("https://bitbucket.org/team/repo/pull-requests/119",0,false));assertEquals("ready",Json.text(first,"status"));int before=calls.get();
        api.abbreviated=false;ObjectNode again=reviews.request(Json.text(first,"url"),0,true);assertEquals(Json.text(first,"id"),Json.text(again,"id"));assertEquals(before,calls.get());
        api.head="7".repeat(40);api.abbreviated=true;ObjectNode changed=await(reviews.request(Json.text(first,"url"),0,false));assertNotEquals(Json.text(first,"id"),Json.text(changed,"id"));assertEquals(api.head,Json.text(changed,"sourceCommit"));assertTrue(calls.get()>before);
    }
    @Test void legacySuccessAndItsNotificationAreReusedAfterNormalization()throws Exception{
        String url="https://bitbucket.org/team/repo/pull-requests/119";
        ObjectNode old=Json.obj("id","old","url",url,"sourceCommit",HEAD.substring(0,12),"destinationCommit",BASE.substring(0,12),"status","ready","notified",true);
        store.put("prReview",old);store.put("prReviewNotice",Json.obj("id",PullRequestReview.notificationKey(old),"reviewId","old"));
        reviews=new PullRequestReview(service,api,prompt->{throw new AssertionError("must reuse previous review");});
        ObjectNode found=reviews.request(url,0,true);assertEquals("old",Json.text(found,"id"));assertEquals(HEAD,Json.text(found,"sourceCommit"));assertNotNull(store.get("prReviewNotice",PullRequestReview.notificationKey(found)));
    }
    @Test void automaticReviewRecoversFromTheLegacyHashErrorOnce()throws Exception{
        String url="https://bitbucket.org/team/repo/pull-requests/119";
        store.put("prReview",Json.obj("id","failed-old","url",url,"sourceCommit",HEAD.substring(0,12),"destinationCommit",BASE.substring(0,12),"status","failed","error","PR 커밋 해시를 확인하세요.","notified",true));
        AtomicInteger calls=new AtomicInteger();reviews=new PullRequestReview(service,api,prompt->{calls.incrementAndGet();return result();});
        ObjectNode retried=await(reviews.request(url,0,true));assertNotEquals("failed-old",Json.text(retried,"id"));assertTrue(Set.of("ready","saved").contains(Json.text(retried,"status")));int before=calls.get();
        assertEquals(Json.text(retried,"id"),Json.text(reviews.request(url,0,true),"id"));assertEquals(before,calls.get());
    }
    @Test void invalidOrMismatchedCommitResolutionIsRejected()throws Exception{
        Api wrong=new Api(new Config(temporary.resolve("none"),Map.of())){@Override JsonNode get(String path){return Json.obj("hash","b".repeat(40));}};
        assertThrows(IllegalStateException.class,()->wrong.fullCommit("fork/repo",HEAD.substring(0,12)));
        assertThrows(IllegalArgumentException.class,()->api.fullCommit("fork/repo","../main"));assertThrows(IllegalArgumentException.class,()->api.fullCommit("../repo",HEAD));
        int before=api.commits.size();assertEquals(HEAD,api.fullCommit("fork/repo",HEAD.toUpperCase(Locale.ROOT)));assertEquals("c".repeat(64),api.fullCommit("fork/repo","c".repeat(64)));assertEquals(before,api.commits.size());
    }
    @Test void oldThreadHashesDoNotLoseThePreviousReviewOrFalselyReportAChangedHead()throws Exception{
        String url="https://bitbucket.org/team/repo/pull-requests/119";
        store.put("prReview",Json.obj("id","old","url",url,"sourceCommit",HEAD.substring(0,12),"destinationCommit",BASE.substring(0,12),"status","ready","summary","이전 검토 요약"));
        PrQuestion question=new PrQuestion(store,api,prompt->{assertTrue(prompt.contains("이전 검토 요약"));return Json.obj("reply","현재 foo.java는 new입니다.","replyKind","fact","replyConfidence","high","replyEvidence",List.of(Json.obj("path","foo.java","side","new","line",1,"quote","new")),"relatedPaths",List.of(),"findings",List.of(),"limitations",List.of());});
        ObjectNode answer=question.answer(url,"foo.java 설명해줘",HEAD.substring(0,12));assertFalse(Json.text(answer,"text").contains("리뷰 이후 PR이 바뀌어"));
    }
}
