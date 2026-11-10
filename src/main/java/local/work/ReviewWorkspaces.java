package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Prepares an isolated checkout at the PR head without changing the owner's working branch. */
final class ReviewWorkspaces {
    private final Config config;
    private final Store store;
    ReviewWorkspaces(Config config, Store store) { this.config = config; this.store = store; }
    ObjectNode configure(JsonNode input) throws Exception {
        String repository = Json.required(input, "repository"), value = Json.text(input, "localPath");
        BitbucketClient.PullRequest.parse("https://bitbucket.org/" + repository + "/pull-requests/1");
        if (value.isBlank()) { store.delete("prRepositoryPath", repository); return Json.obj("repository", repository, "localPath", ""); }
        Path path = Path.of(value).toRealPath();
        if (!matches(path, repository)) throw new IllegalArgumentException("이 폴더의 Git 원격 저장소가 입력한 Bitbucket 저장소와 다릅니다.");
        return store.put("prRepositoryPath", Json.obj("id", repository, "repository", repository, "localPath", path.toString()));
    }
    Path repository(String name) throws Exception {
        ObjectNode setting = store.get("prRepositoryPath", name);
        if (setting != null) {
            Path path = Path.of(Json.required(setting, "localPath")).toRealPath();
            if (!matches(path, name)) throw new IllegalArgumentException("저장된 로컬 경로의 Git 원격 저장소가 바뀌었습니다.");
            return path;
        }
        Path root = config.has("REPOSITORY_ROOT") ? Path.of(config.get("REPOSITORY_ROOT", "")) : Path.of("").toAbsolutePath().getParent();
        if (!Files.isDirectory(root)) return null;
        List<Path> matches = new ArrayList<>();
        try (var children = Files.list(root)) {
            for (Path path : children.filter(Files::isDirectory).toList()) if (Files.exists(path.resolve(".git"))) {
                try { if (matches(path, name)) matches.add(path.toRealPath()); } catch (IllegalStateException ignored) {}
            }
        }
        if (matches.size() > 1) {
            String slug=name.substring(name.indexOf('/')+1);
            List<Path> exact=matches.stream().filter(path->path.getFileName().toString().equalsIgnoreCase(slug)).toList();
            if(exact.size()==1)return exact.getFirst();
            throw new IllegalArgumentException("같은 원격 저장소를 사용하는 폴더가 여러 개입니다. PR 리뷰에서 로컬 경로를 선택하세요.");
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }
    synchronized Path prepare(ObjectNode job, JsonNode metadata) throws Exception {
        BitbucketClient.PullRequest pr = BitbucketClient.PullRequest.parse(Json.required(job, "url"));
        String repository = pr.workspace() + "/" + pr.repository();
        Path clone = repository(repository);
        if (clone == null) return null;
        String head = Json.required(job, "sourceCommit"), base = Json.required(job, "destinationCommit");
        if (!head.matches("[0-9a-f]{40,64}") || !base.matches("[0-9a-f]{40,64}")) throw new IllegalArgumentException("PR 커밋 해시를 확인하세요.");
        String sourceRepository = Json.text(metadata.path("source").path("repository"), "full_name");
        if (sourceRepository.isBlank()) sourceRepository = repository;
        BitbucketClient.PullRequest.parse("https://bitbucket.org/" + sourceRepository + "/pull-requests/1");
        ensureCommit(clone, sourceRepository, head); ensureCommit(clone, repository, base);
        Path directory = config.data().resolve("review-worktrees").resolve(TranscriptImporter.hash(repository)).resolve(head).toAbsolutePath().normalize();
        if (Files.exists(directory.resolve(".git"))) {
            if (!git(directory, "rev-parse", "HEAD").trim().equals(head) || !git(directory, "status", "--porcelain").isBlank()) throw new IllegalStateException("검토용 작업 폴더가 변경돼 있습니다. 내용을 확인한 뒤 다시 검토하세요.");
        } else {
            Files.createDirectories(directory.getParent());
            git(clone, "worktree", "add", "--detach", directory.toString(), head);
        }
        job.put("localRepository", clone.toString()).put("reviewDirectory", directory.toString()).put("executionMode", "project-session");
        return directory;
    }
    private void ensureCommit(Path clone, String repository, String commit) throws Exception {
        try { git(clone, "cat-file", "-e", commit + "^{commit}"); }
        catch (IllegalStateException e) { String remote=remoteName(clone,repository);git(clone, "fetch", "--no-tags", remote.isBlank()?"https://bitbucket.org/" + repository + ".git":remote, commit); }
    }
    private boolean matches(Path path, String repository) throws Exception {
        return !remoteName(path,repository).isBlank();
    }
    private String remoteName(Path path, String repository) throws Exception {
        for (String line : git(path, "remote", "-v").split("\\R")) {
            String[] columns = line.split("\\s+"); if (columns.length < 2) continue;
            String remote = columns[1].replaceFirst("\\.git$", "");
            if (remote.equals("git@bitbucket.org:" + repository)) return columns[0];
            try { java.net.URI uri=java.net.URI.create(remote); if("bitbucket.org".equals(uri.getHost()) && ("/"+repository).equals(uri.getPath()))return columns[0]; } catch(IllegalArgumentException ignored) {}
        }
        return "";
    }
    static String git(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "--no-optional-locks", "-C", directory.toString())); command.addAll(List.of(arguments));
        Path hooks = Files.createTempDirectory("work-agent-no-hooks-");
        command.addAll(1, List.of("-c", "core.hooksPath=" + hooks));
        Path output = Files.createTempFile("work-agent-git-", ".log"); Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("GCM_INTERACTIVE", "Never");
            process = builder.start();
            if (!process.waitFor(45, TimeUnit.SECONDS)) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); throw new IllegalStateException("로컬 저장소 확인 시간이 초과됐습니다."); }
            if (process.exitValue() != 0) throw new IllegalStateException("로컬 저장소에서 " + arguments[0] + " 작업을 완료하지 못했습니다.");
            return Files.readString(output, StandardCharsets.UTF_8);
        } finally { if (process != null && process.isAlive()) process.destroyForcibly(); Files.deleteIfExists(output); Files.deleteIfExists(hooks); }
    }
}
