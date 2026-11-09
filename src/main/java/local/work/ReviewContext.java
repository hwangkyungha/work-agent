package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.regex.*;

/** Pins PR evidence to both commits and validates model citations against actual source lines. */
final class ReviewContext {
    final BitbucketClient.PullRequest pr;
    final JsonNode metadata;
    final String sourceCommit, destinationCommit, baseCommit, diff;
    private final String sourceRepository;
    private final Store cache;
    final List<String> currentPaths = new ArrayList<>();
    final Map<String, String> files = new LinkedHashMap<>();
    final Map<String, Set<Integer>> changed = new LinkedHashMap<>();
    final List<String> limitations = new ArrayList<>();
    final List<ObjectNode> documents = new ArrayList<>();
    ReviewContext(BitbucketClient api, BitbucketClient.PullRequest pr) throws Exception {
        this(api, pr, null, true);
    }
    ReviewContext(BitbucketClient api, BitbucketClient.PullRequest pr, Store cache, boolean full) throws Exception {
        this.pr = pr; metadata = api.pullRequest(pr);
        this.cache = cache;
        sourceCommit = Json.required(metadata.path("source").path("commit"), "hash");
        destinationCommit = Json.required(metadata.path("destination").path("commit"), "hash");
        if (full && !Json.text(metadata, "state").equals("OPEN")) throw new IllegalArgumentException("열려 있는 PR만 검토할 수 있습니다.");
        baseCommit = full ? Json.required(api.get("/repositories/" + BitbucketClient.segment(pr.workspace()) + "/" + BitbucketClient.segment(pr.repository()) + "/merge-base/" + sourceCommit + ".." + destinationCommit), "hash") : "";
        diff = full ? api.request("GET", pr.api() + "/diff", null) : "";
        if (diff.length() > 1_500_000) throw new IllegalArgumentException("PR 변경이 너무 큽니다. PR을 나눈 뒤 다시 검토하세요.");
        parseChanges(diff);
        sourceRepository = Json.required(metadata.path("source").path("repository"), "full_name");
        String destinationRepository = Json.required(metadata.path("destination").path("repository"), "full_name");
        String snapshot = TranscriptImporter.hash(pr.url() + ":" + sourceCommit + ":" + destinationCommit);
        ObjectNode saved = cache == null ? null : cache.get("prFileIndex", snapshot);
        List<JsonNode> stats = new ArrayList<>();
        if (saved != null) saved.path("stats").forEach(stats::add);
        else {
            stats.addAll(api.pages(pr.api() + "/diffstat?pagelen=100"));
            if (!sameHead(api.pullRequest(pr))) throw new IllegalStateException("자료를 읽는 중 PR 커밋이 바뀌었습니다. 다시 검토하세요.");
            if (cache != null) cache.put("prFileIndex", Json.obj("id", snapshot, "stats", stats));
        }
        for (JsonNode stat : stats) {
            String oldPath = Json.text(stat.path("old"), "path"), newPath = Json.text(stat.path("new"), "path");
            if (!newPath.isBlank()) currentPaths.add(newPath);
            if (!full) continue;
            if (stat.path("lines_added").asInt() == 0 && stat.path("lines_removed").asInt() == 0) { limitations.add((newPath.isBlank() ? oldPath : newPath) + ": 텍스트 변경 없이 이동했거나 바이너리 파일입니다."); continue; }
            if (!newPath.isBlank()) load(api, sourceRepository, sourceCommit, newPath, "new");
            if (!oldPath.isBlank()) load(api, destinationRepository, baseCommit, oldPath, "old");
        }
        if (!full) return;
        for (String path : List.of("AGENTS.md", "CLAUDE.md", "README.md")) if (!files.containsKey("new:" + path)) {
            try { load(api, sourceRepository, sourceCommit, path, "new"); }
            catch (BitbucketClient.Rejected e) { if (e.status != 404) throw e; }
        }
        JsonNode current = api.pullRequest(pr);
        if (!sameHead(current)) throw new IllegalStateException("자료를 읽는 중 PR 커밋이 바뀌었습니다. 다시 검토하세요.");
    }
    private void load(BitbucketClient api, String repository, String commit, String path, String side) throws Exception {
        String key = sourceKey(repository, commit, path);
        ObjectNode saved = cache == null ? null : cache.get("prSource", key);
        String text = saved == null ? api.source(repository, commit, path).replace("\r\n", "\n") : Json.text(saved, "text");
        if (text.length() > 220_000) { limitations.add(path + ": 파일이 커서 본문 검토에서 제외했습니다."); return; }
        if (cache != null && saved == null) cache.put("prSource", Json.obj("id", key, "text", text));
        files.put(side + ":" + path, text);
        documents.add(Json.obj("path", path, "side", side, "content", numbered(text)));
    }
    private static String sourceKey(String repository, String commit, String path) {
        return TranscriptImporter.hash(Json.write(List.of(repository, commit, path)));
    }
    String cachedCurrent(String path) {
        ObjectNode saved = cache == null ? null : cache.get("prSource", sourceKey(sourceRepository, sourceCommit, path));
        return saved == null ? "" : Json.text(saved, "text");
    }
    boolean includeCurrent(BitbucketClient api, String path) throws Exception {
        if (files.containsKey("new:" + path)) return false;
        if (!currentPaths.contains(path) && !path.matches("(?:[A-Za-z0-9_-][A-Za-z0-9_.-]*/)*[A-Za-z0-9_-][A-Za-z0-9_.-]*\\.(?:json|md|go|java|kt|rs|py|cs|c|cpp|h|hpp|js|ts|yaml|yml|toml)")) return false;
        try { load(api, sourceRepository, sourceCommit, path, "new"); }
        catch (BitbucketClient.Rejected e) { if (e.status != 404) throw e; }
        return files.containsKey("new:" + path);
    }
    void includeCommentFile(BitbucketClient api, JsonNode comment) throws Exception {
        String path = Json.text(comment.path("inline"), "path");
        if (!path.isBlank() && !files.containsKey("new:" + path)) {
            try { load(api, sourceRepository, sourceCommit, path, "new"); }
            catch (BitbucketClient.Rejected e) { if (e.status != 404) throw e; limitations.add(path + ": 현재 소스에 없는 파일의 댓글입니다."); }
        }
    }
    Set<String> relatedFileCandidates(BitbucketClient api, JsonNode incoming) throws Exception {
        Set<String> paths = new LinkedHashSet<>(), directories = new LinkedHashSet<>();
        String inline = Json.text(incoming.path("inline"), "path");
        if (!inline.isBlank()) directories.add(parentDirectory(inline));
        for (ObjectNode document : documents) {
            if (!Json.text(document, "side").equals("new")) continue;
            String path = Json.text(document, "path");
            paths.add(path);
            if (!path.endsWith(".md")) directories.add(parentDirectory(path));
        }
        Set<String> search = new LinkedHashSet<>(directories.stream().limit(4).toList());
        search.addAll(List.of("openspec/specs", "docs", "specs", ""));
        for (String directory : search) {
            try {
                JsonNode page = api.sourceDirectory(sourceRepository, sourceCommit, directory, directories.contains(directory) || directory.isEmpty() ? 1 : 2);
                for (JsonNode entry : page.path("values")) {
                    String path = Json.text(entry, "path");
                    if (Json.text(entry, "type").equals("commit_file") && path.matches("(?:[\\p{L}\\p{N}_-][\\p{L}\\p{N}_.-]*/)*[\\p{L}\\p{N}_-][\\p{L}\\p{N}_.-]*\\.(?:md|rst|adoc|go|java|kt|cs|rs|py|rb|js|ts|jsx|tsx|c|cc|cpp|h|hpp|sh|ps1)")) paths.add(path);
                }
                if (page.hasNonNull("next")) limitations.add(directory + " 파일 목록은 처음 100개까지만 확인했습니다.");
            } catch (BitbucketClient.Rejected e) {
                if (e.status != 404) limitations.add(directory + " 관련 파일 목록을 읽지 못했습니다(HTTP " + e.status + ").");
            }
        }
        if (directories.size() > 4) limitations.add("관련 호출부 검색은 우선순위가 높은 소스 디렉터리 4곳까지 확인했습니다.");
        return paths;
    }
    Set<String> includeRelatedFiles(BitbucketClient api, Set<String> candidates, JsonNode selected) throws Exception {
        Set<String> included = new LinkedHashSet<>();
        for (JsonNode value : selected) {
            if (included.size() == 4) break;
            String path = value.asText();
            if (!candidates.contains(path) || !included.add(path)) continue;
            if (files.containsKey("new:" + path)) continue;
            try { load(api, sourceRepository, sourceCommit, path, "new"); }
            catch (BitbucketClient.Rejected e) { limitations.add(path + ": 관련 자료를 읽지 못했습니다(HTTP " + e.status + ")."); }
        }
        included.removeIf(path -> !files.containsKey("new:" + path));
        return included;
    }
    private static String parentDirectory(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
    private static String numbered(String text) {
        String[] lines = text.split("\n", -1); StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) result.append(i + 1).append(": ").append(lines[i]).append('\n');
        return result.toString();
    }
    private void parseChanges(String patch) {
        String oldPath = "", newPath = ""; int oldLine = 0, newLine = 0; boolean hunk = false;
        Pattern header = Pattern.compile("@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");
        for (String line : patch.split("\n")) {
            if (line.startsWith("diff --git ")) { hunk = false; oldPath = ""; newPath = ""; }
            else if (!hunk && line.startsWith("--- ")) oldPath = patchPath(line.substring(4), "a/");
            else if (!hunk && line.startsWith("+++ ")) newPath = patchPath(line.substring(4), "b/");
            else if (line.startsWith("@@ ")) { Matcher match = header.matcher(line); hunk = match.matches(); if (hunk) { oldLine = Integer.parseInt(match.group(1)); newLine = Integer.parseInt(match.group(2)); } }
            else if (hunk && line.startsWith("+")) { changed.computeIfAbsent("new:" + newPath, key -> new HashSet<>()).add(newLine++); }
            else if (hunk && line.startsWith("-")) { changed.computeIfAbsent("old:" + oldPath, key -> new HashSet<>()).add(oldLine++); }
            else if (hunk && line.startsWith(" ")) { oldLine++; newLine++; }
        }
    }
    private static String patchPath(String path, String prefix) {
        path = path.split("\t", 2)[0];
        if (path.startsWith("\"") && path.endsWith("\"")) { try { path = Json.read(path).asText(); } catch (RuntimeException e) { return ""; } }
        return path.startsWith(prefix) ? path.substring(2) : "";
    }
    boolean sameHead(JsonNode current) {
        return sourceCommit.equals(Json.text(current.path("source").path("commit"), "hash")) && destinationCommit.equals(Json.text(current.path("destination").path("commit"), "hash"));
    }
    boolean validFinding(JsonNode finding) {
        String key = Json.text(finding, "side") + ":" + Json.text(finding, "path");
        if (!Json.text(finding, "confidence").equals("high") || !Set.of("high", "medium").contains(Json.text(finding, "severity")) || !changed.getOrDefault(key, Set.of()).contains(finding.path("line").asInt())) return false;
        for (String field : List.of("title", "comment", "trigger", "impact", "fix")) if (Json.text(finding, field).isBlank()) return false;
        if (!finding.path("evidence").isArray() || finding.path("evidence").isEmpty()) return false;
        boolean anchor = false;
        for (JsonNode evidence : finding.path("evidence")) {
            if (!validEvidence(evidence)) return false;
            anchor |= Json.text(evidence, "side").equals(Json.text(finding, "side")) && Json.text(evidence, "path").equals(Json.text(finding, "path")) && evidence.path("line").asInt() == finding.path("line").asInt();
        }
        return anchor;
    }
    boolean validEvidence(JsonNode evidence) {
        String content = files.get(Json.text(evidence, "side") + ":" + Json.text(evidence, "path"));
        int line = evidence.path("line").asInt(); String quote = Json.text(evidence, "quote");
        if (content == null || line < 1 || quote.isBlank()) return false;
        String[] lines = content.split("\n", -1), quoted = quote.replace("\r\n", "\n").split("\n", -1);
        if (line > lines.length || quoted.length > lines.length - line + 1) return false;
        for (int offset = 0; offset < quoted.length; offset++) if (!lines[line - 1 + offset].strip().equals(quoted[offset].strip())) return false;
        return true;
    }
    List<ObjectNode> replyExcerpts(JsonNode evidence, Set<String> related, String question, String reply) {
        Map<String, NavigableMap<Integer, String>> excerpts = new LinkedHashMap<>();
        StringBuilder termsSource = new StringBuilder(question);
        for (JsonNode citation : evidence) {
            if (!validEvidence(citation)) continue;
            String key = Json.text(citation, "side") + ":" + Json.text(citation, "path");
            String quote = Json.text(citation, "quote");
            int line = citation.path("line").asInt();
            addExcerpt(excerpts, key, files.get(key).split("\n", -1), line - 8, line + quote.split("\n", -1).length + 7);
            termsSource.append('\n').append(quote);
        }
        Set<String> terms = new LinkedHashSet<>();
        Matcher words = Pattern.compile("[\\p{L}\\p{N}_]{4,}").matcher(termsSource.toString().toLowerCase(Locale.ROOT));
        while (words.find() && terms.size() < 40) terms.add(words.group());
        for (String path : related) {
            String key = "new:" + path;
            String[] lines = files.get(key).split("\n", -1);
            int bestLine = 1, bestScore = 0;
            for (int i = 0; i < lines.length; i++) {
                String text = lines[i].toLowerCase(Locale.ROOT);
                int score = 0;
                for (String term : terms) if (text.contains(term)) score++;
                if (score > bestScore) { bestLine = i + 1; bestScore = score; }
            }
            addExcerpt(excerpts, key, lines, bestLine - 8, bestLine + 8);
        }
        List<String> calls = Pattern.compile("`([A-Za-z_][A-Za-z0-9_]{3,})`").matcher(reply).results()
                .map(match -> match.group(1) + "(").distinct().limit(8).toList();
        int callers = 0;
        for (var file : files.entrySet()) {
            String key = file.getKey();
            if (callers >= 2 || calls.isEmpty()) break;
            if (!key.startsWith("new:") || key.endsWith(".md") || excerpts.containsKey(key)) continue;
            String[] lines = file.getValue().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (calls.stream().noneMatch(line::contains)) continue;
                addExcerpt(excerpts, key, lines, i - 4, i + 6);
                callers++;
                break;
            }
        }
        List<ObjectNode> documents = new ArrayList<>();
        excerpts.forEach((key, lines) -> {
            int split = key.indexOf(':');
            StringBuilder content = new StringBuilder();
            lines.forEach((number, line) -> content.append(number).append(": ").append(line).append('\n'));
            documents.add(Json.obj("side", key.substring(0, split), "path", key.substring(split + 1), "content", content.toString()));
        });
        return documents;
    }
    private static void addExcerpt(Map<String, NavigableMap<Integer, String>> excerpts, String key, String[] lines, int from, int to) {
        NavigableMap<Integer, String> selected = excerpts.computeIfAbsent(key, unused -> new TreeMap<>());
        for (int line = Math.max(1, from); line <= Math.min(lines.length, to); line++) selected.put(line, lines[line - 1]);
    }
    List<ObjectNode> questionExcerpts(List<String> terms) {
        List<ObjectNode> excerpts = new ArrayList<>();
        for (var entry : files.entrySet()) {
            String[] lines = entry.getValue().split("\n", -1);
            List<Integer> matches = new ArrayList<>();
            int[] scores = new int[lines.length];
            for (int i = 0; i < lines.length; i++) {
                String lower = lines[i].toLowerCase(Locale.ROOT);
                for (String term : terms) if (lower.contains(term)) scores[i]++;
                if (scores[i] > 0) matches.add(i);
            }
            matches.sort(Comparator.comparingInt((Integer i) -> scores[i]).reversed());
            Set<Integer> selected = new TreeSet<>();
            for (int i = 0; i < Math.min(6, lines.length); i++) selected.add(i);
            for (int anchor : matches) {
                if (selected.size() >= 42) break;
                if (!selected.contains(anchor)) for (int i = Math.max(0, anchor - 5); i < Math.min(lines.length, anchor + 6); i++) selected.add(i);
            }
            if (matches.isEmpty()) for (int i = 6; i < Math.min(lines.length, 20); i++) selected.add(i);
            StringBuilder excerpt = new StringBuilder();
            for (int index : selected) if (lines[index].length() <= 1200 && excerpt.length() + lines[index].length() <= 3500) excerpt.append(index + 1).append(": ").append(lines[index]).append('\n');
            excerpts.add(Json.obj("path", entry.getKey().substring(4), "side", "new", "content", excerpt.toString()));
        }
        return excerpts;
    }
    List<List<ObjectNode>> batches() {
        List<List<ObjectNode>> batches = new ArrayList<>(); List<ObjectNode> batch = new ArrayList<>(); int size = 2;
        Map<String, List<ObjectNode>> paths = new LinkedHashMap<>();
        for (ObjectNode doc : documents) paths.computeIfAbsent(Json.text(doc, "path"), key -> new ArrayList<>()).addAll(chunks(doc));
        for (List<ObjectNode> pair : paths.values()) {
            int length = Json.write(pair).length();
            if (!batch.isEmpty() && size + length > 65_000) { batches.add(batch); batch = new ArrayList<>(); size = 2; }
            for (ObjectNode doc : pair) {
                int documentSize = Json.write(doc).length() + 1;
                if (!batch.isEmpty() && size + documentSize > 65_000) { batches.add(batch); batch = new ArrayList<>(); size = 2; }
                batch.add(doc); size += documentSize;
            }
        }
        if (!batch.isEmpty()) batches.add(batch);
        return batches;
    }
    List<ObjectNode> chunks(ObjectNode document) {
        if (Json.write(document).length() <= 28_000) return List.of(document);
        List<ObjectNode> chunks = new ArrayList<>(); StringBuilder text = new StringBuilder(); int size = 0;
        for (String line : Json.text(document, "content").split("\n", -1)) {
            int length = Json.write(line + "\n").length();
            if (length > 27_000) { limitations.add(Json.text(document, "path") + ": 매우 긴 한 줄을 검토에서 제외했습니다."); continue; }
            if (size + length > 27_000 && !text.isEmpty()) { chunks.add(document.deepCopy().put("content", text.toString())); text.setLength(0); size = 0; }
            text.append(line).append('\n'); size += length;
        }
        if (!text.isEmpty()) chunks.add(document.deepCopy().put("content", text.toString()));
        return chunks;
    }
}
