package local.work;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionAndTokensTest {
    @TempDir Path temporary;
    Store store; Path file;
    @BeforeEach void open() throws Exception {file=temporary.resolve("data.db");store=new Store(file);}
    @AfterEach void close() throws Exception {store.close();}
    Config config(Map<String,String> values) throws Exception {return new Config(temporary.resolve("none"),values);}
    void tick(String instant, String hour, Scheduler.SessionStart start) throws Exception {
        Config config=config(Map.of("AUTO_SYNC","false","CLAUDE_SESSION_TIME",hour));
        WorkService service=new WorkService(store,new KoreaTime(Clock.fixed(Instant.parse(instant),ZoneOffset.UTC)),new JiraClient(config));
        Agent agent=new Agent(service,new Reports(service),p->Json.object(),k->Json.object(),"호박","호박아");
        try(SlackConnection slack=new SlackConnection(config,store,agent);Scheduler scheduler=new Scheduler(service,new Reports(service),slack,config,k->Json.object(),start)){scheduler.tick();}
    }
    @Test void sessionRunsOnWeekendsOncePerKoreaDayIncludingAfterRestart() throws Exception {
        AtomicInteger calls=new AtomicInteger();tick("2026-11-07T20:59:59Z","06:00",calls::incrementAndGet);assertEquals(0,calls.get());
        tick("2026-11-07T21:00:00Z","06:00",calls::incrementAndGet);tick("2026-11-07T21:30:00Z","06:00",calls::incrementAndGet);assertEquals(1,calls.get());
        assertEquals("complete",Json.text(store.get("scheduled","claude-session:2026-11-08"),"status"));
    }
    @Test void failureIsNotMistakenForSuccessOrRetriedEveryTickAndLateStartupSkipsOldRequest() throws Exception {
        AtomicInteger calls=new AtomicInteger();Scheduler.SessionStart fail=()->{calls.incrementAndGet();throw new IllegalStateException("synthetic failure");};
        tick("2026-11-08T21:00:00Z","06:00",fail);tick("2026-11-08T21:01:00Z","06:00",fail);assertEquals(1,calls.get());assertEquals("failed",Json.text(store.get("scheduled","claude-session:2026-11-09"),"status"));
        tick("2026-11-09T22:00:00Z","06:00",fail);tick("2026-11-10T21:00:00Z","",fail);assertEquals(1,calls.get());
    }
    Config rotating() throws Exception {return config(Map.of("SLACK_APP_TOKEN","xoxe.xapp-test-old","SLACK_REFRESH_TOKEN","refresh-old","SLACK_CLIENT_ID","test-id","SLACK_CLIENT_SECRET","test-secret"));}
    SlackTokens.Refresh response(AtomicInteger calls){return body->{int n=calls.incrementAndGet();return Json.obj("token_type","app_level","access_token","xoxe.xapp-test-"+n,"refresh_token","refresh-"+n,"expires_in",43200);};}
    @Test void concurrentRefreshAndRestartKeepTheLatestTokenPair() throws Exception {
        AtomicInteger calls=new AtomicInteger();Config config=rotating();SlackTokens tokens=new SlackTokens(config,store,response(calls));Instant now=Instant.parse("2026-11-05T00:00:00Z");
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var futures=new ArrayList<Future<String>>();for(int i=0;i<8;i++)futures.add(pool.submit(()->tokens.access(now)));for(var result:futures)assertEquals("xoxe.xapp-test-1",result.get());}
        assertEquals(1,calls.get());SlackTokens restarted=new SlackTokens(config,store,body->{assertEquals("refresh-1",Json.text(body,"refresh_token"));return response(calls).call(body);});
        assertEquals("xoxe.xapp-test-1",restarted.access(now.plusSeconds(36000)));assertEquals(1,calls.get());assertEquals("xoxe.xapp-test-2",restarted.access(now.plusSeconds(39600)));assertEquals(2,calls.get());
    }
    @Test void diskFailureRetriesSavingTheReturnedPairWithoutRedeemingAgain() throws Exception {
        AtomicInteger calls=new AtomicInteger();SlackTokens tokens=new SlackTokens(rotating(),store,response(calls));Instant now=Instant.parse("2026-11-05T00:00:00Z");
        try(Connection connection=DriverManager.getConnection("jdbc:sqlite:"+file);Statement sql=connection.createStatement()){
            sql.execute("CREATE TRIGGER fail_secret BEFORE INSERT ON records WHEN NEW.kind='secret' BEGIN SELECT RAISE(ABORT,'test disk failure'); END");
            assertThrows(IllegalStateException.class,()->tokens.access(now));assertEquals(1,calls.get());sql.execute("DROP TRIGGER fail_secret");
            assertEquals("xoxe.xapp-test-1",tokens.access(now));assertEquals(1,calls.get());
        }
    }
    @Test void permanentTokensNeedNoRefreshAndNetworkFailureChangesNoStoredPair() throws Exception {
        AtomicInteger calls=new AtomicInteger();assertEquals("xapp-test",new SlackTokens(config(Map.of("SLACK_APP_TOKEN","xapp-test")),store,response(calls)).access(Instant.now()));assertEquals(0,calls.get());
        SlackTokens broken=new SlackTokens(rotating(),store,b->{throw new java.io.IOException("offline");});assertThrows(java.io.IOException.class,()->broken.access(Instant.now()));assertNull(store.get("secret","slack-app-token"));
    }
    @Test void changingConfiguredTokenDoesNotReuseAnotherAppsSavedCredentials() throws Exception {
        AtomicInteger calls=new AtomicInteger();new SlackTokens(rotating(),store,response(calls)).access(Instant.now());
        assertEquals("xapp-another",new SlackTokens(config(Map.of("SLACK_APP_TOKEN","xapp-another")),store,response(calls)).access(Instant.now()));assertEquals(1,calls.get());
    }
}
