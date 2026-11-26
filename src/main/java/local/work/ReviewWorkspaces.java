package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

/** Uses a clean owner checkout or a checkout registered in work-agent's own Git repository. */
final class ReviewWorkspaces {
    private static final long GIT_TIMEOUT_SECONDS = 45;
    private static final long CHECKOUT_TIMEOUT_SECONDS = 300;
    private final Config config;
    private final Store store;
    private final Supplier<Instant> clock;
    private final Map<Path, Integer> active = new HashMap<>();
    ReviewWorkspaces(Config config, Store store, Supplier<Instant> clock) { this.config = config; this.store = store; this.clock = clock; }

    final class Lease implements AutoCloseable {
        private final String url;
        private final Set<Path> paths = new LinkedHashSet<>();
        Lease(ObjectNode job) { url = Json.required(job, "url"); }
        private void use(Path path, Path gitDirectory) {
            String id = path.toString();
            ObjectNode row = store.get("reviewWorkspace", id);
            if (row == null) row = Json.obj("id", id, "gitDirectory", gitDirectory == null ? "" : gitDirectory.toString(), "urls", List.of());
            if (StreamSupport.stream(row.path("urls").spliterator(), false).noneMatch(value -> url.equals(value.asText()))) row.withArray("urls").add(url);
            store.put("reviewWorkspace", row.put("lastUsedAt", clock.get().toString()));
            if (paths.add(path)) active.merge(path, 1, Integer::sum);
        }
        @Override public void close() {
            synchronized (ReviewWorkspaces.this) {
                for (Path path : paths) {
                    active.computeIfPresent(path, (key, count) -> count == 1 ? null : count - 1);
                    ObjectNode row = store.get("reviewWorkspace", path.toString());
                    if (row != null) store.put("reviewWorkspace", row.put("lastUsedAt", clock.get().toString()));
                }
                paths.clear();
            }
        }
    }
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
    Path localRepository(ObjectNode job) throws Exception {
        BitbucketClient.PullRequest pr = BitbucketClient.PullRequest.parse(Json.required(job, "url"));
        try { return repository(pr.workspace() + "/" + pr.repository()); }
        catch (IllegalArgumentException | IOException | IllegalStateException unavailable) { return null; }
    }
    synchronized Path prepare(ObjectNode job, JsonNode metadata, Path local, String preferredCwd, Lease lease) throws Exception {
        Path isolated = repositoryWithCommits(job, metadata, local);
        String head = Json.required(job, "sourceCommit");
        Path directory = job.path("ownPr").asBoolean() ? ownerCheckout(local, preferredCwd, head) : null;
        boolean owner = directory != null;
        if (!owner) directory = prepareCheckout(isolated, workspaceRoot(isolated).resolve(head), head, lease);
        job.put("reviewRepository", isolated.toString()).put("reviewDirectory", directory.toString())
                .put("reviewWorkspaceMode", owner ? "owner" : "isolated").put("executionMode", "project-session");
        return directory;
    }
    synchronized Path repositoryWithCommits(ObjectNode job, JsonNode metadata, Path local) throws Exception {
        BitbucketClient.PullRequest pr = BitbucketClient.PullRequest.parse(Json.required(job, "url"));
        String repository = pr.workspace() + "/" + pr.repository();
        String head = Json.required(job, "sourceCommit"), base = Json.required(job, "destinationCommit");
        if (!head.matches("[0-9a-f]{40,64}") || !base.matches("[0-9a-f]{40,64}")) throw new IllegalArgumentException("PR 커밋 해시를 확인하세요.");
        String sourceRepository = Json.text(metadata.path("source").path("repository"), "full_name");
        if (sourceRepository.isBlank()) sourceRepository = repository;
        BitbucketClient.PullRequest.parse("https://bitbucket.org/" + sourceRepository + "/pull-requests/1");
        Path gitRoot = Files.createDirectories(config.data().toAbsolutePath().normalize().resolve("review-git"));
        Path isolated = gitRoot.resolve(TranscriptImporter.hash(repository) + ".git");
        if (Files.notExists(isolated.resolve("HEAD"))) git(gitRoot, "init", "--bare", isolated.toString());
        ensureCommit(isolated, local, sourceRepository, head);
        ensureCommit(isolated, local, repository, base);
        return isolated;
    }
    private void ensureCommit(Path isolated, Path local, String repository, String commit) throws Exception {
        String ref = "refs/work-agent/commits/" + commit;
        if (hasCommit(isolated, ref)) return;
        if (!hasCommit(isolated, commit)) {
            if (local != null && hasCommit(local, commit)) {
                git(isolated, CHECKOUT_TIMEOUT_SECONDS, "fetch", "--no-tags", "--", local.toString(), commit);
            } else {
                String token = config.environment(config.get("BITBUCKET_TOKEN_ENV", "BITBUCKET_API_TOKEN"));
                String basic = Base64.getEncoder().encodeToString(("x-bitbucket-api-token-auth:" + token).getBytes(StandardCharsets.UTF_8));
                Map<String, String> auth = token.isBlank() ? Map.of() : Map.of(
                        "GIT_CONFIG_COUNT", "2",
                        "GIT_CONFIG_KEY_0", "http.https://bitbucket.org/.extraHeader",
                        "GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic,
                        "GIT_CONFIG_KEY_1", "http.https://bitbucket.org/.followRedirects",
                        "GIT_CONFIG_VALUE_1", "false");
                try { git(isolated, CHECKOUT_TIMEOUT_SECONDS, auth, "fetch", "--no-tags", "--", "https://bitbucket.org/" + repository + ".git", commit); }
                catch (IllegalStateException failure) {
                    throw new IllegalStateException("Bitbucket에서 PR 커밋을 가져오지 못했습니다. 저장소 읽기 권한과 커밋 접근을 확인하세요.", failure);
                }
            }
        }
        git(isolated, "update-ref", ref, commit);
    }
    private static boolean hasCommit(Path repository, String object) throws Exception {
        try { git(repository, "cat-file", "-e", object + "^{commit}"); return true; }
        catch (IllegalStateException missing) { return false; }
    }
    private Path ownerCheckout(Path local, String preferredCwd, String head) throws Exception {
        if (local == null) return null;
        Path preferred = preferredCwd.isBlank() ? null : Path.of(preferredCwd).toAbsolutePath().normalize();
        List<Path> candidates = Arrays.stream(git(local, "worktree", "list", "--porcelain", "-z").split("\0"))
                .filter(line -> line.startsWith("worktree ")).map(line -> Path.of(line.substring(9)).toAbsolutePath().normalize())
                .filter(path -> !path.startsWith(checkoutsRoot()) && Files.isDirectory(path))
                .sorted(Comparator.comparing(path -> preferred == null || !preferred.startsWith(path))).toList();
        for (Path candidate : candidates) {
            try { if (cleanAt(candidate, head)) return candidate; }
            catch (IllegalStateException unreadable) { /* Try another registered checkout. */ }
        }
        return null;
    }
    static boolean cleanAt(Path directory, String commit) throws Exception {
        return git(directory, "rev-parse", "HEAD").trim().equals(commit)
                && git(directory, "status", "--porcelain", "--untracked-files=normal").isBlank();
    }
    synchronized void prepareMaterials(ObjectNode job, Lease lease) throws Exception {
        Path isolated = Path.of(Json.required(job, "reviewRepository"));
        String base = git(isolated, "merge-base", Json.required(job, "sourceCommit"), Json.required(job, "destinationCommit")).trim();
        Path root = workspaceRoot(isolated);
        Path baseDirectory = prepareCheckout(isolated, root.resolve("base-" + base), base, lease);
        Path inputs = Files.createDirectories(root.resolve("inputs-" + TranscriptImporter.hash(Json.required(job, "id"))));
        lease.use(inputs, null);
        job.put("baseDirectory", baseDirectory.toString()).put("baseCommit", base).put("reviewInputDirectory", inputs.toString());
    }
    private Path prepareCheckout(Path isolated, Path directory, String commit, Lease lease) throws Exception {
        Path candidate = directory;
        for (int attempt = 1; Files.exists(candidate); attempt++) {
            try {
                if (isCheckoutOf(candidate, isolated) && cleanAt(candidate, commit)) {
                    lease.use(candidate, isolated);
                    return candidate;
                }
            } catch (IOException | IllegalStateException incomplete) { /* Preserve this checkout and try a new path. */ }
            candidate = directory.resolveSibling(directory.getFileName() + "-retry-" + attempt);
        }
        Files.createDirectories(candidate.getParent());
        lease.use(candidate, isolated);
        git(isolated, CHECKOUT_TIMEOUT_SECONDS, "worktree", "add", "--detach", candidate.toString(), commit);
        return candidate;
    }
    private Path checkoutsRoot() { return config.data().toAbsolutePath().normalize().resolve("review-worktrees"); }
    private Path workspaceRoot(Path isolated) {
        return checkoutsRoot().resolve(isolated.getFileName().toString().replaceFirst("\\.git$", "")).resolve("isolated");
    }
    private static boolean isCheckoutOf(Path path, Path isolated) throws Exception {
        if (!Files.isRegularFile(path.resolve(".git"))) return false;
        return Path.of(git(path, "rev-parse", "--path-format=absolute", "--git-common-dir").trim()).toRealPath().equals(isolated.toRealPath());
    }
    synchronized void clean() throws Exception {
        Instant cutoff = clock.get().minus(Duration.ofHours(24));
        for (ObjectNode row : store.list("reviewWorkspace")) {
            Path path = Path.of(Json.required(row, "id")).toAbsolutePath().normalize();
            if (active.containsKey(path)) continue;
            boolean closed = !row.path("urls").isEmpty() && StreamSupport.stream(row.path("urls").spliterator(), false).allMatch(value -> {
                ObjectNode pr = store.get("prLifecycle", value.asText());
                return pr != null && Set.of("MERGED", "DECLINED").contains(Json.text(pr, "state"));
            });
            if (!closed && Instant.parse(Json.required(row, "lastUsedAt")).isAfter(cutoff)) continue;
            Path root = checkoutsRoot();
            if (!path.startsWith(root) || root.relativize(path).getNameCount() != 3 || !path.getParent().endsWith("isolated")) continue;
            if (Files.exists(path)) {
                if (!path.toRealPath().equals(root.toRealPath().resolve(root.relativize(path)))) continue;
                String gitDirectory = Json.text(row, "gitDirectory");
                try {
                    if (gitDirectory.isBlank()) {
                        if (!path.getFileName().toString().startsWith("inputs-")) continue;
                        try (var files = Files.list(path)) {
                            if (files.anyMatch(file -> !Set.of("changes.patch", "paths.json").contains(file.getFileName().toString()))) continue;
                        }
                        Files.deleteIfExists(path.resolve("changes.patch"));
                        Files.deleteIfExists(path.resolve("paths.json"));
                        Files.delete(path);
                    } else {
                        Path isolated = Path.of(gitDirectory);
                        if (!path.getParent().equals(workspaceRoot(isolated)) || !isCheckoutOf(path, isolated)) continue;
                        git(isolated, "worktree", "remove", "--", path.toString());
                    }
                } catch (IOException | IllegalStateException blocked) { continue; }
            }
            store.delete("reviewWorkspace", path.toString());
        }
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
        return git(directory, GIT_TIMEOUT_SECONDS, arguments);
    }
    private static String git(Path directory, long timeoutSeconds, String... arguments) throws Exception {
        return git(directory, timeoutSeconds, Map.of(), arguments);
    }
    private static String git(Path directory, long timeoutSeconds, Map<String, String> environment, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "--no-optional-locks", "-C", directory.toString())); command.addAll(List.of(arguments));
        Path hooks = Files.createTempDirectory("work-agent-no-hooks-");
        command.addAll(1, List.of("-c", "core.hooksPath=" + hooks));
        Path output = Files.createTempFile("work-agent-git-", ".log"); Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("GCM_INTERACTIVE", "Never");
            builder.environment().putAll(environment);
            process = builder.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) throw new IllegalStateException(timeoutSeconds == CHECKOUT_TIMEOUT_SECONDS
                    ? "검토용 작업 폴더 준비가 5분을 넘었습니다. 다시 검토하세요."
                    : "로컬 저장소 확인 시간이 초과됐습니다.");
            if (process.exitValue() != 0) throw new IllegalStateException("로컬 저장소에서 " + arguments[0] + " 작업을 완료하지 못했습니다.");
            return Files.readString(output, StandardCharsets.UTF_8);
        } finally {
            try {
                if (process != null && process.isAlive()) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            } finally {
                removeTempFile(output);
                removeTempFile(hooks);
            }
        }
    }
    private static void removeTempFile(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException e) { path.toFile().deleteOnExit(); }
    }
}
