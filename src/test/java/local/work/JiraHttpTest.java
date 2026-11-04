package local.work;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class JiraHttpTest {
    @TempDir Path temporary;
    HttpServer server;
    String method,path,query,authorization;
    JsonNode received;
    int status=201;
    String response="{\"id\":\"12345\"}";
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            method=exchange.getRequestMethod();path=exchange.getRequestURI().getPath();query=exchange.getRequestURI().getQuery();authorization=exchange.getRequestHeaders().getFirst("Authorization");
            byte[] input=exchange.getRequestBody().readAllBytes();if(input.length>0)received=Json.read(new String(input,StandardCharsets.UTF_8));
            byte[] output=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,output.length);exchange.getResponseBody().write(output);exchange.close();
        });server.start();
    }
    @AfterEach void stop() {server.stop(0);}
    JiraClient client(String kind,String email) throws Exception {
        Path file=temporary.resolve("config.properties");
        Files.writeString(file,"JIRA_URL=http://127.0.0.1:"+server.getAddress().getPort()+"/jira\nJIRA_TOKEN=test-token\nJIRA_KIND="+kind+"\nJIRA_EMAIL="+email+"\n");
        return new JiraClient(new Config(file,Map.of()));
    }
    JsonNode item(){return Json.obj("jiraKey","CT-123","seconds",17280,"comment","원인 분석","marker","work-agent:test");}
    @Test void serverRequestUsesContextPathBearerAndKoreaStartedTime() throws Exception {
        assertEquals("12345",client("server","").addWorklog(LocalDate.of(2026,11,4),item()));
        assertEquals("POST",method);assertEquals("/jira/rest/api/2/issue/CT-123/worklog",path);assertEquals("adjustEstimate=leave",query);assertTrue("Bearer test-token".equals(authorization),"모의 인증 정보만 사용해야 합니다.");
        assertEquals("2026-11-04T09:00:00.000+0900",Json.text(received,"started"));assertEquals(17280,received.path("timeSpentSeconds").asInt());assertTrue(Json.text(received,"comment").contains("work-agent:test"));
    }
    @Test void cloudRequestUsesAdfAndBasicAuth() throws Exception {
        client("cloud","user@example.com").addWorklog(LocalDate.of(2026,11,4),item());
        assertEquals("/jira/rest/api/3/issue/CT-123/worklog",path);assertEquals("doc",Json.text(received.path("comment"),"type"));assertTrue(authorization.startsWith("Basic "));
        assertTrue(received.path("comment").toString().contains("원인 분석"));
    }
    @Test void clientErrorsAreDefiniteButServerErrorsRemainUncertain() throws Exception {
        JiraClient client=client("server","");status=403;
        assertThrows(JiraClient.Rejected.class,()->client.addWorklog(LocalDate.of(2026,11,4),item()));status=503;
        Exception error=assertThrows(JiraClient.IOExceptionBase.class,()->client.addWorklog(LocalDate.of(2026,11,4),item()));assertFalse(error instanceof JiraClient.Rejected);
    }
    @Test void markerCanBeRecoveredFromExistingRemoteWorklog() throws Exception {
        status=200;response="{\"total\":1,\"worklogs\":[{\"id\":\"remote-id\",\"comment\":\"업무 기록 [work-agent:test]\"}]}";
        assertEquals("remote-id",client("server","").findMarker("CT-123","work-agent:test"));
        assertEquals("GET",method);assertTrue(query.contains("startAt=0"));
    }
    @Test void emptyTokenDoesNotEnableJira() throws Exception {
        Path file=temporary.resolve("empty.properties");Files.writeString(file,"JIRA_URL=https://example.invalid\nJIRA_TOKEN=\n");
        assertFalse(new JiraClient(new Config(file,Map.of())).configured());
    }
    @Test void supportedTimestampOffsetsGroupByKoreaDate() {
        assertEquals(LocalDate.of(2026,11,4),JiraClient.parseJiraDate("2026-11-03T16:00:00.000+0000"));
        assertEquals(LocalDate.of(2026,11,4),JiraClient.parseJiraDate("2026-11-03T16:00:00.000+00:00"));
        assertEquals(LocalDate.of(2026,11,4),JiraClient.parseJiraDate("2026-11-03T16:00:00Z"));
    }
    @Test void selectedTokenVariableTakesPrecedenceOverLegacyToken() throws Exception {
        Path file=temporary.resolve("selected.properties");
        Files.writeString(file,"JIRA_URL=http://127.0.0.1:"+server.getAddress().getPort()+"\nJIRA_KIND=cloud\nJIRA_EMAIL=user@example.com\nJIRA_TOKEN_ENV=JIRA_API_TOKEN\nJIRA_TOKEN=legacy-config\n");
        var client=new JiraClient(new Config(file,Map.of("JIRA_API_TOKEN","selected-test-token","JIRA_TOKEN","legacy-test-token")));
        client.addWorklog(LocalDate.of(2026,11,5),item());
        String expected="Basic "+Base64.getEncoder().encodeToString("user@example.com:selected-test-token".getBytes(StandardCharsets.UTF_8));
        assertTrue(expected.equals(authorization),"설정한 모의 환경변수만 사용해야 합니다.");
    }
    @Test void missingSelectedVariableDoesNotFallBackToLegacyToken() throws Exception {
        Path file=temporary.resolve("selected-missing.properties");
        Files.writeString(file,"JIRA_URL=https://example.invalid\nJIRA_TOKEN_ENV=JIRA_API_TOKEN\nJIRA_TOKEN=legacy-config\n");
        assertFalse(new JiraClient(new Config(file,Map.of("JIRA_TOKEN","legacy-test-token"))).configured());
    }
    @Test void scopedTokenGatewayBaseKeepsCloudIdInRequestPath() throws Exception {
        Path file=temporary.resolve("gateway.properties");
        Files.writeString(file,"JIRA_URL=http://127.0.0.1:"+server.getAddress().getPort()+"/ex/jira/test-cloud-id\nJIRA_KIND=cloud\nJIRA_EMAIL=user@example.com\nJIRA_TOKEN_ENV=JIRA_API_TOKEN\n");
        status=200;response="{\"accountId\":\"test-account\"}";
        var client=new JiraClient(new Config(file,Map.of("JIRA_API_TOKEN","gateway-test-token")));
        assertEquals("test-account",Json.text(client.request("GET","/myself",null),"accountId"));
        assertEquals("/ex/jira/test-cloud-id/rest/api/3/myself",path);
        String expected="Basic "+Base64.getEncoder().encodeToString("user@example.com:gateway-test-token".getBytes(StandardCharsets.UTF_8));
        assertTrue(expected.equals(authorization),"선택한 모의 토큰을 사용해야 합니다.");
    }
}
