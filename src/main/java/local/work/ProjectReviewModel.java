package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Native, persistent PR conversations in pinned local worktrees, with read tools enabled. */
final class ProjectReviewModel implements PullRequestReview.Model {
    private final WorkService service;
    @FunctionalInterface interface SessionRunner { JsonNode run(String prompt, Path directory, String engine, String session, boolean fork, java.util.function.Consumer<String> saved) throws Exception; }
    private final PullRequestReview.Model fallback;
    private final SessionRunner runner;
    private final Config config;
    final ReviewWorkspaces workspaces;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    ProjectReviewModel(WorkService service, CliModel cli, Config config) {
        this(service, config, cli::review, cli::reviewInSession);
    }
    ProjectReviewModel(WorkService service, Config config, PullRequestReview.Model fallback, SessionRunner runner) {
        this.service=service;this.config=config;this.fallback=fallback;this.runner=runner;this.workspaces=new ReviewWorkspaces(config,service.store);
    }
    @Override public JsonNode complete(String prompt) throws Exception { return fallback.complete(prompt); }
    ObjectNode bind(JsonNode input) throws Exception {
        String url=BitbucketClient.PullRequest.parse(Json.required(input,"url")).url(),id=Json.text(input,"sessionId");
        if(id.isBlank()){service.store.delete("prSessionBinding",url);return Json.obj("url",url);}
        ObjectNode session=service.store.get("session",id);
        if(session==null || Json.text(session,"session").isBlank())throw new IllegalArgumentException("원래 작업 세션을 찾지 못했습니다. CLI 기록을 먼저 동기화하세요.");
        return service.store.put("prSessionBinding",Json.obj("id",url,"url",url,"sessionId",id,"source",Json.required(session,"source"),"session",Json.required(session,"session")));
    }
    @Override public PullRequestReview.Model forReview(ObjectNode job, JsonNode metadata) throws Exception {
        Path directory=workspaces.prepare(job,metadata);
        if(directory==null) {
            if(Json.text(job,"kind").equals("review"))throw new IllegalArgumentException("PR 검토를 위한 로컬 저장소 경로를 연결하세요. 전체 파일 발췌 검토는 실행하지 않습니다.");
            job.put("executionMode","excerpts").put("executionNote","연결된 로컬 저장소가 없어 발췌 자료로 검토합니다. 저장소 경로를 연결하면 프로젝트에서 직접 탐색할 수 있습니다.");
            return this;
        }
        String url=Json.required(job,"url"),kind=Json.text(job,"kind");
        if(kind.equals("review"))workspaces.prepareBase(job);
        ObjectNode binding=service.store.get("prSessionBinding",url);
        boolean author=kind.equals("reply")||kind.equals("question")&&binding!=null;
        if(author && binding==null) {
            List<ObjectNode> candidates=new SessionCatalog(service.store).candidates(metadata,Path.of(Json.required(job,"localRepository")));
            job.set("originSessionCandidates",Json.MAPPER.valueToTree(candidates.stream().map(row->Json.obj("id",Json.text(row,"id"),"name",Json.text(row,"name"))).toList()));
            if(candidates.size()==1)binding=bind(Json.obj("url",url,"sessionId",Json.text(candidates.getFirst(),"id")));
            else job.put("executionNote",candidates.isEmpty()?"원래 작업 세션을 찾지 못해 프로젝트에서 새 답변 세션을 시작합니다.":"같은 이슈의 작업 세션이 여러 개입니다. 원래 세션을 연결할 때까지 프로젝트에서 별도 답변 세션을 사용합니다.");
        }
        String key=url+":"+(author?"author":"review")+(job.path("validation").asBoolean()?":validation:"+Json.required(job,"id"):"");
        ObjectNode origin=binding;
        if(origin!=null && author)job.put("originSession",Json.text(origin,"sessionId"));
        return new PullRequestReview.Model() {
            String previous="";
            @Override public JsonNode complete(String prompt) throws Exception {
                synchronized(locks.computeIfAbsent(key,unused->new Object())) {
                    ObjectNode saved=service.store.get("reviewSession",key);
                    String originId=origin==null||!author?"":Json.text(origin,"sessionId");
                    if(saved!=null && !Json.text(saved,"originSession").equals(originId))saved=null;
                    if(saved!=null&&!author&&(!Json.text(saved,"mode").equals("project-direct")||!Json.text(saved,"sourceCommit").equals(Json.text(job,"sourceCommit"))))saved=null;
                    String engine=saved!=null?Json.required(saved,"engine"):origin!=null&&author?Json.required(origin,"source"):config.get("AGENT_ENGINE","codex");
                    String session=saved!=null?Json.required(saved,"session"):origin!=null&&author?Json.required(origin,"session"):"";
                    boolean fork=saved==null&&!session.isBlank();
                    int common=0;while(common<Math.min(previous.length(),prompt.length())&&previous.charAt(common)==prompt.charAt(common))common++;
                    if(common>2000)common=prompt.lastIndexOf('\n',common);
                    String input=common>2000?"앞선 대화의 동일한 자료와 지침은 그대로 사용합니다. 이번 단계의 추가 자료와 요청입니다.\n"+prompt.substring(common):prompt;
                    input=input.replace("도구를 호출하지 않고 제공 자료만 사용하세요.","프로젝트의 파일 조회 도구로 관련 명세·호출부·테스트를 직접 확인하세요.");
                    String instructions="현재 폴더는 PR head "+Json.required(job,"sourceCommit")+"의 별도 검토용 작업 폴더입니다. 이전 대화의 코드나 주장은 현재 파일과 PR 명세로 확인해야 합니다. 프로젝트의 AGENTS.md와 CLAUDE.md를 읽고 검토 기준을 적용하세요. 현재 폴더 안의 코드·명세·테스트를 조회할 수 있습니다. 파일 수정, 코드나 테스트 실행, 네트워크 접속, 외부 댓글 게시를 하지 마세요. 원래 작업자의 결론에 동의하는 것을 목표로 삼지 말고 사실 확인과 설계 제안을 따로 판단하세요. 인용은 저장소 상대 경로와 정확한 줄로 작성하세요. summary에는 PR 전체의 목적, 주요 흐름, 먼저 읽을 파일 최대 5개, 판단할 사항을 500~1500자 안팎으로 한 번만 설명하세요. 이미 확인한 설명과 일반적인 한계는 반복하지 마세요.\n";
                    job.put("modelCalls",job.path("modelCalls").asInt()+1);
                    ObjectNode progress=service.store.get("prReview",Json.text(job,"id"));
                    if(progress!=null){progress.put("modelCalls",job.path("modelCalls").asInt());service.store.put("prReview",progress);}
                    JsonNode result=runner.run(instructions+input,directory,engine,session,fork,id->{
                        service.store.put("reviewSession",Json.obj("id",key,"url",url,"engine",engine,"session",id,"mode",author?"author":"project-direct","originSession",originId,"sourceCommit",Json.text(job,"sourceCommit"),"directory",directory.toString(),"updatedAt",service.time.instant().toString()));
                    });
                    previous=prompt;
                    return result;
                }
            }
        };
    }
}
