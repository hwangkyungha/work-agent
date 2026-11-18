package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReviewListTest {
    @TempDir Path temporary;
    Store store; WorkService service; ReviewList list;
    final String url="https://bitbucket.org/team/repo/pull-requests/105";
    final Map<Integer,String> states=new HashMap<>();
    int reads; boolean failRead;
    PullRequestReviewTest.FakeApi api;
    @BeforeEach void open() throws Exception {
        Config config=new Config(temporary.resolve("none"),Map.of());
        store=new Store(temporary.resolve("test.db"));
        service=new WorkService(store,new KoreaTime(Clock.systemUTC()),new JiraClient(config));
        api=new PullRequestReviewTest.FakeApi(config){
            @Override JsonNode pullRequest(PullRequest pr){
                reads++;
                if(failRead)throw new IllegalStateException("HTTP 403 or timeout");
                ObjectNode row=(ObjectNode)super.pullRequest(pr);
                return row.put("state",states.getOrDefault(pr.number(),"OPEN"));
            }
        };
        list=new ReviewList(service,api);
    }
    @AfterEach void close() throws Exception {store.close();}
    ObjectNode job(String id,String head){return store.put("prReview",Json.obj("id",id,"url",url,"sourceCommit",head,"status","ready","readingGuide","저장한 검토 본문","sessionId","session","findings",List.of()));}
    ObjectNode visible(ObjectNode job){return list.decorate(List.of(job)).getFirst();}
    void mark(ObjectNode job,boolean reviewed){list.mark(Json.obj("id",job.path("id"),"reviewed",reviewed));}
    ObjectNode metadata(String state,String head){return Json.obj("state",state,"source",Json.obj("commit",Json.obj("hash",head)));}

    @Test void humanCompletionAndRestorePersistWithoutChangingReviewOrSession() {
        ObjectNode job=job("first","head");
        assertEquals("active",Json.text(visible(job),"listGroup"));
        mark(job,true);
        list=new ReviewList(service,api);
        assertEquals("completed",Json.text(visible(job),"listGroup"));
        assertEquals(job,store.get("prReview","first"));
        mark(job,false);
        assertEquals("active",Json.text(visible(job),"listGroup"));
        assertEquals(job,store.get("prReview","first"));
    }
    @Test void newHeadReturnsToActiveAndNewExecutionDoesNotInheritCompletion() {
        ObjectNode old=job("old","old-head");mark(old,true);
        list.observe(url,metadata("OPEN","new-head"));
        ObjectNode shown=visible(old);
        assertEquals("active",Json.text(shown,"listGroup"));assertTrue(shown.path("headChanged").asBoolean());
        assertThrows(IllegalArgumentException.class,()->mark(old,true));
        ObjectNode current=job("new","new-head");
        List<ObjectNode> jobs=list.decorate(List.of(current,old));
        assertEquals("active",Json.text(jobs.getFirst(),"listGroup"));assertFalse(jobs.getFirst().path("userReviewed").asBoolean());
        assertEquals("history",Json.text(jobs.getLast(),"listGroup"));
        assertTrue(jobs.getLast().path("userReviewed").asBoolean());
    }
    @Test void confirmedMergedAndDeclinedStayInClosedEvenAfterRestore() {
        ObjectNode job=job("first","head");mark(job,true);
        for(String state:List.of("MERGED","DECLINED")){
            list.observe(url,metadata(state,"head"));
            assertEquals("closed",Json.text(visible(job),"listGroup"));
        }
        mark(job,false);assertEquals("closed",Json.text(visible(job),"listGroup"));
        list.observe(url,metadata("OPEN","head"));
        assertEquals("active",Json.text(visible(job),"listGroup"));
    }
    @Test void missingFromOpenResultsIsNotClosedAndFailuresPreserveLastState() {
        ObjectNode job=job("first","source");
        list.refreshKnown(Set.of());assertEquals("active",Json.text(visible(job),"listGroup"));
        failRead=true;
        assertEquals(1,list.refreshKnown(Set.of()).size());
        assertEquals("OPEN",Json.text(visible(job),"prState"));assertEquals("active",Json.text(visible(job),"listGroup"));
        assertFalse(Json.text(visible(job),"prStateError").isBlank());
        states.put(105,"MERGED");failRead=false;list.refresh();
        assertEquals("closed",Json.text(visible(job),"listGroup"));assertTrue(Json.text(visible(job),"prStateError").isBlank());
        failRead=true;list.refresh();assertEquals("closed",Json.text(visible(job),"listGroup"));
    }
    @Test void failedFirstReadDoesNotExcludeAndEachUrlIsReadOnlyOnceWithoutAIOrWrites() {
        ObjectNode job=job("first","source");job("duplicate","source");
        failRead=true;list.refresh();assertEquals(1,reads);
        assertEquals("active",Json.text(visible(job),"listGroup"));
        failRead=false;list.refreshKnown(Set.of(url));assertEquals(1,reads);
        list.refresh();assertEquals(2,reads);assertEquals(0,api.writes);
        assertEquals(2,store.list("prReview").size());
    }
    @Test void abbreviatedAndFullHeadsAreTheSameButAnotherHeadIsDifferent() {
        String full="abcdef1234567890abcdef1234567890abcdef12";
        ObjectNode job=job("first",full);mark(job,true);
        list.observe(url,metadata("OPEN",full.substring(0,12)));
        assertEquals("completed",Json.text(visible(job),"listGroup"));
        assertFalse(visible(job).path("headChanged").asBoolean());
        list.observe(url,metadata("OPEN","abcdef123457"));
        assertEquals("active",Json.text(visible(job),"listGroup"));
    }
    @Test void repliesValidationAndNewCommentRevisionsKeepIndependentDecisions() {
        ObjectNode job=job("review","head");mark(job,true);
        ObjectNode reply=job("reply","head").put("parentCommentId",7).put("commentRevision","r1");store.put("prReview",reply);
        ObjectNode validation=job("validation","head").put("validation",true);store.put("prReview",validation);
        assertEquals("active",Json.text(visible(reply),"listGroup"));assertEquals("active",Json.text(visible(validation),"listGroup"));
        mark(reply,true);assertEquals("completed",Json.text(visible(reply),"listGroup"));
        reply.put("commentRevision","r2");store.put("prReview",reply);
        assertEquals("active",Json.text(visible(reply),"listGroup"));
    }
    @Test void runningAndUncertainDeliveryCannotBeMarkedComplete() {
        ObjectNode job=job("first","head");
        for(String state:List.of("queued","running","saving","delivery-unknown")){
            job.put("status",state);store.put("prReview",job);
            assertTrue(visible(job).path("managementBusy").asBoolean());
            assertThrows(IllegalArgumentException.class,()->mark(job,true));
        }
    }
    @Test void monitorChecksKnownPrEvenWhenNoOpenPrWasReturned() throws Exception {
        ObjectNode job=job("first","source");states.put(105,"DECLINED");
        Config config=new Config(temporary.resolve("monitor-config"),Map.of("BITBUCKET_WORKSPACES","team"));
        try(PullRequestReview reviews=new PullRequestReview(service,api,prompt->{fail("status check must not invoke AI");return Json.object();});
            ReviewMonitor monitor=new ReviewMonitor(service,api,reviews,config,text->fail("no notification during scan"))){
            monitor.scan();
            assertEquals("closed",Json.text(monitor.state().path("jobs").get(0),"listGroup"));
            assertEquals(job,store.get("prReview","first"));assertEquals(0,api.writes);
        }
    }
    @Test void humanCompletionDoesNotCauseTheSameHeadToBeReviewedAgain() throws Exception {
        ObjectNode job=job("first","source");mark(job,true);
        try(PullRequestReview reviews=new PullRequestReview(service,api,prompt->{fail("same head must not invoke AI again");return Json.object();})){
            assertEquals("first",Json.text(reviews.request(url,0,true),"id"));
            assertEquals("completed",Json.text(visible(store.get("prReview","first")),"listGroup"));
            assertEquals(1,store.list("prReview").size());assertEquals(0,api.writes);
        }
    }
}
