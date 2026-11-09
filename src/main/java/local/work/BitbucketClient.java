package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

/** Reads pinned Bitbucket data and creates pending comments with redirects confined to the API origin. */
class BitbucketClient {
    record SourceReference(String repository, String revision, String path) {
        static SourceReference parse(String url) {
            URI uri = URI.create(url.strip());
            Matcher match = Pattern.compile("^/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/src/([^/]+)/(.+\\.(?:md|txt|rst|adoc|json))$").matcher(uri.getPath() == null ? "" : uri.getPath());
            if (!"https".equals(uri.getScheme()) || !"bitbucket.org".equals(uri.getHost()) || !match.matches()
                    || Arrays.asList(match.group(3).split("/")).contains("..")) throw new IllegalArgumentException("Bitbucket의 참고 문서 파일 주소를 입력하세요.");
            return new SourceReference(match.group(1), match.group(2), match.group(3));
        }
        String url(String commit) { return "https://bitbucket.org/" + repository + "/src/" + segment(commit) + "/" + pathSegment(path); }
    }
    record PullRequest(String workspace, String repository, int number) {
        String api() { return "/repositories/" + segment(workspace) + "/" + segment(repository) + "/pullrequests/" + number; }
        String url() { return "https://bitbucket.org/" + workspace + "/" + repository + "/pull-requests/" + number; }
        static PullRequest parse(String url) {
            Matcher match = Pattern.compile("^https://bitbucket\\.org/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/pull-requests/([1-9][0-9]*)(?:[/?#].*)?$").matcher(url.strip());
            if (!match.matches()) throw new IllegalArgumentException("Bitbucket Cloud PR 주소를 입력하세요.");
            return new PullRequest(match.group(1), match.group(2), Integer.parseInt(match.group(3)));
        }
    }
    static final class Rejected extends IOException {
        final int status;
        Rejected(int status) { super("Bitbucket 요청에 실패했습니다(HTTP " + status + "). 토큰 권한과 저장소 접근 권한을 확인하세요."); this.status = status; }
    }
    private final Config config;
    private final URI root;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    BitbucketClient(Config config) { this(config, URI.create("https://api.bitbucket.org/2.0")); }
    BitbucketClient(Config config, URI root) { this.config = config; this.root = root; }
    private String email() { return config.has("BITBUCKET_EMAIL") ? config.get("BITBUCKET_EMAIL", "") : config.get("JIRA_EMAIL", ""); }
    boolean configured() { return !token().isBlank() && !email().isBlank(); }
    private String token() { return config.environment(config.get("BITBUCKET_TOKEN_ENV", "BITBUCKET_API_TOKEN")); }
    String request(String method, String path, JsonNode body) throws Exception {
        if (!configured()) throw new IllegalArgumentException("Bitbucket 계정 이메일과 API 토큰을 설정하세요.");
        URI uri = path.startsWith("/") ? URI.create(root + path) : URI.create(path);
        String auth = Base64.getEncoder().encodeToString((email() + ":" + token()).getBytes(StandardCharsets.UTF_8));
        for (int redirect = 0; redirect < 4; redirect++) {
            if (!Objects.equals(root.getScheme(), uri.getScheme()) || !Objects.equals(root.getAuthority(), uri.getAuthority()) || !uri.getPath().startsWith(root.getPath() + "/")) throw new IllegalArgumentException("Bitbucket API 외부 주소로 인증 정보를 전달할 수 없습니다.");
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)).header("Authorization", "Basic " + auth).header("Accept", "application/json");
            if (body == null) builder.GET(); else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (Set.of(301, 302, 303, 307, 308).contains(response.statusCode()) && method.equals("GET")) { uri = uri.resolve(response.headers().firstValue("Location").orElseThrow()); continue; }
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new Rejected(response.statusCode());
            return response.body();
        }
        throw new IllegalStateException("Bitbucket API의 이동 횟수가 너무 많습니다.");
    }
    JsonNode get(String path) throws Exception { return Json.read(request("GET", path, null)); }
    List<JsonNode> pages(String path) throws Exception {
        List<JsonNode> rows = new ArrayList<>(); Set<String> visited = new HashSet<>();
        while (!path.isBlank()) {
            if (!visited.add(path)) throw new IllegalStateException("Bitbucket 페이지가 반복됩니다.");
            JsonNode page = get(path); page.path("values").forEach(rows::add); path = Json.text(page, "next");
        }
        return rows;
    }
    JsonNode pullRequest(PullRequest pr) throws Exception { return get(pr.api()); }
    List<JsonNode> comments(PullRequest pr) throws Exception { return pages(pr.api() + "/comments?pagelen=100"); }
    String source(String repository, String commit, String path) throws Exception { return request("GET", "/repositories/" + repository + "/src/" + segment(commit) + "/" + pathSegment(path), null); }
    JsonNode sourceDirectory(String repository, String commit, String directory, int depth) throws Exception {
        String path = directory.isEmpty() ? "" : pathSegment(directory) + "/";
        return get("/repositories/" + repository + "/src/" + segment(commit) + "/" + path + "?pagelen=100&max_depth=" + depth);
    }
    JsonNode pendingComment(PullRequest pr, String text, String path, int line, String side, long parent) throws Exception {
        ObjectNode body = Json.obj("content", Json.obj("raw", text), "pending", true);
        if (parent > 0) body.set("parent", Json.obj("id", parent));
        else if (!path.isBlank()) body.set("inline", Json.obj("path", path, side.equals("old") ? "from" : "to", line));
        return Json.read(request("POST", pr.api() + "/comments", body));
    }
    static String segment(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    static String pathSegment(String path) { return String.join("/", Arrays.stream(path.split("/", -1)).map(BitbucketClient::segment).toList()); }
}
