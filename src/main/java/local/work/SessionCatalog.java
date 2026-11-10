package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Session titles are display text; the original tool and session ID are the durable link. */
final class SessionCatalog {
    private final Store store;
    SessionCatalog(Store store) { this.store = store; }
    void refresh() {
        Map<String, ObjectNode> sessions = new LinkedHashMap<>();
        for (ObjectNode row : store.list("session")) sessions.put(Json.text(row,"id"),row);
        for (ObjectNode row : store.list("evidence")) {
            String source = Json.text(row,"source"), session = Json.text(row,"session");
            if (!Set.of("claude","codex").contains(source) || session.isBlank()) continue;
            String id = source + ":" + session;
            ObjectNode saved = sessions.computeIfAbsent(id, key -> Json.obj("id",id));
            saved.put("source",source).put("session",session);
            if (!Json.text(row,"cwd").isBlank()) saved.put("cwd",Json.text(row,"cwd"));
            String at = Json.text(row,"occurredAt");
            if (at.compareTo(Json.text(saved,"updatedAt")) > 0) saved.put("updatedAt",at);
            if (Json.text(saved,"name").isBlank() && Json.text(row,"role").equals("user")) {
                String title = Json.text(row,"text").lines().map(String::trim).filter(line->!line.isBlank()).findFirst().orElse("");
                saved.put("name",title.substring(0,Math.min(120,title.length())));
            }
        }
        sessions.values().forEach(row -> store.put("session",row));
    }
    void codexTitles(Path state) throws Exception {
        if (!Files.isRegularFile(state)) return;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + state.toAbsolutePath().toString().replace('\\','/') + "?mode=ro");
             Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("SELECT id,title,cwd,updated_at FROM threads WHERE has_user_event=1")) {
            while(rows.next()) {
                String id="codex:"+rows.getString("id");ObjectNode old=store.get("session",id);
                // Only enrich sessions already collected by the transcript importer.
                if(old==null)continue;
                old.put("source","codex").put("session",rows.getString("id")).put("cwd",rows.getString("cwd"));
                String title=rows.getString("title");if(title!=null && !title.isBlank())old.put("name",title);
                store.put("session",old);
            }
        }
    }
    static Set<String> keys(String text) {
        return new LinkedHashSet<>(java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]*-[0-9]+").matcher(text).results().map(match->match.group()).toList());
    }
    List<ObjectNode> candidates(JsonNode metadata, Path repository) {
        Set<String> issues=keys(Json.text(metadata,"title")+" "+Json.text(metadata.path("source").path("branch"),"name"));
        String root=repository.toAbsolutePath().normalize().toString().replace('\\','/').toLowerCase(Locale.ROOT);
        return store.list("session").stream().filter(row->{
            String cwd=Json.text(row,"cwd").replace('\\','/').toLowerCase(Locale.ROOT);
            if (Collections.disjoint(issues,keys(Json.text(row,"name"))) || Json.text(row,"session").isBlank() || cwd.contains("/review-worktrees/") || ActivitySummary.automatedPath(cwd) || cwd.endsWith("/work/agent")) return false;
            if (cwd.equals(root)||cwd.startsWith(root+"/")) return true;
            try { return ReviewWorkspaces.git(Path.of(Json.text(row,"cwd")),"rev-parse","--path-format=absolute","--git-common-dir").trim().equals(ReviewWorkspaces.git(repository,"rev-parse","--path-format=absolute","--git-common-dir").trim()); }
            catch(Exception e) { return false; }
        }).toList();
    }
}
