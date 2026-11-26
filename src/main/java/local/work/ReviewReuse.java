package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.*;

/** Compares committed review inputs before creating a new checkout or starting the model. */
final class ReviewReuse {
    static String background(JsonNode metadata, List<JsonNode> comments) {
        var relevant = comments.stream().filter(comment -> !comment.path("deleted").asBoolean() && !comment.path("pending").asBoolean()
                && !Json.text(comment.path("content"), "raw").contains("<!-- work-agent-review:"))
                .sorted(Comparator.comparingLong(comment -> comment.path("id").asLong()))
                .map(comment -> Json.obj("id", comment.path("id"), "parent", comment.path("parent").path("id"),
                        "text", Json.text(comment.path("content"), "raw"), "inline", comment.path("inline"))).toList();
        return TranscriptImporter.hash(Json.write(Json.obj("title", Json.text(metadata, "title"), "description", Json.text(metadata, "description"), "comments", relevant)));
    }

    static boolean comparable(JsonNode row) {
        return row.path("referenceUrls").isEmpty() && row.path("repositorySources").isEmpty();
    }
    static boolean sameApprovedChange(Path clone, JsonNode approved, JsonNode current) throws Exception {
        String oldHead = Json.required(approved, "sourceCommit"), head = Json.required(current, "sourceCommit");
        String oldBase = ReviewWorkspaces.git(clone, "merge-base", oldHead, Json.required(approved, "destinationCommit")).strip();
        String base = ReviewWorkspaces.git(clone, "merge-base", head, Json.required(current, "destinationCommit")).strip();
        if (changes(clone, oldBase, oldHead).equals(changes(clone, base, head))) return true;
        String rebased = ReviewWorkspaces.git(clone, "merge-tree", "--write-tree", "--no-messages", "--merge-base=" + oldBase, base, oldHead).strip();
        return rebased.equals(ReviewWorkspaces.git(clone, "rev-parse", head + "^{tree}").strip());
    }

    static String equivalentBase(Path clone, JsonNode previous, JsonNode current) throws Exception {
        String oldHead = Json.text(previous, "sourceCommit"), oldBase = Json.text(previous, "baseCommit");
        String head = Json.text(current, "sourceCommit"), destination = Json.text(current, "destinationCommit");
        if (List.of(oldHead, oldBase, head, destination).stream().anyMatch(hash -> !hash.matches("[0-9a-f]{40,64}"))) return "";
        if (!previous.path("reviewEvidencePaths").isArray() || !comparable(previous) || !comparable(current)
                || Json.text(previous,"reviewBackground").isBlank() || !Json.text(previous,"reviewBackground").equals(Json.text(current,"reviewBackground"))) return "";
        String base = ReviewWorkspaces.git(clone,"merge-base",head,destination).trim();
        Map<String,String> old = changes(clone,oldBase,oldHead), next = changes(clone,base,head);
        Set<String> evidence = new LinkedHashSet<>(List.of("AGENTS.md", "CLAUDE.md", "README.md"));
        Set<String> reported = new LinkedHashSet<>();
        for (JsonNode path : previous.path("reviewEvidencePaths")) {
            if (!repositoryPath(path.asText())) return "";
            reported.add(path.asText());
        }
        if (!missingFiles(clone, oldBase, missingFiles(clone, oldHead, reported)).isEmpty()) return "";
        evidence.addAll(reported);
        for (var entry : old.entrySet()) {
            String path = entry.getKey();
            if (Objects.equals(entry.getValue(), next.get(path))) continue;
            if (next.containsKey(path) || evidence.contains(path) || !entry.getValue().endsWith(" A")
                    || !path.matches("openspec/changes/[^/]+/(?:\\.openspec\\.yaml|brief\\.md|tasks\\.md)")
                    || !treeEntry(clone, head, path).isEmpty()) return "";
        }
        if (next.isEmpty() || !old.keySet().containsAll(next.keySet())) return "";
        if (!sameFiles(clone,oldHead,head,evidence) || !sameFiles(clone,oldBase,base,evidence)) return "";
        return base;
    }

    private static Map<String,String> changes(Path clone, String base, String head) throws Exception {
        String raw = ReviewWorkspaces.git(clone,"diff","--raw","--no-abbrev","--no-renames","--no-ext-diff","-z",base,head,"--");
        String[] fields = raw.split("\0",-1);
        Map<String,String> result = new TreeMap<>();
        for (int i = 0; i + 1 < fields.length; i += 2) result.put(fields[i+1],fields[i]);
        return result;
    }

    private static String treeEntry(Path clone, String revision, String path) throws Exception {
        return ReviewWorkspaces.git(clone,"--literal-pathspecs","ls-tree","--full-tree","-z",revision,"--",path);
    }

    private static Set<String> missingFiles(Path clone, String commit, Set<String> paths) throws Exception {
        if (paths.isEmpty()) return paths;
        List<String> args = new ArrayList<>(List.of("--literal-pathspecs","ls-tree","-r","--name-only","--full-tree","-z",commit,"--"));
        args.addAll(paths);
        Set<String> missing = new LinkedHashSet<>(paths);
        missing.removeAll(Arrays.asList(ReviewWorkspaces.git(clone,args.toArray(String[]::new)).split("\0",-1)));
        return missing;
    }

    private static boolean sameFiles(Path clone, String old, String next, Set<String> paths) throws Exception {
        if (old.equals(next)) return true;
        List<String> args = new ArrayList<>(List.of("--literal-pathspecs","diff","--name-only","--no-renames","-z",old,next,"--"));
        args.addAll(paths);
        return ReviewWorkspaces.git(clone,args.toArray(String[]::new)).isEmpty();
    }

    static boolean repositoryPath(String path) {
        return !path.isBlank() && !path.startsWith("/") && !path.contains("\\") && !path.contains(":")
                && Arrays.stream(path.split("/",-1)).noneMatch(part -> part.isBlank() || part.equals(".") || part.equals("..") || part.equalsIgnoreCase(".git"))
                && path.chars().noneMatch(Character::isISOControl);
    }

    static void rememberEvidence(ObjectNode row, ReviewContext context, JsonNode result) {
        if (!result.path("reviewedPaths").isArray()) return;
        Set<String> paths = new LinkedHashSet<>();
        result.path("reviewedPaths").forEach(path -> paths.add(path.asText()));
        for (var source : context.files.keySet()) paths.add(source.substring(source.indexOf(':')+1));
        if (paths.size() <= 200 && paths.stream().allMatch(ReviewReuse::repositoryPath)) row.set("reviewEvidencePaths",Json.MAPPER.valueToTree(paths));
    }
}
