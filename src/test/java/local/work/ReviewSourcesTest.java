package local.work;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReviewSourcesTest {
    @TempDir Path temporary;
    Store store;
    PullRequestReviewTest.FakeApi api;
    ReviewSources sources;
    final String commit = "a".repeat(40);
    @BeforeEach void open() throws Exception {
        store = new Store(temporary.resolve("test.db"));
        api = new PullRequestReviewTest.FakeApi(new Config(temporary.resolve("none"), Map.of()));
        sources = new ReviewSources(store, api);
    }
    @AfterEach void close() throws Exception { store.close(); }
    Path publication() throws Exception {
        Path root = temporary.resolve("published");
        Path contracts = root.resolve("versions/" + commit + "-run/contracts");
        Files.createDirectories(contracts);
        Files.writeString(root.resolve("index.html"), "<meta content=\"0;url=versions/" + commit + "-run/index.html\">", StandardCharsets.UTF_8);
        Map<String, String> texts = Map.of("catalog.json", "{\"binaries\":[{\"binary\":\"test-runner\",\"commands\":[\"run-tests\"]}]}",
                "common/interface.json", "\ncommon contract\n" + "background\n".repeat(10_000), "test-runner/interface.json", "runner contract\n", "unused/interface.json", "UNRELATED_CONTENT");
        ObjectNode files = Json.object();
        for (Map.Entry<String, String> file : texts.entrySet()) {
            Path path = contracts.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
            files.put("contracts/" + file.getKey(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        }
        Files.writeString(contracts.getParent().resolve("version.json"), Json.write(Json.obj("commit", commit, "files", files)), StandardCharsets.UTF_8);
        return root;
    }
    ReviewContext context() throws Exception { return new ReviewContext(api, BitbucketClient.PullRequest.parse("https://bitbucket.org/team/repo/pull-requests/1")); }
    ObjectNode job(Path root) { return Json.obj("id", "job", "repositorySources", List.of(root.toString())); }
    @Test void selectedContractsAreHashedAndRestoredWithoutReadingChangedFiles() throws Exception {
        Path root = publication();
        sources.configure(Json.obj("repository", "team/repo", "sources", List.of(root.toString())));
        assertEquals(root.toString(), new ReviewSources(store, api).configured("team/repo").get(0).asText());
        ObjectNode review = job(root);
        ReviewContext context = context();
        Set<String> paths = sources.attach(context, review, prompt -> {
            assertTrue(prompt.contains("run-tests"));
            return Json.obj("relatedPaths", List.of(TranscriptImporter.hash(root.toString()).substring(0, 12) + "/test-runner/interface.json"));
        });
        assertEquals(2, paths.size());
        assertEquals(0, review.path("referenceErrors").size());
        assertFalse(Json.write(context.documents).contains("UNRELATED_CONTENT"));
        String common = paths.stream().filter(p -> p.endsWith("common/interface.json")).findFirst().orElseThrow();
        assertTrue(context.files.get("new:" + common).length() > 65_000);
        assertTrue(context.validEvidence(Json.obj("path", common, "side", "new", "line", 2, "quote", "common contract")));
        String support = Json.write(context.requestedReviewSupport(paths, "common runner contract"));
        assertTrue(support.length() < 65_000);
        assertTrue(support.contains("common contract"));
        assertEquals(1, context.batches().size());
        Files.writeString(root.resolve("versions/" + commit + "-run/contracts/test-runner/interface.json"), "changed", StandardCharsets.UTF_8);
        ReviewContext restored = context();
        sources.attach(restored, review, prompt -> { fail("snapshot restore must not call AI"); return null; });
        assertTrue(restored.files.values().stream().anyMatch(text -> text.equals("runner contract\n")));
        assertEquals(commit, review.path("referenceSnapshots").get(0).path("commit").asText());
    }
    @Test void mismatchedPublicationIsReportedAndNeverUsedAsEvidence() throws Exception {
        Path root = publication();
        Files.writeString(root.resolve("versions/" + commit + "-run/contracts/test-runner/interface.json"), "TAMPERED", StandardCharsets.UTF_8);
        ObjectNode review = job(root);
        ReviewContext context = context();
        sources.attach(context, review, prompt -> Json.obj("relatedPaths", List.of(TranscriptImporter.hash(root.toString()).substring(0, 12) + "/test-runner/interface.json")));
        assertEquals(1, review.path("referenceErrors").size());
        assertFalse(Json.write(context.documents).contains("TAMPERED"));
        assertFalse(context.limitations.isEmpty());
    }
    @Test void missingPublicationIsVisibleAndInvalidConfigurationIsRejected() throws Exception {
        ObjectNode review = job(temporary.resolve("missing"));
        ReviewContext context = context();
        sources.attach(context, review, prompt -> { fail("no catalogue to select from"); return null; });
        assertEquals(1, review.path("referenceErrors").size());
        assertThrows(IllegalArgumentException.class, () -> sources.configure(Json.obj("repository", "../repo", "sources", List.of())));
        assertThrows(IllegalArgumentException.class, () -> sources.configure(Json.obj("repository", "team/repo", "sources", List.of("relative/path"))));
    }
    @Test void configuredBitbucketContractIsLoadedWithoutPerRequestLinks() throws Exception {
        String url = "https://bitbucket.org/team/contracts/src/" + commit + "/interface.json";
        PullRequestReviewTest.FakeApi referenced = new PullRequestReviewTest.FakeApi(new Config(temporary.resolve("none"), Map.of())) {
            @Override String source(String repository, String revision, String path) throws Exception {
                if (repository.equals("team/contracts")) { assertEquals(commit, revision); return "contract from registry"; }
                return super.source(repository, revision, path);
            }
        };
        sources = new ReviewSources(store, referenced);
        sources.configure(Json.obj("repository", "team/repo", "sources", List.of(url)));
        ObjectNode review = Json.obj("id", "job", "repositorySources", sources.configured("team/repo"));
        ReviewContext context = context();
        sources.attach(context, review, prompt -> { fail("explicit file needs no selection"); return null; });
        assertTrue(context.files.values().contains("contract from registry"));
        assertEquals(url, review.path("referenceSnapshots").get(0).path("url").asText());
    }
    @Test void followupQuestionUsesTheReviewedSnapshotEvenAfterBaseMoves() throws Exception {
        Path root = publication();
        ObjectNode review = job(root);
        Set<String> paths = sources.attach(context(), review, prompt -> Json.obj("relatedPaths", List.of(TranscriptImporter.hash(root.toString()).substring(0, 12) + "/test-runner/interface.json")));
        String path = paths.stream().filter(p -> p.endsWith("test-runner/interface.json")).findFirst().orElseThrow();
        review.put("url", "https://bitbucket.org/team/repo/pull-requests/1").put("sourceCommit", "source").put("destinationCommit", "old-base").put("status", "ready");
        store.put("prReview", review);
        PrQuestion questions = new PrQuestion(store, api, prompt -> {
            assertTrue(prompt.contains("runner contract"));
            return Json.obj("reply", "저장한 계약을 확인했습니다.", "relatedPaths", List.of(), "replyEvidence",
                    List.of(Json.obj("path", path, "side", "new", "line", 1, "quote", "runner contract")));
        });
        ObjectNode answer = questions.answer(Json.text(review, "url"), "runner의 계약을 알려줘", "source");
        assertTrue(Json.text(answer, "message").contains("저장한 계약을 확인했습니다."));
    }
}
