package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BitbucketHttpTest {
    @TempDir Path temporary;
    HttpServer server; BitbucketClient client; JsonNode posted; String authorization;
    String redirect; AtomicInteger hits = new AtomicInteger();
    @BeforeEach void open() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet(); authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String path = exchange.getRequestURI().getPath(), body;
            if (path.endsWith("/redirect")) { exchange.getResponseHeaders().set("Location", redirect); exchange.sendResponseHeaders(302, -1); exchange.close(); return; }
            if (exchange.getRequestMethod().equals("POST")) { posted = Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); body = "{\"id\":1,\"pending\":true}"; }
            else body = path.endsWith("/page2") ? "{\"values\":[{\"id\":2}]}" : "{\"values\":[{\"id\":1}],\"next\":\"" + origin() + "/2.0/page2\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        client = new BitbucketClient(new Config(temporary.resolve("none"), Map.of("BITBUCKET_EMAIL", "", "JIRA_EMAIL", "owner@example.com", "BITBUCKET_API_TOKEN", "not-a-real-token")), URI.create(origin() + "/2.0"));
    }
    String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    @AfterEach void close() { server.stop(0); }
    @Test void followsAuthenticatedSameOriginRedirectAndEveryPage() throws Exception {
        redirect = origin() + "/2.0/page1";
        assertEquals(2, client.pages("/redirect").size()); assertEquals(3, hits.get());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("owner@example.com:not-a-real-token".getBytes(StandardCharsets.UTF_8)), authorization);
    }
    @Test void neverSendsCredentialsToAnotherOriginOrOutsideApiPrefix() {
        redirect = "https://example.com/2.0/page1";
        assertThrows(IllegalArgumentException.class, () -> client.get("/redirect")); assertEquals(1, hits.get());
        assertThrows(IllegalArgumentException.class, () -> client.get(origin() + "/not-api")); assertEquals(1, hits.get());
    }
    @Test void createsOnlyPendingInlineCommentsOrParentReplies() throws Exception {
        BitbucketClient.PullRequest pr = BitbucketClient.PullRequest.parse("https://bitbucket.org/team/repo/pull-requests/123");
        client.pendingComment(pr, "검토 댓글", "파일.java", 12, "old", 0);
        assertTrue(posted.path("pending").asBoolean()); assertEquals(12, posted.path("inline").path("from").asInt()); assertFalse(posted.has("parent"));
        client.pendingComment(pr, "답변", "", 0, "new", 77);
        assertTrue(posted.path("pending").asBoolean()); assertEquals(77, posted.path("parent").path("id").asInt()); assertFalse(posted.has("inline"));
        assertThrows(IllegalArgumentException.class, () -> BitbucketClient.PullRequest.parse("https://bitbucket.org.attacker.test/team/repo/pull-requests/1"));
    }
}
