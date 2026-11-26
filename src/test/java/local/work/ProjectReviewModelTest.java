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
    @Test void comparesTheApprovalWithARebaseAndDetectsAnAdditionalChange() throws Exception {
        Files.writeString(clone.resolve("file.txt"), "original\n" + "context\n".repeat(12) + "before target\n");
        ReviewWorkspaces.git(clone, "commit", "-am", "shared base");
        String originalBase = ReviewWorkspaces.git(clone, "rev-parse", "HEAD").strip();
        Files.writeString(clone.resolve("file.txt"), "approved\n" + "context\n".repeat(12) + "before target\n");
        ReviewWorkspaces.git(clone, "commit", "-am", "approved change");
        String approvedHead = ReviewWorkspaces.git(clone, "rev-parse", "HEAD").strip();
        ReviewWorkspaces.git(clone, "checkout", "--detach", originalBase);
        Files.writeString(clone.resolve("file.txt"), "original\n" + "context\n".repeat(12) + "updated target\n");
        ReviewWorkspaces.git(clone, "commit", "-am", "target advanced");
        String newBase = ReviewWorkspaces.git(clone, "rev-parse", "HEAD").strip();
        Files.writeString(clone.resolve("file.txt"), "approved\n" + "context\n".repeat(12) + "updated target\n");
        ReviewWorkspaces.git(clone, "commit", "-am", "rebased approved change");
        ObjectNode current = Json.obj("sourceCommit", ReviewWorkspaces.git(clone, "rev-parse", "HEAD").strip(), "destinationCommit", newBase);
        ObjectNode approved = Json.obj("sourceCommit", approvedHead, "destinationCommit", originalBase);
        assertTrue(ReviewReuse.sameApprovedChange(clone, approved, current));
        Files.writeString(clone.resolve("file.txt"), "different\n" + "context\n".repeat(12) + "updated target\n");
        ReviewWorkspaces.git(clone, "commit", "-am", "additional change");
        current.put("sourceCommit", ReviewWorkspaces.git(clone, "rev-parse", "HEAD").strip());
        assertFalse(ReviewReuse.sameApprovedChange(clone, approved, current));
    }
    ObjectNode job(String kind){return Json.obj("id",UUID.randomUUID().toString(),"kind",kind,"ownPr",kind.equals("reply"),"url","https://bitbucket.org/team/repo/pull-requests/1","sourceCommit",head,"destinationCommit",base);}
    ObjectNode metadata(){return Json.obj("title","CT-42 기능 개선","source",Json.obj("repository",Json.obj("full_name","team/repo"),"branch",Json.obj("name","feature/CT-42")));}
    BitbucketClient projectApi(int[] sourceReads){
        return new BitbucketClient(config){
            @Override JsonNode pullRequest(PullRequest pr){ObjectNode value=metadata();value.put("state","OPEN");((ObjectNode)value.path("source")).set("commit",Json.obj("hash",head));value.set("destination",Json.obj("commit",Json.obj("hash",base),"repository",Json.obj("full_name","team/repo")));return value;}
            @Override JsonNode get(String path){return path.equals("/user")?Json.obj("uuid","owner"):Json.obj("hash",base);}
            @Override List<JsonNode> pages(String path){return List.of(Json.obj("old",Json.obj("path","file.txt"),"new",Json.obj("path","file.txt"),"lines_added",1,"lines_removed",1));}
            @Override List<JsonNode> comments(PullRequest pr){return List.of();}
            @Override String request(String method,String path,JsonNode body){return "diff --git a/file.txt b/file.txt\n--- a/file.txt\n+++ b/file.txt\n@@ -1 +1 @@\n-before\n+after\n";}
            @Override String source(String repository,String commit,String path){sourceReads[0]++;return "WHOLE_FILE_PAYLOAD".repeat(2500);}
        };
    }
    @Test void projectReviewExploresFilesWithoutReceivingWholeFileBatches()throws Exception {
        int[] sourceReads={0},calls={0};
        BitbucketClient api=projectApi(sourceReads);
        ProjectReviewModel model=new ProjectReviewModel(service,config,prompt->Json.object(),(prompt,directory,materials,engine,session,fork,saved)->{
            assertEquals("claude",engine);
            assertEquals("opus",config.reviewModel(engine));
            calls[0]++;if(prompt.contains("WHOLE_FILE_PAYLOAD"))throw new IllegalStateException("whole file content was sent");
            assertTrue(prompt.contains(head));return Json.obj("summary","PR 전체 안내","slackSummary","Slack 변경 요약","modelInfo",Json.obj("engine","claude","requested","opus","reported",List.of("claude-opus-5-5")),"findings",List.of(),"limitations",List.of());
        });
        try(PullRequestReview reviews=new PullRequestReview(service,api,model)) {
            ObjectNode row=reviews.request("https://bitbucket.org/team/repo/pull-requests/1",0,false);long until=System.nanoTime()+5_000_000_000L;
            do{row=store.get("prReview",Json.text(row,"id"));if(!PullRequestReview.inProgress(row))break;Thread.sleep(10);}while(System.nanoTime()<until);
            assertEquals("ready",Json.text(row,"status"));assertEquals(0,sourceReads[0]);assertEquals(1,calls[0]);assertEquals("PR 전체 안내",Json.text(row,"readingGuide"));
            assertEquals("Slack 변경 요약",Json.text(row,"slackSummary"));
            assertEquals("claude-opus-5-5",row.path("modelInfo").path("reported").get(0).asText());
        }
    }
    @Test void projectFindingsAreRecheckedAndCitationsAreVerifiedFromPinnedFiles()throws Exception {
        int[] reads={0},calls={0};BitbucketClient api=projectApi(reads);
        ObjectNode finding=Json.obj("path","file.txt","side","new","line",1,"title","확인할 결과","comment","발생 조건과 영향 설명","confidence","high","severity","medium","trigger","입력","expected","before","actual","after","reasoning","검증 근거","impact","결과 변경","fix","계약 확인","evidence",List.of(Json.obj("path","file.txt","side","new","line",1,"quote","after"),Json.obj("path","file.txt","side","old","line",1,"quote","before")));
        ProjectReviewModel model=new ProjectReviewModel(service,config,prompt->Json.object(),(prompt,directory,materials,engine,session,fork,saved)->{calls[0]++;if(calls[0]==2){assertTrue(prompt.contains("반박"));assertFalse(prompt.contains("WHOLE_FILE_PAYLOAD"));}return Json.obj("summary","전체 안내","findings",List.of(finding),"limitations",List.of());});
        try(PullRequestReview reviews=new PullRequestReview(service,api,model)) {
            ObjectNode row=reviews.request("https://bitbucket.org/team/repo/pull-requests/1",0,false);long until=System.nanoTime()+5_000_000_000L;
            do{row=store.get("prReview",Json.text(row,"id"));if(!PullRequestReview.inProgress(row))break;Thread.sleep(10);}while(System.nanoTime()<until);
            assertEquals("ready",Json.text(row,"status"));assertEquals(1,row.path("findings").size());assertEquals(2,calls[0]);assertEquals(0,reads[0]);assertEquals("project-session",Json.text(row,"executionMode"));
        }
    }
    @Test void abbreviatedApiHashesPrepareAndReuseTheExactPinnedWorktree()throws Exception {
        BitbucketClient api=new BitbucketClient(config){
            @Override JsonNode pullRequest(PullRequest pr){ObjectNode value=metadata();((ObjectNode)value.path("source")).set("commit",Json.obj("hash",head.substring(0,12)));value.set("destination",Json.obj("repository",Json.obj("full_name","team/repo"),"commit",Json.obj("hash",base.substring(0,12))));return value;}
            @Override JsonNode get(String path){return Json.obj("hash",path.endsWith(head.substring(0,12))?head:base);}
        };
        JsonNode metadata=api.reviewPullRequest(BitbucketClient.PullRequest.parse("https://bitbucket.org/team/repo/pull-requests/1"));
        ObjectNode job=job("review");job.put("sourceCommit",Json.text(metadata.path("source").path("commit"),"hash")).put("destinationCommit",Json.text(metadata.path("destination").path("commit"),"hash"));
        ReviewWorkspaces workspaces = new ReviewWorkspaces(config,store,service.time::instant);
        try (var lease = workspaces.new Lease(job)) {
            Path directory = workspaces.prepare(job,metadata,clone,"",lease);
            assertEquals(head,ReviewWorkspaces.git(directory,"rev-parse","HEAD").trim());
            assertEquals(directory,workspaces.prepare(job,metadata,clone,"",lease));
        }
        assertEquals(base,ReviewWorkspaces.git(clone,"rev-parse","HEAD").trim());assertEquals("keep me",Files.readString(clone.resolve("owner-uncommitted.txt")));
    }
    @Test void reviewUsesAPinnedSeparateWorktreeAndKeepsTheOwnerCheckoutAndUncommittedFiles() throws Exception {
        ProjectReviewModel model=new ProjectReviewModel(service,config,prompt->{fail("must use project session");return null;},(prompt,directory,materials,engine,session,fork,saved)->{
            assertEquals(head,ReviewWorkspaces.git(directory,"rev-parse","HEAD").trim());assertEquals("after\n",Files.readString(directory.resolve("file.txt")));
            assertTrue(prompt.contains("호출부·테스트를 직접 확인"));assertFalse(fork);saved.accept("new-session");return Json.obj("summary","목적과 읽을 파일 안내");
        });
        ObjectNode job=job("review");model.forReview(job,metadata()).complete("도구를 호출하지 않고 제공 자료만 사용하세요.");
        assertEquals("project-session",Json.text(job,"executionMode"));
        assertEquals(base,ReviewWorkspaces.git(clone,"rev-parse","HEAD").trim());assertEquals("keep me",Files.readString(clone.resolve("owner-uncommitted.txt")));
        assertEquals("new-session",Json.text(store.list("reviewSession").getFirst(),"session"));
    }
    @Test void retriesInterruptedCheckoutInANewDirectoryAndKeepsExistingFiles() throws Exception {
        ReviewWorkspaces workspaces = new ReviewWorkspaces(config,store,service.time::instant);
        Path isolated = workspaces.repositoryWithCommits(job("review"),metadata(),clone);
        Path interrupted = config.data().resolve("review-worktrees").resolve(TranscriptImporter.hash("team/repo")).resolve("isolated").resolve(head);
        Files.createDirectories(interrupted.getParent());
        ReviewWorkspaces.git(isolated,"worktree","add","--no-checkout","--detach",interrupted.toString(),head);
        Path metadataDir = Path.of(ReviewWorkspaces.git(interrupted,"rev-parse","--absolute-git-dir").trim());
        assertFalse(Files.exists(metadataDir.resolve("index")));
        Files.writeString(metadataDir.resolve("locked"),"initializing\n");
        Files.createFile(metadataDir.resolve("index.lock"));
        Files.writeString(interrupted.resolve("file.txt"),"partial");

        ObjectNode job = job("review");
        Path recovered = prepare(workspaces,job);
        assertNotEquals(interrupted,recovered);
        assertEquals(recovered,prepare(workspaces,job));
        assertEquals(recovered.toString(),Json.text(job,"reviewDirectory"));
        assertEquals(head,ReviewWorkspaces.git(recovered,"rev-parse","HEAD").trim());
        assertEquals("after\n",Files.readString(recovered.resolve("file.txt")));
        assertTrue(ReviewWorkspaces.git(recovered,"status","--porcelain").isBlank());
        assertEquals("partial",Files.readString(interrupted.resolve("file.txt")));

        Files.writeString(recovered.resolve("file.txt"),"user change");
        assertNotEquals(recovered,prepare(workspaces,job));
        assertEquals("user change",Files.readString(recovered.resolve("file.txt")));
        assertEquals(base,ReviewWorkspaces.git(clone,"rev-parse","HEAD").trim());
        assertEquals("keep me",Files.readString(clone.resolve("owner-uncommitted.txt")));
    }
    private Path prepare(ReviewWorkspaces workspaces, ObjectNode job) throws Exception {
        try (var lease = workspaces.new Lease(job)) {
            return workspaces.prepare(job, metadata(), clone, "", lease);
        }
    }

    @Test void usesExistingCheckoutOnlyForOwnPrAtTheExactCleanHead() throws Exception {
        ReviewWorkspaces workspaces = new ReviewWorkspaces(config,store,service.time::instant);
        Path owner = temporary.resolve("owner");
        ReviewWorkspaces.git(clone,"worktree","add","--detach",owner.toString(),head);
        String registered = ReviewWorkspaces.git(clone,"worktree","list","--porcelain");
        assertEquals(owner.toRealPath(),prepare(workspaces,job("review").put("ownPr",true)).toRealPath());
        Path isolated = prepare(workspaces,job("review"));
        assertNotEquals(owner.toRealPath(),isolated.toRealPath());
        Files.writeString(owner.resolve("untracked.txt"),"my draft");
        assertEquals(isolated,prepare(workspaces,job("reply")));
        assertEquals(registered,ReviewWorkspaces.git(clone,"worktree","list","--porcelain"));
        assertEquals("my draft",Files.readString(owner.resolve("untracked.txt")));
    }
    @Test void protectsActiveCheckoutThenExpiresAndRecreatesItFromPinnedObjects() throws Exception {
        Instant start = Instant.parse("2026-11-09T00:00:00Z");
        var now = new java.util.concurrent.atomic.AtomicReference<>(start);
        ReviewWorkspaces workspaces = new ReviewWorkspaces(config,store,now::get);
        ObjectNode job = job("review");
        Path isolated;
        try (var lease = workspaces.new Lease(job)) {
            isolated = workspaces.prepare(job,metadata(),clone,"",lease);
            workspaces.prepareMaterials(job,lease);
            now.set(start.plus(Duration.ofHours(25)));
            workspaces.clean();
            assertTrue(Files.isDirectory(isolated));
        }
        Path basePath = Path.of(Json.required(job,"baseDirectory"));
        Path inputs = Path.of(Json.required(job,"reviewInputDirectory"));
        Files.writeString(inputs.resolve("changes.patch"),"patch");
        now.set(start.plus(Duration.ofHours(48)));
        workspaces.clean();
        assertTrue(Files.isDirectory(isolated));
        now.set(start.plus(Duration.ofHours(49)));
        workspaces.clean();
        assertTrue(Files.notExists(isolated));
        assertTrue(Files.notExists(basePath));
        assertTrue(Files.notExists(inputs));
        try (var lease = workspaces.new Lease(job)) {
            assertEquals(isolated,workspaces.prepare(job,metadata(),null,"",lease));
            assertEquals(head,ReviewWorkspaces.git(isolated,"rev-parse","HEAD").trim());
        }
    }
    @Test void keepsSharedWorktreeUntilBothPrsCloseAndPreservesLockedOrDirtyCheckouts() throws Exception {
        ReviewWorkspaces workspaces = new ReviewWorkspaces(config,store,service.time::instant);
        ObjectNode first = job("review");
        ObjectNode second = job("review").put("url",Json.required(first,"url").replace("/1","/2"));
        Path shared = prepare(workspaces,first);
        assertEquals(shared,prepare(workspaces,second));
        Path git = Path.of(Json.required(first,"reviewRepository"));
        store.put("prLifecycle",Json.obj("id",Json.required(first,"url"),"state","MERGED"));
        workspaces.clean();
        assertTrue(Files.isDirectory(shared));
        store.put("prLifecycle",Json.obj("id",Json.required(second,"url"),"state","DECLINED"));
        ReviewWorkspaces.git(git,"worktree","lock",shared.toString());
        workspaces.clean();
        assertTrue(Files.isDirectory(shared));
        ReviewWorkspaces.git(git,"worktree","unlock",shared.toString());
        Path draft = Files.writeString(shared.resolve("unsaved.txt"),"preserve");
        workspaces.clean();
        assertEquals("preserve",Files.readString(draft));
        Files.delete(draft);
        workspaces.clean();
        assertTrue(Files.notExists(shared));
    }
    @Test void automaticReviewReusesUnchangedInputsAfterRebaseAndChangeCleanup() throws Exception {
        Path plan = clone.resolve("openspec/changes/example/brief.md");
        Files.createDirectories(plan.getParent());
        Files.writeString(plan,"working notes");
        Files.writeString(clone.resolve("caller.go"),"unchanged caller");
        base = commitPaths("caller", "caller.go");
        String originalBase = base;
        Files.writeString(clone.resolve("file.txt"),"after\n");
        head = commitPaths("change with notes", "file.txt", "openspec/changes/example/brief.md");
        String url = "https://bitbucket.org/team/repo/pull-requests/1";
        int[] calls = {0};
        ProjectReviewModel model = new ProjectReviewModel(service,config,prompt->Json.object(),(prompt,dir,materials,engine,session,fork,saved)->{
            calls[0]++;
            return Json.obj("summary","review summary","findings",List.of(),"reviewedPaths",List.of("caller.go"));
        });
        BitbucketClient api = projectApi(new int[]{0});
        try (PullRequestReview reviews = new PullRequestReview(service,api,model)) {
            List<String> notices = new ArrayList<>();
            try (ReviewMonitor monitor = new ReviewMonitor(service,api,reviews,config,(text,review)->notices.add(text))) {
                ObjectNode first = awaitReview(reviews.request(url,0,true));
                assertEquals("ready",Json.text(first,"status"),Json.text(first,"error"));
                reviews.list.mark(Json.obj("id",Json.text(first,"id"),"reviewed",true));
                monitor.notifyCompleted();

                ReviewWorkspaces.git(clone,"checkout","--detach",originalBase);
                Files.writeString(clone.resolve("unrelated.txt"),"unrelated change");
                base = commitPaths("new baseline", "unrelated.txt");
                Files.writeString(clone.resolve("file.txt"),"after\n");
                head = commitPaths("same change without notes", "file.txt");
                ObjectNode reused = awaitReview(reviews.request(url,0,true));
                assertEquals(Json.text(first,"id"),Json.text(reused,"reusedReviewId"),Json.text(reused,"error"));
                assertEquals(head,Json.text(reused,"sourceCommit"));
                assertTrue(reviews.list.decorate(List.of(reused)).getFirst().path("userReviewed").asBoolean());
                assertEquals(1,calls[0]);
                monitor.notifyCompleted();
                assertEquals(1,notices.size());
                assertThrows(IllegalArgumentException.class,()->reviews.savePending(Json.text(reused,"id")));

                ReviewWorkspaces.git(clone,"checkout","--detach",base);
                Files.writeString(clone.resolve("caller.go"),"changed evidence");
                base = commitPaths("different caller", "caller.go");
                Files.writeString(clone.resolve("file.txt"),"after\n");
                head = commitPaths("same change with different caller", "file.txt");
                ObjectNode rerun = awaitReview(reviews.request(url,0,true));
                assertEquals("ready", Json.text(rerun,"status"),Json.text(rerun,"error"));
                assertFalse(rerun.has("reusedReviewId"));
                assertEquals(2,calls[0]);
            }
        }
    }
    private String commitPaths(String message, String... paths) throws Exception {
        List<String> add = new ArrayList<>(List.of("add","--"));
        add.addAll(List.of(paths));
        ReviewWorkspaces.git(clone,add.toArray(String[]::new));
        ReviewWorkspaces.git(clone,"commit","-m",message);
        return ReviewWorkspaces.git(clone,"rev-parse","HEAD").trim();
    }
    private ObjectNode awaitReview(ObjectNode job) throws Exception {
        String id = Json.required(job,"id");
        long deadline = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        ObjectNode row = store.get("prReview",id);
        while (PullRequestReview.inProgress(row) && System.nanoTime() < deadline) {
            Thread.sleep(20);
            row = store.get("prReview",id);
        }
        return row;
    }
    @Test void authorReplyForksTheOriginalToolSessionThenResumesTheReplySession() throws Exception {
        store.put("session",Json.obj("id","codex:original","source","codex","session","original","cwd",clone.toString(),"name","CT-42 기능 개선"));
        List<String> sessions=new ArrayList<>();List<Boolean> forks=new ArrayList<>();
        ProjectReviewModel model=new ProjectReviewModel(service,config,prompt->Json.object(),(prompt,directory,materials,engine,session,fork,saved)->{
            assertEquals("codex",engine);sessions.add(session);forks.add(fork);saved.accept("reply-session");return Json.object();
        });
        PullRequestReview.Model execution=model.forReview(job("reply"),metadata());execution.complete("첫 질문");execution.complete("추가 질문");
        model.forReview(job("question").put("ownPr",true),metadata()).complete("재시작 후 질문");
        assertEquals(List.of("original","reply-session","reply-session"),sessions);assertEquals(List.of(true,false,false),forks);
    }
    @Test void ambiguousOriginalSessionsAreNotChosenAndValidationUsesADifferentConversation() throws Exception {
        for(String id:List.of("one","two"))store.put("session",Json.obj("id","claude:"+id,"source","claude","session",id,"cwd",clone.toString(),"name","CT-42 작업"));
        ProjectReviewModel model=new ProjectReviewModel(service,config,prompt->Json.object(),(prompt,directory,materials,engine,session,fork,saved)->{assertEquals("",session);assertFalse(fork);saved.accept(UUID.randomUUID().toString());return Json.object();});
        ObjectNode job=job("reply");model.forReview(job,metadata()).complete("답변");assertEquals(2,job.path("originSessionCandidates").size());assertTrue(store.list("prSessionBinding").isEmpty());
        model.forReview(job("review"),metadata()).complete("리뷰");model.forReview(job("review").put("validation",true),metadata()).complete("검증");
        assertEquals(3,store.list("reviewSession").size());
    }
    @Test void repeatedInputIsOmittedOnlyAtALineBoundaryWithoutCuttingJsonInHalf() throws Exception {
        List<String> inputs=new ArrayList<>();
        ProjectReviewModel model=new ProjectReviewModel(service,config,prompt->Json.object(),(prompt,directory,materials,engine,session,fork,saved)->{inputs.add(prompt);saved.accept("retained");return Json.object();});
        PullRequestReview.Model execution=model.forReview(job("review"),metadata());String shared="공통 자료\n"+"a".repeat(2500)+"\n";
        execution.complete(shared+"{\"file\":\"first\"}\n검토하세요.");execution.complete(shared+"{\"file\":\"second\"}\n검토하세요.");
        assertTrue(inputs.get(1).contains("{\"file\":\"second\"}"));assertFalse(inputs.get(1).contains("a".repeat(2500)));
    }
    @Test void nativeCliCanReadThePinnedProjectAndResumeItsSavedConversation() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("work.native.smoke"));
        Config installed=new Config(Path.of("config.properties"));
        Config nativeConfig=new Config(temporary.resolve("none"),Map.of("WORK_AGENT_DATA",temporary.resolve("native-data").toString(),"REPOSITORY_ROOT",temporary.toString(),"AGENT_ENGINE",installed.get("AGENT_ENGINE","claude"),"AGENT_MODEL",installed.get("AGENT_MODEL",""),"REVIEW_TIMEOUT_SECONDS","120"));
        ProjectReviewModel model=new ProjectReviewModel(service,new CliModel(nativeConfig),nativeConfig);
        String request="현재 작업 폴더의 file.txt를 파일 조회 도구로 읽으세요. summary에는 확인한 파일 이름을 쓰고 slackSummary에는 파일 내용에 대한 한 문장 요약을 쓰세요. reply에는 파일의 첫 줄만 그대로 쓰세요. replyKind=fact,replyConfidence=high로 쓰고 replyEvidence에는 파일 첫 줄을 인용하세요. findings,relatedPaths,limitations는 빈 배열로 출력하세요.";
        JsonNode first=model.forReview(job("review"),metadata()).complete(request);
        assertEquals("after",Json.text(first,"reply").trim());
        assertFalse(Json.text(first,"slackSummary").isBlank());
        assertTrue(PullRequestReview.modelFooter(first.path("modelInfo")).contains("사용 모델 · claude-opus"));
        String session=Json.required(store.list("reviewSession").getFirst(),"session");
        JsonNode next=model.forReview(job("question"),metadata()).complete("앞선 대화에서 읽은 file.txt의 첫 줄을 다시 reply에만 쓰세요. replyKind=fact,replyConfidence=high로 쓰고 replyEvidence에 첫 줄을 인용하세요. findings,relatedPaths,limitations는 비우고 summary에는 이 질문의 요지를 쓰세요.");
        assertEquals("after",Json.text(next,"reply").trim());assertEquals(session,Json.text(store.list("reviewSession").getFirst(),"session"));
        assertTrue(ReviewWorkspaces.git(Path.of(Json.required(store.list("reviewSession").getFirst(),"directory")),"status","--porcelain").isBlank());
    }
}
