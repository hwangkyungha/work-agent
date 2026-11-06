package local.work;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "groupware.browser.test", matches = "true")
class GroupwareBrowserTest {
    @TempDir Path temporary;
    @ParameterizedTest @ValueSource(ints = {0, 6})
    void reservesAndCancelsInFixtureWithoutContactingGroupware(int headcount) throws Exception {
        Path file = temporary.resolve("settings.json");
        Files.writeString(file, "{\"baseUrl\":\"https://groupware.example\"}");
        GroupwareSettings settings = new GroupwareSettings(new Config(temporary.resolve("none"), Map.of("GROUPWARE_CONFIG", file.toString())));
        List<ObjectNode> saved = new ArrayList<>(); List<String> purposes = new ArrayList<>();
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"))); Browser browser = playwright.chromium().launch()) {
            Page page = browser.newPage();
            page.setDefaultTimeout(2000);
            page.route("**/*", route -> {
                assertTrue(route.request().url().startsWith("https://groupware.example/"));
                String url = route.request().url();
                if (url.contains("/api/asset/")) {
                    if (url.endsWith("/api/asset/reservation")) {
                        route.fulfill(new Route.FulfillOptions().setContentType("application/json").setBody("{\"data\":[{\"id\":27,\"availabilityDate\":{\"ableDays\":\"1111111\"}}]}")); return;
                    }
                    if (route.request().method().equals("POST")) {
                        var request = Json.read(route.request().postData()); purposes.add(Json.text(request, "purpose"));
                        assertEquals("-", Json.text(request, "attendees"));
                        saved.add(Json.obj("id", "r1", "itemId", "item1", "itemName", "회의실 11-4(8인 / TV)", "startTime", "2026-11-07T10:00:00+09:00", "endTime", "2026-11-07T11:00:00+09:00"));
                    }
                    if (route.request().method().equals("DELETE")) saved.clear();
                    route.fulfill(new Route.FulfillOptions().setContentType("application/json").setBody(Json.write(Json.obj("data", saved))));
                } else route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(HTML));
            });
            page.navigate(settings.baseUrl() + "/app/asset");
            GroupwareBrowser gateway = new GroupwareBrowser(settings, page);
            String purpose = "O'Reilly \"리뷰\" </script><script>window.hacked=true</script>";
            var request = new GroupwareBooking.Request(LocalDate.of(2026, 11, 7), java.time.LocalTime.of(10, 0), java.time.LocalTime.of(11, 0), headcount, purpose, "11-4");
            var room = Json.obj("short", "11-4", "name", "회의실 11-4(8인 / TV)", "category_id", "27", "capacity", 8);
            assertEquals("r1", gateway.reserve(room, request));
            assertEquals(List.of(purpose), purposes); assertNull(page.evaluate("window.hacked"));
            var reservation = gateway.daily("27", request.date()).getFirst(); gateway.cancel(room, reservation);
            assertTrue(gateway.daily("27", request.date()).isEmpty());
        }
    }
    static final String HTML = """
        <!doctype html><html><meta charset="utf-8"><body>
        <ul class="lnb"><li data-id="27"><a href="#">11층</a></li></ul>
        <button id="datePickerBtn" onclick="document.getElementById('ui-datepicker-div').style.display='block'">2026-11-06</button>
        <div id="ui-datepicker-div" style="display:none"><select class="ui-datepicker-year"><option value="2026">2026</option></select><select class="ui-datepicker-month"><option value="10">11</option></select><table class="ui-datepicker-calendar"><tr><td><a href="#" onclick="document.getElementById('datePickerBtn').textContent='2026-11-07';document.getElementById('ui-datepicker-div').style.display='none';document.getElementById('scheduleOverlay').style.display='block'">7</a></td></tr></table></div>
        <div id="scheduleOverlay" class="_wrap_schedule_tiem" style="position:fixed;inset:0;z-index:999;display:none" onclick="this.style.display='none';document.querySelector('.go_popup').style.display='block'"></div>
        <table class="time_table_bg"><tr id="room"></tr></table>
        <div class="go_popup layer_normal" style="display:none"><input id="startDate"><input id="endDate"><input id="startTime"><input id="endTime"><table><tr><th>이용목적</th><td><input id="purpose"></td></tr><tr><th>참석인원</th><td><input id="attendees"></td></tr></table><a class="btn_major_s" href="#" onclick="save();return false">확인</a></div>
        <button id="reservationCancel" onclick="document.querySelector('.layer_confim').style.display='block'">취소</button>
        <div class="layer_confim" style="display:none"><div class="btn_layer_wrap"><a class="btn_major_s" href="#" onclick="cancelReservation();return false">확인</a></div></div>
        <script>
        for(let i=0;i<50;i++){const td=document.createElement('td');if(i===0)td.innerHTML='<span class="name">회의실 11-4(8인 / TV)</span>';td.onclick=()=>document.querySelector('.go_popup').style.display='block';document.getElementById('room').appendChild(td);}
        async function save(){await fetch('/api/asset/27/save',{method:'POST',body:JSON.stringify({purpose:document.getElementById('purpose').value,attendees:document.getElementById('attendees').value})});document.querySelector('.go_popup').style.display='none';}
        async function cancelReservation(){await fetch('/api/asset/27/cancel',{method:'DELETE'});document.querySelector('.layer_confim').style.display='none';}
        </script></body></html>
        """;
}
