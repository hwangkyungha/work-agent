package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

/** Finds the owner's review requests and unanswered threads across accessible repositories. */
final class ReviewMonitor implements AutoCloseable {
    @FunctionalInterface interface CompletionNotifier { void send(String text, ObjectNode review) throws Exception; }
    private static final int FIRST_SCAN_HOUR = 8;
    private static final int END_SCAN_HOUR = 17;
    private static final int SCAN_INTERVAL_MINUTES = 30;
    private final WorkService service;
    private final BitbucketClient api;
    private final PullRequestReview reviews;
    private final Config config;
    private final CompletionNotifier notifier;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean scanning;
    ReviewMonitor(WorkService service, BitbucketClient api, PullRequestReview reviews, Config config, Scheduler.Notifier notifier) {
        this(service, api, reviews, config, (text, review) -> notifier.send(text));
    }
    ReviewMonitor(WorkService service, BitbucketClient api, PullRequestReview reviews, Config config, CompletionNotifier notifier) {
        this.service = service; this.api = api; this.reviews = reviews; this.config = config; this.notifier = notifier;
    }
    void start() {
        reviews.resume();
        if (api.configured() && config.get("BITBUCKET_AUTO_REVIEW", "true").equals("true")) scheduleNextScan();
        worker.scheduleWithFixedDelay(() -> { try { notifyCompleted(); } catch (Exception e) { System.err.println("PR 알림을 보내지 못했습니다: " + SlackConnection.friendly(e)); } }, 30, 30, TimeUnit.SECONDS);
    }
    ObjectNode refresh() {
        if (beginScan()) worker.submit(this::tick);
        return state();
    }
    private synchronized boolean beginScan() {
        if (scanning) return false;
        scanning = true;
        return true;
    }
    static ZonedDateTime nextScanTime(ZonedDateTime now) {
        ZonedDateTime next = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(SCAN_INTERVAL_MINUTES - now.getMinute() % SCAN_INTERVAL_MINUTES);
        if (next.getHour() >= END_SCAN_HOUR) return next.plusDays(1).withHour(FIRST_SCAN_HOUR).withMinute(0);
        if (next.getHour() < FIRST_SCAN_HOUR) return next.withHour(FIRST_SCAN_HOUR).withMinute(0);
        return next;
    }
    private void scheduleNextScan() {
        if (worker.isShutdown()) return;
        ZonedDateTime now = service.time.now();
        ZonedDateTime target = nextScanTime(now);
        worker.schedule(() -> {
            try {
                ZonedDateTime actual = service.time.now();
                if (!actual.isBefore(target) && actual.isBefore(target.plusMinutes(SCAN_INTERVAL_MINUTES)) && beginScan()) tick();
            } finally {
                scheduleNextScan();
            }
        }, Duration.between(now, target).toNanos(), TimeUnit.NANOSECONDS);
    }
    private void tick() {
        try { scan(); }
        catch (Exception e) {
            ObjectNode status = service.store.get("reviewMonitor", "status");
            if (status == null) status = Json.obj("id", "status", "items", List.of());
            status.put("error", SlackConnection.friendly(e)).put("attemptedAt", service.time.instant().toString()); service.store.put("reviewMonitor", status);
        } finally { scanning = false; }
        try { notifyCompleted(); } catch (Exception e) { System.err.println("PR 알림을 보내지 못했습니다: " + SlackConnection.friendly(e)); }
    }
    ObjectNode scan() throws Exception {
        String owner = Json.required(api.get("/user"), "uuid");
        Set<String> workspaces = new LinkedHashSet<>(); List<String> warnings = new ArrayList<>();
        try { for (JsonNode membership : api.pages("/user/workspaces?pagelen=100")) { String slug = Json.text(membership.path("workspace"), "slug"); if (!slug.isBlank()) workspaces.add(slug); } }
        catch (BitbucketClient.Rejected e) { if (e.status != 403) throw e; warnings.add("워크스페이스 자동 조회 권한이 없습니다. read:workspace:bitbucket 권한을 추가하면 모든 워크스페이스를 찾습니다. 현재는 BITBUCKET_WORKSPACES에 지정한 범위를 확인합니다."); }
        for (String slug : config.get("BITBUCKET_WORKSPACES", "").split(",")) if (!slug.isBlank()) workspaces.add(slug.strip());
        if (workspaces.isEmpty()) throw new IllegalArgumentException("확인할 워크스페이스가 없습니다. read:workspace:bitbucket 권한을 추가하거나 BITBUCKET_WORKSPACES를 설정하세요.");
        List<ObjectNode> items = new ArrayList<>(); Set<String> seen = new HashSet<>(); int repositoryCount = 0;
        for (String workspace : workspaces) {
            List<JsonNode> repositories;
            try { repositories = api.pages("/user/workspaces/" + BitbucketClient.segment(workspace) + "/permissions/repositories?pagelen=100"); }
            catch (Exception e) { warnings.add(workspace + ": " + SlackConnection.friendly(e)); continue; }
            for (JsonNode permission : repositories) {
                String fullName = Json.text(permission.path("repository"), "full_name");
                if (!fullName.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || !seen.add(fullName)) continue;
                repositoryCount++;
                try {
                    String query = "state=\"OPEN\" AND (author.uuid=\"" + owner + "\" OR reviewers.uuid=\"" + owner + "\")";
                    for (JsonNode pr : api.pages("/repositories/" + fullName + "/pullrequests?pagelen=50&fields=%2Bvalues.reviewers&q=" + BitbucketClient.segment(query))) {
                        boolean own = owner.equals(Json.text(pr.path("author"), "uuid")), reviewer = false;
                        for (JsonNode person : pr.path("reviewers")) reviewer |= owner.equals(Json.text(person, "uuid"));
                        if (!Json.text(pr, "state").equals("OPEN") || (!own && !reviewer)) continue;
                        String url = "https://bitbucket.org/" + fullName + "/pull-requests/" + pr.path("id").asInt();
                        if (!own && reviewer) { ObjectNode job = reviews.request(url, 0, true); items.add(item(job, fullName, "review")); }
                        if (own) {
                            List<JsonNode> comments = api.comments(BitbucketClient.PullRequest.parse(url));
                            for (JsonNode incoming : unanswered(comments, owner)) { ObjectNode job = reviews.request(url, incoming.path("id").asLong(), true); items.add(item(job, fullName, "reply")); }
                        }
                    }
                } catch (Exception e) { warnings.add(fullName + ": " + SlackConnection.friendly(e)); }
            }
        }
        return service.store.put("reviewMonitor", Json.obj("id", "status", "at", service.time.instant().toString(), "repositoryCount", repositoryCount, "workspaces", workspaces, "warnings", warnings, "items", items));
    }
    private static ObjectNode item(ObjectNode job, String repository, String kind) { return Json.obj("id", Json.text(job, "id"), "url", Json.text(job, "url"), "repository", repository, "kind", kind); }
    static List<JsonNode> unanswered(List<JsonNode> comments, String owner) {
        Map<Long, JsonNode> byId = new HashMap<>(), latest = new LinkedHashMap<>();
        for (JsonNode comment : comments) if (!comment.path("deleted").asBoolean()) byId.put(comment.path("id").asLong(), comment);
        comments.stream().filter(comment -> !comment.path("deleted").asBoolean()).sorted(Comparator.comparing(comment -> Json.text(comment, "created_on"))).forEach(comment -> {
            long root = comment.path("id").asLong(); JsonNode ancestor = comment; Set<Long> visited = new HashSet<>();
            while (ancestor.path("parent").path("id").asLong() > 0 && visited.add(root)) { root = ancestor.path("parent").path("id").asLong(); ancestor = byId.get(root); if (ancestor == null) break; }
            latest.put(root, comment);
        });
        return latest.entrySet().stream().filter(entry -> byId.containsKey(entry.getKey()) && !byId.get(entry.getKey()).path("resolution").isObject()).map(Map.Entry::getValue)
                .filter(comment -> !owner.equals(Json.text(comment.path("user"), "uuid")) && !comment.path("pending").asBoolean()).toList();
    }
    ObjectNode state() {
        ObjectNode status = service.store.get("reviewMonitor", "status");
        if (status == null) status = Json.obj("items", List.of(), "warnings", List.of());
        status.put("scanning", scanning).put("configured", api.configured()).put("automatic", config.get("BITBUCKET_AUTO_REVIEW", "true").equals("true"));
        List<ObjectNode> jobs = service.store.list("prReview"); Collections.reverse(jobs); status.set("jobs", Json.MAPPER.valueToTree(jobs));
        status.set("repositories", Json.MAPPER.valueToTree(reviews.sources.settings()));
        status.set("localRepositories", Json.MAPPER.valueToTree(service.store.list("prRepositoryPath")));
        status.set("sessionBindings", Json.MAPPER.valueToTree(service.store.list("prSessionBinding")));
        status.set("sessions", Json.MAPPER.valueToTree(service.store.list("session").stream().filter(row -> !Json.text(row,"session").isBlank()).sorted(Comparator.comparing((ObjectNode row)->Json.text(row,"updatedAt")).reversed()).limit(200).map(row -> Json.obj("id",Json.text(row,"id"),"name",Json.text(row,"name"),"cwd",Json.text(row,"cwd"))).toList()));
        return status;
    }
    synchronized void notifyCompleted() throws Exception {
        for (ObjectNode row : service.store.list("prReview")) {
            if (row.path("validation").asBoolean() || row.path("notified").asBoolean() || PullRequestReview.inProgress(row)) continue;
            if (!Set.of("ready", "saved", "failed", "stale", "delivery-unknown").contains(Json.text(row, "status"))) continue;
            String key = PullRequestReview.notificationKey(row);
            boolean sent = service.store.get("prReviewNotice", key) != null || service.store.list("prReview").stream()
                    .anyMatch(old -> !old.path("validation").asBoolean() && old.path("notified").asBoolean() && key.equals(PullRequestReview.notificationKey(old)));
            if (!sent) notifier.send(PullRequestReview.resultMessage(row), row);
            synchronized (service.store) {
                service.store.put("prReviewNotice", Json.obj("id", key, "reviewId", Json.text(row, "id"), "at", service.time.instant().toString()));
                ObjectNode current = service.store.get("prReview", Json.required(row, "id"));
                if (current != null) { current.put("notified", true); service.store.put("prReview", current); }
            }
        }
    }
    @Override public void close() { worker.shutdownNow(); }
}
