package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectReviewModelTest {
    @TempDir Path temporary;
    Store store;WorkService service;Config config;Path clone;String base,head;
    @BeforeEach void open() throws Exception {
        clone=Files.createDirectory(temporary.resolve("repo"));
        ReviewWorkspaces.git(clone,"init");ReviewWorkspaces.git(clone,"config","user.email","test@example.invalid");ReviewWorkspaces.git(clone,"config","user.name","test");
        ReviewWorkspaces.git(clone,"remote","add","origin","https://test-user@bitbucket.org/team/repo.git");
        Files.writeString(clone.resolve("file.txt"),"before\n");ReviewWorkspaces.git(clone,"add",".");ReviewWorkspaces.git(clone,"commit","-m","base");base=ReviewWorkspaces.git(clone,"rev-parse","HEAD").trim();
        Files.writeString(clone.resolve("file.txt"),"after\n");ReviewWorkspaces.git(clone,"commit","-am","head");head=ReviewWorkspaces.git(clone,"rev-parse","HEAD").trim();
        ReviewWorkspaces.git(clone,"checkout","--detach",base);Files.writeString(clone.resolve("owner-uncommitted.txt"),"keep me");
        config=new Config(temporary.resolve("none"),Map.of("WORK_AGENT_DATA",temporary.resolve("data").toString(),"REPOSITORY_ROOT",temporary.toString(),"AGENT_ENGINE","claude"));
        store=new Store(temporary.resolve("work.db"));service=new WorkService(store,new KoreaTime(Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"),ZoneOffset.UTC)),new JiraClient(config));
    }
    @AfterEach void close() throws Exception {store.close();}
    ObjectNode job(String kind){return Json.obj("id",UUID.randomUUID().toString(),"kind",kind,"url","https://bitbucket.org/team/repo/pull-requests/1","sourceCommit",head,"destinationCommit",base);}
    ObjectNode metadata(){return Json.obj("title","CT-42 기능 개선","source",Json.obj("repository",Json.obj("full_name","team/repo"),"branch",Json.obj("name","feature/CT-42")));}
    @Test void abbreviatedApiHashesPrepareAndReuseTheExactPinnedWorktree()throws Exception {
        BitbucketClient api=new BitbucketClient(config){
            @Override JsonNode pullRequest(PullRequest pr){ObjectNode value=metadata();((ObjectNode)value.path("source")).set("commit",Json.obj("hash",head.substring(0,12)));value.set("destination",Json.obj("repository",Json.obj("full_name","team/repo"),"commit",Json.obj("hash",base.substring(0,12))));return value;}
            @Override JsonNode get(String path){return Json.obj("hash",path.endsWith(head.substring(0,12))?head:base);}
        };
        JsonNode metadata=api.reviewPullRequest(BitbucketClient.PullRequest.parse("https://bitbucket.org/team/repo/pull-requests/1"));
        ObjectNode job=job("review");job.put("sourceCommit",Json.text(metadata.path("source").path("commit"),"hash")).put("destinationCommit",Json.text(metadata.path("destination").path("commit"),"hash"));
        ReviewWorkspaces workspaces=new ReviewWorkspaces(config,store);Path directory=workspaces.prepare(job,metadata);assertEquals(head,ReviewWorkspaces.git(directory,"rev-parse","HEAD").trim());assertEquals(directory,workspaces.prepare(job,metadata));
        assertEquals(base,ReviewWorkspaces.git(clone,"rev-parse","HEAD").trim());assertEquals("keep me",Files.readString(clone.resolve("owner-uncommitted.txt")));
    }
    @Test void reviewUsesAPinnedSeparateWorktreeAndKeepsTheOwnerCheckoutAndUncommittedFiles() throws Exception {
        ProjectReviewModel model=new ProjectReviewModel(service,config,prompt->{fail("must use project session");return null;},(prompt,directory,engine,session,fork,saved)->{
            assertEquals(head,ReviewWorkspaces.git(directory,"rev-parse","HEAD").trim());assertEquals("after\n",Files.readString(directory.resolve("file.txt")));
            assertTrue(prompt.contains("호출부·테스트를 직접 확인"));assertFalse(fork);saved.accept("new-session");return Json.obj("summary","목적과 읽을 파일 안내");
        });
        ObjectNode job=job("review");model.forReview(job,metadata()).complete("도구를 호출하지 않고 제공 자료만 사용하세요.");
        assertEquals("project-session",Json.text(job,"executionMode"));
        assertEquals(base,ReviewWorkspaces.git(clone,"rev-parse","HEAD").trim());assertEquals("keep me",Files.readString(clone.resolve("owner-uncommitted.txt")));
        assertEquals("new-session",Json.text(store.list("reviewSession").getFirst(),"session"));
    }
    @Test void authorReplyForksTheOriginalToolSessionThenResumesTheReplySession() throws Exception {
        store.put("session",Json.obj("id","codex:original","source","codex","session","original","cwd",clone.toString(),"name","CT-42 기능 개선"));
        List<String> sessions=new ArrayList<>();List<Boolean> forks=new ArrayList<>();
        ProjectReviewModel model=new ProjectReviewModel(service,config,prompt->Json.object(),(prompt,directory,engine,session,fork,saved)->{
            assertEquals("codex",engine);sessions.add(session);forks.add(fork);saved.accept("reply-session");return Json.object();
        });
        PullRequestReview.Model execution=model.forReview(job("reply"),metadata());execution.complete("첫 질문");execution.complete("추가 질문");
        model.forReview(job("question"),metadata()).complete("재시작 후 질문");
        assertEquals(List.of("original","reply-session","reply-session"),sessions);assertEquals(List.of(true,false,false),forks);
    }
    @Test void ambiguousOriginalSessionsAreNotChosenAndValidationUsesADifferentConversation() throws Exception {
        for(String id:List.of("one","two"))store.put("session",Json.obj("id","claude:"+id,"source","claude","session",id,"cwd",clone.toString(),"name","CT-42 작업"));
        ProjectReviewModel model=new ProjectReviewModel(service,config,prompt->Json.object(),(prompt,directory,engine,session,fork,saved)->{assertEquals("",session);assertFalse(fork);saved.accept(UUID.randomUUID().toString());return Json.object();});
        ObjectNode job=job("reply");model.forReview(job,metadata()).complete("답변");assertEquals(2,job.path("originSessionCandidates").size());assertTrue(store.list("prSessionBinding").isEmpty());
        model.forReview(job("review"),metadata()).complete("리뷰");model.forReview(job("review").put("validation",true),metadata()).complete("검증");
        assertEquals(3,store.list("reviewSession").size());
    }
    @Test void repeatedInputIsOmittedOnlyAtALineBoundaryWithoutCuttingJsonInHalf() throws Exception {
        List<String> inputs=new ArrayList<>();
        ProjectReviewModel model=new ProjectReviewModel(service,config,prompt->Json.object(),(prompt,directory,engine,session,fork,saved)->{inputs.add(prompt);saved.accept("retained");return Json.object();});
        PullRequestReview.Model execution=model.forReview(job("review"),metadata());String shared="공통 자료\n"+"a".repeat(2500)+"\n";
        execution.complete(shared+"{\"file\":\"first\"}\n검토하세요.");execution.complete(shared+"{\"file\":\"second\"}\n검토하세요.");
        assertTrue(inputs.get(1).contains("{\"file\":\"second\"}"));assertFalse(inputs.get(1).contains("a".repeat(2500)));
    }
    @Test void nativeCliCanReadThePinnedProjectAndResumeItsSavedConversation() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("work.native.smoke"));
        Config installed=new Config(Path.of("config.properties"));
        Config nativeConfig=new Config(temporary.resolve("none"),Map.of("WORK_AGENT_DATA",temporary.resolve("native-data").toString(),"REPOSITORY_ROOT",temporary.toString(),"AGENT_ENGINE",installed.get("AGENT_ENGINE","claude"),"AGENT_MODEL",installed.get("AGENT_MODEL",""),"REVIEW_TIMEOUT_SECONDS","120"));
        ProjectReviewModel model=new ProjectReviewModel(service,new CliModel(nativeConfig),nativeConfig);
        String request="현재 작업 폴더의 file.txt를 파일 조회 도구로 읽으세요. summary에는 확인한 파일 이름을 쓰고 reply에는 파일의 첫 줄만 그대로 쓰세요. replyKind=fact,replyConfidence=high로 쓰고 replyEvidence에는 파일 첫 줄을 인용하세요. findings,relatedPaths,limitations는 빈 배열로 출력하세요.";
        JsonNode first=model.forReview(job("review"),metadata()).complete(request);
        assertEquals("after",Json.text(first,"reply").trim());
        String session=Json.required(store.list("reviewSession").getFirst(),"session");
        JsonNode next=model.forReview(job("question"),metadata()).complete("앞선 대화에서 읽은 file.txt의 첫 줄을 다시 reply에만 쓰세요. replyKind=fact,replyConfidence=high로 쓰고 replyEvidence에 첫 줄을 인용하세요. findings,relatedPaths,limitations는 비우고 summary에는 이 질문의 요지를 쓰세요.");
        assertEquals("after",Json.text(next,"reply").trim());assertEquals(session,Json.text(store.list("reviewSession").getFirst(),"session"));
        assertTrue(ReviewWorkspaces.git(Path.of(Json.required(store.list("reviewSession").getFirst(),"directory")),"status","--porcelain").isBlank());
    }
}
