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
    final Map<String, String> files = new LinkedHashMap<>();
    final Map<String, Set<Integer>> changed = new LinkedHashMap<>();
    final List<String> limitations = new ArrayList<>();
    final List<ObjectNode> documents = new ArrayList<>();
    ReviewContext(BitbucketClient api, BitbucketClient.PullRequest pr) throws Exception {
        this.pr = pr; metadata = api.pullRequest(pr);
        sourceCommit = Json.required(metadata.path("source").path("commit"), "hash");
        destinationCommit = Json.required(metadata.path("destination").path("commit"), "hash");
        if (!Json.text(metadata, "state").equals("OPEN")) throw new IllegalArgumentException("열려 있는 PR만 검토할 수 있습니다.");
        baseCommit = Json.required(api.get("/repositories/" + BitbucketClient.segment(pr.workspace()) + "/" + BitbucketClient.segment(pr.repository()) + "/merge-base/" + sourceCommit + ".." + destinationCommit), "hash");
        diff = api.request("GET", pr.api() + "/diff", null);
        if (diff.length() > 1_500_000) throw new IllegalArgumentException("PR 변경이 너무 큽니다. PR을 나눈 뒤 다시 검토하세요.");
        parseChanges(diff);
        String sourceRepository = Json.required(metadata.path("source").path("repository"), "full_name");
        String destinationRepository = Json.required(metadata.path("destination").path("repository"), "full_name");
        for (JsonNode stat : api.pages(pr.api() + "/diffstat?pagelen=100")) {
            String oldPath = Json.text(stat.path("old"), "path"), newPath = Json.text(stat.path("new"), "path");
            if (stat.path("lines_added").asInt() == 0 && stat.path("lines_removed").asInt() == 0) { limitations.add((newPath.isBlank() ? oldPath : newPath) + ": 텍스트 변경 없이 이동했거나 바이너리 파일입니다."); continue; }
            if (!newPath.isBlank()) load(api, sourceRepository, sourceCommit, newPath, "new");
            if (!oldPath.isBlank()) load(api, destinationRepository, baseCommit, oldPath, "old");
        }
        for (String path : List.of("AGENTS.md", "CLAUDE.md", "README.md")) if (!files.containsKey("new:" + path)) {
            try { load(api, sourceRepository, sourceCommit, path, "new"); }
            catch (BitbucketClient.Rejected e) { if (e.status != 404) throw e; }
        }
        JsonNode current = api.pullRequest(pr);
        if (!sameHead(current)) throw new IllegalStateException("자료를 읽는 중 PR 커밋이 바뀌었습니다. 다시 검토하세요.");
    }
    private void load(BitbucketClient api, String repository, String commit, String path, String side) throws Exception {
        String text = api.source(repository, commit, path).replace("\r\n", "\n");
        if (text.length() > 220_000) { limitations.add(path + ": 파일이 커서 본문 검토에서 제외했습니다."); return; }
        files.put(side + ":" + path, text);
        documents.add(Json.obj("path", path, "side", side, "content", numbered(text)));
    }
    void includeCommentFile(BitbucketClient api, JsonNode comment) throws Exception {
        String path = Json.text(comment.path("inline"), "path");
        if (!path.isBlank() && !files.containsKey("new:" + path)) {
            try { load(api, Json.required(metadata.path("source").path("repository"), "full_name"), sourceCommit, path, "new"); }
            catch (BitbucketClient.Rejected e) { if (e.status != 404) throw e; limitations.add(path + ": 현재 소스에 없는 파일의 댓글입니다."); }
        }
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
        if (line + quoted.length - 1 > lines.length) return false;
        for (int offset = 0; offset < quoted.length; offset++) if (!lines[line - 1 + offset].strip().equals(quoted[offset].strip())) return false;
        return true;
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
