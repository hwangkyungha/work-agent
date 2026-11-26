package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Native, persistent PR conversations in pinned local worktrees, with read tools enabled. */
final class ProjectReviewModel implements PullRequestReview.Model {
    static final String SESSION_MARKER = "work-agent PR 검토 세션";
    private final WorkService service;
    @FunctionalInterface interface SessionRunner { JsonNode run(String prompt, Path directory, List<Path> materials, String engine, String session, boolean fork, java.util.function.Consumer<String> saved) throws Exception; }
    private final PullRequestReview.Model fallback;
    private final SessionRunner runner;
    private final Config config;
    final ReviewWorkspaces workspaces;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    ProjectReviewModel(WorkService service, CliModel cli, Config config) {
        this(service, config, cli::review, cli::reviewInSession);
    }
    ProjectReviewModel(WorkService service, Config config, PullRequestReview.Model fallback, SessionRunner runner) {
        this.service=service;this.config=config;this.fallback=fallback;this.runner=runner;this.workspaces=new ReviewWorkspaces(config,service.store,service.time::instant);
    }
    @Override public JsonNode complete(String prompt) throws Exception { return fallback.complete(prompt); }
    @Override public String equivalentBase(ObjectNode previous, ObjectNode current, JsonNode metadata) throws Exception {
        if (!ReviewReuse.comparable(previous) || !Json.text(previous,"reviewBackground").equals(Json.text(current,"reviewBackground"))) return "";
        Path clone = workspaces.repositoryWithCommits(current,metadata,workspaces.localRepository(current));
        return ReviewReuse.equivalentBase(clone,previous,current);
    }
    @Override public boolean sameApprovedChange(ObjectNode approved, ObjectNode current, JsonNode metadata) throws Exception {
        try {
            Path local = workspaces.localRepository(current);
            Path clone = workspaces.repositoryWithCommits(current, metadata, local);
            workspaces.repositoryWithCommits(approved, metadata, local);
            return ReviewReuse.sameApprovedChange(clone, approved, current);
        } catch (java.io.IOException | IllegalStateException unavailable) { return false; }
    }
    @Override public void cleanWorkspaces() throws Exception { workspaces.clean(); }
    ObjectNode bind(JsonNode input) throws Exception {
        String url=BitbucketClient.PullRequest.parse(Json.required(input,"url")).url(),id=Json.text(input,"sessionId");
        if(id.isBlank()){service.store.delete("prSessionBinding",url);return Json.obj("url",url);}
        ObjectNode session=service.store.get("session",id);
        if(session==null || Json.text(session,"session").isBlank())throw new IllegalArgumentException("원래 작업 세션을 찾지 못했습니다. CLI 기록을 먼저 동기화하세요.");
        return service.store.put("prSessionBinding",Json.obj("id",url,"url",url,"sessionId",id,"source",Json.required(session,"source"),"session",Json.required(session,"session")));
    }
    @Override public PullRequestReview.Model forReview(ObjectNode job, JsonNode metadata) throws Exception {
        String url=Json.required(job,"url"),kind=Json.text(job,"kind");
        Path local = workspaces.localRepository(job);
        ObjectNode binding=service.store.get("prSessionBinding",url);
        boolean author=job.path("ownPr").asBoolean() && (kind.equals("reply")||kind.equals("question"));
        if(author && binding==null) {
            List<ObjectNode> candidates=local==null?List.of():new SessionCatalog(service.store).candidates(metadata,local);
            job.set("originSessionCandidates",Json.MAPPER.valueToTree(candidates.stream().map(row->Json.obj("id",Json.text(row,"id"),"name",Json.text(row,"name"))).toList()));
            if(candidates.size()==1)binding=bind(Json.obj("url",url,"sessionId",Json.text(candidates.getFirst(),"id")));
            else job.put("executionNote",candidates.isEmpty()?"원래 작업 세션을 찾지 못해 프로젝트에서 새 답변 세션을 시작합니다.":"같은 이슈의 작업 세션이 여러 개입니다. 원래 세션을 연결할 때까지 프로젝트에서 별도 답변 세션을 사용합니다.");
        }
        String key=url+":"+(author?"author":"review")+(job.path("validation").asBoolean()?":validation:"+Json.required(job,"id"):"");
        ObjectNode origin=binding;
        if(origin!=null && author)job.put("originSession",Json.text(origin,"sessionId"));
        ObjectNode original = origin == null || !job.path("ownPr").asBoolean() ? null : service.store.get("session", Json.text(origin,"sessionId"));
        ReviewWorkspaces.Lease lease = workspaces.new Lease(job);
        Path directory;
        try {
            directory = workspaces.prepare(job, metadata, local, original == null ? "" : Json.text(original, "cwd"), lease);
            if (kind.equals("review")) workspaces.prepareMaterials(job, lease);
        } catch (Exception error) {
            lease.close();
            throw error;
        }
        boolean ownerCheckout = Json.text(job, "reviewWorkspaceMode").equals("owner");
        List<Path> materials = kind.equals("review")
                ? List.of(Path.of(Json.required(job, "baseDirectory")), Path.of(Json.required(job, "reviewInputDirectory"))) : List.of();
        return new PullRequestReview.Model() {
            String previous="";
            @Override public void close() { lease.close(); }
            private void checkOwnerCheckout() throws Exception {
                if (ownerCheckout && !ReviewWorkspaces.cleanAt(directory, Json.required(job,"sourceCommit"))) {
                    throw new IllegalStateException("검토하는 동안 내 작업 폴더의 커밋 또는 파일이 바뀌었습니다. 다시 검토하세요.");
                }
            }
            @Override public JsonNode complete(String prompt) throws Exception {
                synchronized(locks.computeIfAbsent(key,unused->new Object())) {
                    checkOwnerCheckout();
                    ObjectNode saved=service.store.get("reviewSession",key);
                    String originId=origin==null||!author?"":Json.text(origin,"sessionId");
                    if(saved!=null && !Json.text(saved,"originSession").equals(originId))saved=null;
                    if(saved!=null && !Json.text(saved,"directory").equals(directory.toString()))saved=null;
                    if(saved!=null&&!author&&(!Json.text(saved,"mode").equals("project-direct")||!Json.text(saved,"sourceCommit").equals(Json.text(job,"sourceCommit"))))saved=null;
                    String engine=origin!=null&&author&&!config.reviewEngine().equals("off")?Json.required(origin,"source"):config.reviewEngine();
                    if(saved!=null&&!engine.equals(Json.text(saved,"engine")))saved=null;
                    String session=saved!=null?Json.required(saved,"session"):origin!=null&&author?Json.required(origin,"session"):"";
                    boolean fork=saved==null&&!session.isBlank();
                    int common=0;while(common<Math.min(previous.length(),prompt.length())&&previous.charAt(common)==prompt.charAt(common))common++;
                    if(common>2000)common=prompt.lastIndexOf('\n',common);
                    String input=common>2000?"앞선 대화의 동일한 자료와 지침은 그대로 사용합니다. 이번 단계의 추가 자료와 요청입니다.\n"+prompt.substring(common):prompt;
                    input=input.replace("도구를 호출하지 않고 제공 자료만 사용하세요.","프로젝트의 파일 조회 도구로 관련 명세·호출부·테스트를 직접 확인하세요.");
                    String instructions=SESSION_MARKER+"\n현재 폴더는 PR head "+Json.required(job,"sourceCommit")+"를 체크아웃한 작업 폴더입니다. 이전 대화의 코드나 주장은 현재 파일과 PR 명세로 확인해야 합니다. 프로젝트의 AGENTS.md와 CLAUDE.md를 읽고 검토 기준을 적용하세요. 현재 폴더 안의 코드·명세·테스트를 조회할 수 있습니다. 파일 수정, 코드나 테스트 실행, 네트워크 접속, 외부 댓글 게시를 하지 마세요. 원래 작업자의 결론에 동의하는 것을 목표로 삼지 말고 사실 확인과 설계 제안을 따로 판단하세요. 인용은 저장소 상대 경로와 정확한 줄로 작성하세요.\n";
                    if(kind.equals("review"))instructions+="PR 전체를 검토하는 단계의 summary는 업무 수첩용입니다. 목적·주요 변경·먼저 읽을 파일·확인한 범위·남은 판단을 Markdown 소제목과 문단으로 구분하세요. 먼저 읽을 파일은 최대 5개입니다. 이미 확인한 설명과 일반적인 한계는 반복하지 마세요.\n";
                    job.put("modelCalls",job.path("modelCalls").asInt()+1);
                    ObjectNode progress=service.store.get("prReview",Json.text(job,"id"));
                    if(progress!=null){progress.put("modelCalls",job.path("modelCalls").asInt());service.store.put("prReview",progress);}
                    JsonNode result=runner.run(instructions+input,directory,materials,engine,session,fork,id->{
                        service.store.put("reviewSession",Json.obj("id",key,"url",url,"engine",engine,"session",id,"mode",author?"author":"project-direct","originSession",originId,"sourceCommit",Json.text(job,"sourceCommit"),"directory",directory.toString(),"updatedAt",service.time.instant().toString()));
                    });
                    checkOwnerCheckout();
                    previous=prompt;
                    return result;
                }
            }
        };
    }
}
