package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Checks the account's Bitbucket approval and recognizes rebases that preserved the approved change. */
final class ReviewApprovals {
    private final Store store;
    private final BitbucketClient api;
    private final PullRequestReview.Model model;

    ReviewApprovals(Store store, BitbucketClient api, PullRequestReview.Model model) {
        this.store = store;
        this.api = api;
        this.model = model;
    }

    boolean excludes(BitbucketClient.PullRequest pr, JsonNode metadata, String owner) throws Exception {
        if (owner.isBlank() || !metadata.path("participants").isArray()) return false;
        for (JsonNode participant : metadata.path("participants")) {
            if (!owner.equals(Json.text(participant.path("user"), "uuid"))) continue;
            if (participant.path("approved").asBoolean()) return true;
            if (Json.text(participant, "state").equalsIgnoreCase("changes_requested")) return false;
        }
        String head = commit(metadata, "source"), destination = commit(metadata, "destination");
        if (head.isBlank() || destination.isBlank()) return false;
        String id = TranscriptImporter.hash(pr.url() + ":" + owner + ":" + head + ":" + destination);
        ObjectNode cached = store.get("prApprovalCheck", id);
        if (cached != null) return cached.path("sameChange").asBoolean();
        ObjectNode approved = approvedRevision(pr, owner);
        boolean sameChange = approved != null && !head.equals(Json.text(approved, "sourceCommit"))
                && model.sameApprovedChange(approved, revision(pr, head, destination), metadata);
        store.put("prApprovalCheck", Json.obj("id", id, "sameChange", sameChange));
        return sameChange;
    }

    private ObjectNode approvedRevision(BitbucketClient.PullRequest pr, String owner) throws Exception {
        String next = pr.api() + "/activity?pagelen=50";
        Instant approval = null;
        Set<String> visited = new HashSet<>();
        while (!next.isBlank()) {
            if (!visited.add(next)) throw new IOException("Bitbucket PR 활동 페이지가 반복됩니다.");
            JsonNode page = api.get(next);
            for (JsonNode event : page.path("values")) {
                if (approval == null) {
                    for (String key : List.of("request_changes", "unapproval")) {
                        if (owner.equals(Json.text(event.path(key).path("user"), "uuid"))) return null;
                    }
                    JsonNode vote = event.path("approval");
                    if (owner.equals(Json.text(vote.path("user"), "uuid")) && !Json.text(vote, "date").isBlank())
                        approval = KoreaTime.parse(Json.text(vote, "date"));
                } else {
                    JsonNode update = event.path("update");
                    String at = Json.text(update, "date"), head = commit(update, "source"), destination = commit(update, "destination");
                    if (at.isBlank() || head.isBlank() || destination.isBlank() || KoreaTime.parse(at).isAfter(approval)) continue;
                    try {
                        return revision(pr, api.fullCommit(BitbucketClient.repository(update,"source",pr), head),
                                api.fullCommit(BitbucketClient.repository(update,"destination",pr), destination));
                    } catch (BitbucketClient.Rejected unavailable) {
                        if (unavailable.status == 404) return null;
                        throw unavailable;
                    }
                }
            }
            next = Json.text(page, "next");
        }
        return null;
    }

    private static String commit(JsonNode metadata, String side) {
        return Json.text(metadata.path(side).path("commit"), "hash");
    }
    private static ObjectNode revision(BitbucketClient.PullRequest pr, String head, String destination) {
        return Json.obj("url", pr.url(), "sourceCommit", head, "destinationCommit", destination);
    }
}
