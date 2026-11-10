package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;

/** One local task owns an optional Jira key; merging changes references, not recorded work. */
final class TaskIdentity {
    private static final List<String> USER_FIELDS = List.of("important", "urgent", "hidden", "workGroup", "workType");
    private static final Set<String> REFERENCES = Set.of("taskId", "workId", "sourceTaskId", "acceptedTaskId", "suggestedTaskId");
    private static final List<String> KINDS = List.of("task", "plan", "planRevision", "activity", "evidence", "sessionDay", "sessionLink", "workLink", "taskChange", "followup", "worklog", "workDay", "draftDigest", "draftResult");
    private final Store store;
    TaskIdentity(Store store) { this.store = store; }
    String resolve(String id) {
        Set<String> seen = new HashSet<>(); ObjectNode alias;
        while (!id.isBlank() && (alias = store.get("taskAlias", id)) != null) {
            if (!seen.add(id)) throw new IllegalStateException("업무 연결에 순환 참조가 있습니다.");
            id = Json.required(alias, "taskId");
        }
        return id;
    }
    ObjectNode forKey(String key) {
        return store.list("task").stream().filter(row -> key.equals(Json.text(row,"jiraKey")))
                .min(Comparator.comparing(row -> Json.text(row,"id").startsWith("jira:"))).orElse(null);
    }
    int repair() {
        Map<String,List<ObjectNode>> groups = new LinkedHashMap<>();
        for (ObjectNode task : store.list("task")) if (!Json.text(task,"jiraKey").isBlank()) groups.computeIfAbsent(Json.text(task,"jiraKey"), unused -> new ArrayList<>()).add(task);
        if (groups.values().stream().anyMatch(group -> group.size()>1)) store.backupTaskMerge();
        int count = 0;
        for (ObjectNode conflict : store.list("taskMergeConflict")) if (groups.getOrDefault(Json.text(conflict,"jiraKey"),List.of()).size() < 2) store.delete("taskMergeConflict",Json.text(conflict,"id"));
        for (var group : groups.entrySet()) if (group.getValue().size() > 1) {
            try {
                store.transaction(() -> store.put("task", merge(forKey(group.getKey()))));
                count += group.getValue().size() - 1;
            } catch (IllegalArgumentException conflict) {
                store.put("taskMergeConflict", Json.obj("id",group.getKey(),"jiraKey",group.getKey(),"message",conflict.getMessage()));
            }
        }
        if (store.list("taskMergeConflict").isEmpty()) store.enforceUniqueTaskKeys();
        return count;
    }
    ObjectNode merge(ObjectNode candidate) {
        String key = Json.text(candidate,"jiraKey"), candidateId = Json.text(candidate,"id");
        if (key.isBlank()) return candidate;
        List<ObjectNode> group = new ArrayList<>();
        for (ObjectNode row : store.list("task")) if (key.equals(Json.text(row,"jiraKey")) && !candidateId.equals(Json.text(row,"id"))) group.add(row);
        if (group.isEmpty()) return candidate;
        group.add(candidate);
        Set<String> groupIds=new HashSet<>();group.forEach(row -> groupIds.add(Json.text(row,"id")));
        String preferred=store.list("task").stream().filter(row -> groupIds.contains(Json.text(row,"id")) && !Json.text(row,"id").startsWith("jira:")).map(row -> Json.text(row,"id")).findFirst().orElse(Json.text(group.getFirst(),"id"));
        ObjectNode owner=group.stream().filter(row -> preferred.equals(Json.text(row,"id"))).findFirst().orElseThrow();
        ObjectNode merged = owner.deepCopy(); String ownerId = Json.text(owner,"id");
        Map<String,String> aliases = new LinkedHashMap<>();
        for (ObjectNode row : group) {
            String id = Json.text(row,"id"); if (!id.equals(ownerId)) aliases.put(id,ownerId);
            row.fields().forEachRemaining(field -> { if (!merged.has(field.getKey())) merged.set(field.getKey(),field.getValue()); });
        }
        for (String field : USER_FIELDS) {
            JsonNode chosen = null;
            for (ObjectNode row : group) if (userValue(row,field)) {
                if (chosen != null && !chosen.equals(row.get(field))) throw new IllegalArgumentException(key+"의 중복 업무에서 '"+Map.of("important","중요도","urgent","긴급도","hidden","숨김","workGroup","업무 묶음","workType","업무 성격").get(field)+"' 설정이 다릅니다. 두 업무의 값을 확인해 일치시킨 뒤 다시 연결하세요.");
                chosen = row.get(field);
            }
            if (chosen != null) merged.set(field,chosen);
        }
        Set<String> previous = new LinkedHashSet<>();
        boolean explicitPrevious = group.stream().anyMatch(row -> row.has("predecessorIds"));
        if (explicitPrevious) {
            for (ObjectNode row : group) for (String id : TaskStructure.predecessors(row)) previous.add(aliases.getOrDefault(id,id));
            previous.remove(ownerId); merged.set("predecessorIds",Json.MAPPER.valueToTree(previous));
        }
        ObjectNode issue = store.get("issue",key);
        if (issue != null) for (String field : List.of("title","status","due","product","jiraStatus","jiraStatusId","issueUrl","issueType","jiraParent","jiraPredecessors","createdAt")) if (issue.hasNonNull(field)) merged.set(field,issue.get(field));
        merged.put("id",ownerId).put("jiraKey",key);
        rewrite(merged,aliases,false);
        for (String kind : KINDS) for (ObjectNode original : store.list(kind)) {
            if (kind.equals("task") && (aliases.containsKey(Json.text(original,"id")) || ownerId.equals(Json.text(original,"id")))) continue;
            ObjectNode updated = original.deepCopy(); rewrite(updated,aliases,false);
            if (kind.equals("workDay")) updated.put("id",Json.text(updated,"date")+":"+Json.text(updated,"workId"));
            if (kind.equals("workLink")) updated.put("id",aliases.getOrDefault(Json.text(updated,"id"),Json.text(updated,"id")));
            if (!updated.equals(original)) {
                String oldId=Json.text(original,"id"), newId=Json.text(updated,"id");
                ObjectNode existing = oldId.equals(newId)?null:store.get(kind,newId);
                if (existing != null) updated = combine(kind,existing,updated,key);
                if (!oldId.equals(newId)) store.delete(kind,oldId);
                if (kind.equals("plan")) updated.put("version",updated.path("version").asInt()+1);
                store.put(kind,updated);
            }
        }
        List<ObjectNode> tasks = new ArrayList<>(store.list("task").stream().filter(row -> !aliases.containsKey(Json.text(row,"id")) && !ownerId.equals(Json.text(row,"id"))).toList());
        tasks.add(merged);
        for (ObjectNode task : tasks) TaskStructure.validate(task,tasks);
        for (var alias : aliases.entrySet()) {
            store.put("taskMerge",Json.obj("id",alias.getKey(),"taskId",ownerId,"jiraKey",key,"mergedAt",Instant.now().toString(),"originalTasks",group));
            store.put("taskAlias",Json.obj("id",alias.getKey(),"taskId",ownerId));
            store.delete("task",alias.getKey());
        }
        store.delete("taskMergeConflict",key);
        return merged;
    }
    private static boolean userValue(ObjectNode row,String field) {
        if (!row.hasNonNull(field)) return false;
        if (Set.of("important","urgent","hidden").contains(field)) return row.path(field).asBoolean() || !Json.text(row,"id").startsWith("jira:") || row.hasNonNull("updatedAt");
        return true;
    }
    private static void rewrite(JsonNode node,Map<String,String> aliases,boolean snapshot) {
        if (node.isObject()) {
            ObjectNode object=(ObjectNode)node;
            object.fields().forEachRemaining(field -> {
                String name=field.getKey(); JsonNode value=field.getValue();
                if ((REFERENCES.contains(name) || snapshot && name.equals("id")) && value.isTextual()) object.put(name,aliases.getOrDefault(value.asText(),value.asText()));
                else if ((name.equals("taskIds") || name.equals("predecessorIds")) && value.isArray()) {
                    Set<String> ids=new LinkedHashSet<>(); value.forEach(id -> ids.add(aliases.getOrDefault(id.asText(),id.asText()))); object.set(name,Json.MAPPER.valueToTree(ids));
                } else if (name.equals("initialTasks") || name.equals("taskSnapshots")) value.forEach(task -> rewrite(task,aliases,true));
                else rewrite(value,aliases,false);
            });
        } else if (node.isArray()) node.forEach(child -> rewrite(child,aliases,false));
    }
    private static ObjectNode combine(String kind,ObjectNode first,ObjectNode next,String key) {
        if (kind.equals("workDay")) {
            String a=Json.text(first,"outcome"),b=Json.text(next,"outcome");
            if (!a.equals(b) && !a.equals("unconfirmed") && !b.equals("unconfirmed")) throw new IllegalArgumentException(key+"의 "+Json.text(first,"date")+" 수행 상태가 다릅니다. 상태를 확인한 뒤 병합하세요.");
            ObjectNode result=(a.equals("unconfirmed")?next:first).deepCopy();
            Set<String> notes=new LinkedHashSet<>(List.of(Json.text(first,"note"),Json.text(next,"note")));notes.remove("");result.put("note",String.join("\n",notes));return result;
        }
        if (!first.equals(next) && !Json.text(first,"taskId").equals(Json.text(next,"taskId"))) throw new IllegalArgumentException(key+"의 활동 연결이 서로 다릅니다.");
        return first;
    }
}
