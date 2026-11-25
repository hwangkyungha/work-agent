package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Local review decisions and observed PR lifecycle, independent of AI completion. */
final class ReviewList {
    private final WorkService service;
    private final BitbucketClient api;
    ReviewList(WorkService service,BitbucketClient api){this.service=service;this.api=api;}
    private static boolean sameHead(String a,String b){return !a.isBlank()&&(a.equals(b)||a.matches("[0-9a-f]{12,64}")&&b.matches("[0-9a-f]{12,64}")&&(a.startsWith(b)||b.startsWith(a)));}
    private static String scope(JsonNode row){return Json.text(row,"url")+":"+row.path("parentCommentId").asLong()+":"+row.path("validation").asBoolean();}
    private static boolean matches(JsonNode decision,JsonNode job){
        if(!scope(decision).equals(scope(job))||!Json.text(decision,"commentRevision").equals(Json.text(job,"commentRevision")))return false;
        String head=Json.text(decision,"sourceCommit");
        if(sameHead(head,Json.text(job,"sourceCommit")))return true;
        for(JsonNode previous:job.path("equivalentHeads"))if(sameHead(head,previous.asText()))return true;
        return false;
    }
    ObjectNode mark(JsonNode input){
        ObjectNode job=service.store.get("prReview",Json.required(input,"id"));
        if(job==null)throw new IllegalArgumentException("리뷰 기록을 찾지 못했습니다.");
        if(PullRequestReview.inProgress(job)||Json.text(job,"status").equals("delivery-unknown"))throw new IllegalArgumentException("진행 중인 검토나 댓글 저장 결과를 먼저 확인하세요.");
        if(Json.text(job,"sourceCommit").isBlank())throw new IllegalArgumentException("검토한 head가 없는 기록입니다.");
        boolean reviewed=input.path("reviewed").asBoolean(true);
        if(reviewed){ObjectNode latest=service.store.get("prLifecycle",Json.text(job,"url"));if(latest!=null&&!Json.text(latest,"sourceCommit").isBlank()&&!sameHead(Json.text(latest,"sourceCommit"),Json.text(job,"sourceCommit")))throw new IllegalArgumentException("새 커밋이 있습니다. 최신 head의 검토 결과를 확인하세요.");}
        service.store.transaction(()->{
        for(ObjectNode decision:service.store.list("prReviewDecision"))if(matches(decision,job))service.store.delete("prReviewDecision",Json.text(decision,"id"));
        if(reviewed)service.store.put("prReviewDecision",Json.obj("id",TranscriptImporter.hash(scope(job)+":"+Json.text(job,"sourceCommit")),"url",Json.text(job,"url"),"sourceCommit",Json.text(job,"sourceCommit"),"parentCommentId",job.path("parentCommentId").asLong(),"validation",job.path("validation").asBoolean(),"commentRevision",Json.text(job,"commentRevision"),"at",service.time.instant().toString()));
        });
        return Json.obj("id",Json.text(job,"id"),"userReviewed",reviewed);
    }
    void observe(String url,JsonNode metadata){
        String state=Json.text(metadata,"state");if(!Set.of("OPEN","MERGED","DECLINED").contains(state))throw new IllegalArgumentException("PR 상태를 확인하지 못했습니다.");
        service.store.put("prLifecycle",Json.obj("id",url,"url",url,"state",state,"sourceCommit",Json.text(metadata.path("source").path("commit"),"hash"),"checkedAt",service.time.instant().toString()));
    }
    List<String> refreshKnown(Set<String> observed){
        Set<String> urls=new LinkedHashSet<>();for(ObjectNode job:service.store.list("prReview"))if(!Json.text(job,"url").isBlank())urls.add(Json.text(job,"url"));
        List<String> warnings=new ArrayList<>();
        for(String url:urls)if(!observed.contains(url))try{observe(url,api.pullRequest(BitbucketClient.PullRequest.parse(url)));}
        catch(Exception e){ObjectNode row=service.store.get("prLifecycle",url);if(row==null)row=Json.obj("id",url,"url",url);row.put("error","PR 상태를 확인하지 못했습니다.").put("attemptedAt",service.time.instant().toString());service.store.put("prLifecycle",row);warnings.add(url+": PR 상태를 확인하지 못해 마지막 확인 상태를 유지합니다.");}
        return warnings;
    }
    ObjectNode refresh(){List<String> warnings=refreshKnown(Set.of());return Json.obj("warnings",warnings);}
    List<ObjectNode> decorate(List<ObjectNode> jobs){
        List<ObjectNode> decisions=service.store.list("prReviewDecision"),result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(ObjectNode original:jobs){
            ObjectNode job=original.deepCopy(),pr=service.store.get("prLifecycle",Json.text(job,"url"));
            String state=pr==null?"":Json.text(pr,"state"),head=pr==null?"":Json.text(pr,"sourceCommit");
            boolean changed=!head.isBlank()&&!sameHead(head,Json.text(job,"sourceCommit")),reviewed=decisions.stream().anyMatch(d->matches(d,job));
            job.put("managementBusy",PullRequestReview.inProgress(job)||Json.text(job,"status").equals("delivery-unknown"));
            job.put("prState",state).put("headChanged",changed).put("userReviewed",reviewed);
            if(pr!=null){job.put("prCheckedAt",Json.text(pr,"checkedAt")).put("prStateError",Json.text(pr,"error"));}
            String group=Set.of("MERGED","DECLINED").contains(state)?"closed":reviewed&&!changed?"completed":"active";
            if(!seen.add(scope(job)))group="history";
            job.put("listGroup",group);result.add(job);
        }
        return result;
    }
}
