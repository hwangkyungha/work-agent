package local.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import com.sun.jna.platform.win32.Crypt32Util;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

final class GroupwareBrowser implements GroupwareBooking.Gateway {
    private final Playwright playwright;
    private final Browser browser;
    private final Page page;
    private final GroupwareSettings settings;
    private JsonNode schedules;

    GroupwareBrowser(GroupwareSettings settings, Page page) {
        this.settings = settings; this.page = page; playwright = null; browser = null;
    }

    GroupwareBrowser(GroupwareSettings settings) throws Exception {
        this.settings = settings;
        if (!Files.exists(settings.cookies())) throw new IllegalStateException("그룹웨어 로그인이 필요합니다. work-agent 폴더에서 ./groupware.ps1 login을 실행하세요.");
        List<Cookie> cookies;
        try { cookies = cookies(Json.read(new String(Crypt32Util.cryptUnprotectData(Files.readAllBytes(settings.cookies())), StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("저장된 그룹웨어 세션을 읽지 못했습니다. 이 Windows 계정에서 ./groupware.ps1 login을 실행하세요."); }
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        Browser opened = null;
        String stage = "브라우저 실행";
        try {
            opened = playwright.chromium().launch();
            stage = "로그인 세션 적용";
            BrowserContext context = opened.newContext(new Browser.NewContextOptions().setTimezoneId("Asia/Seoul"));
            context.addCookies(cookies);
            page = context.newPage();
            page.setDefaultTimeout(15000);
            stage = "그룹웨어 예약 화면 열기";
            page.navigate(settings.baseUrl() + "/app/asset", new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            if (page.url().contains("/login")) throw new IllegalStateException("그룹웨어 세션이 만료됐습니다. ./groupware.ps1 login으로 다시 로그인하세요.");
            stage = "회의실 목록 확인";
            page.locator(".lnb li[data-id]").first().waitFor();
            browser = opened;
        } catch (RuntimeException e) {
            if (opened != null) opened.close(); playwright.close();
            if (e instanceof IllegalStateException) throw e;
            throw new IllegalStateException(stage + " 단계에서 연결하지 못했습니다 (" + e.getClass().getSimpleName() + "). 세션 만료 시 ./groupware.ps1 login, 브라우저 미설치 시 ./groupware.ps1 setup을 실행하세요.");
        }
    }
    public JsonNode schedule(String category) {
        if (schedules == null) {
            Object raw = page.evaluate("async()=>{const r=await fetch('/api/asset/reservation');if(!r.ok)throw new Error('예약 설정 조회 실패');return await r.json()}");
            schedules = Json.MAPPER.valueToTree(raw).path("data");
            if (!schedules.isArray()) throw new IllegalStateException("그룹웨어의 예약 설정을 확인하지 못했습니다.");
        }
        for (JsonNode item : schedules) if (Json.text(item, "id").equals(category)) return item.path("availabilityDate");
        throw new IllegalStateException("해당 회의실의 예약 설정을 찾지 못했습니다.");
    }
    public List<JsonNode> daily(String category, LocalDate date) {
        Object raw = page.evaluate("""
            async ({category,date}) => {
                const response = await fetch('/api/asset/' + encodeURIComponent(category) + '/items/daily?fromDate=' + encodeURIComponent(date + 'T00:00:00.000+09:00'));
                if (!response.ok) throw new Error('예약 현황 조회 실패: ' + response.status);
                return await response.json();
            }
            """, Map.of("category", category, "date", date.toString()));
        JsonNode data = Json.MAPPER.valueToTree(raw).path("data");
        if (!data.isArray()) throw new IllegalStateException("예약 현황 응답을 확인하지 못했습니다. 예약을 실행하지 않았습니다.");
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode item : data) {
            String start = Json.text(item, "startTime"), end = Json.text(item, "endTime");
            if (start.isBlank() || end.isBlank()) throw new IllegalStateException("예약 현황에 시작·종료 시각이 없습니다.");
            if (KoreaTime.parse(start).isBefore(date.plusDays(1).atStartOfDay(KoreaTime.ZONE).toInstant()) && KoreaTime.parse(end).isAfter(date.atStartOfDay(KoreaTime.ZONE).toInstant())) result.add(item);
        }
        return result;
    }
    public String reserve(JsonNode room, GroupwareBooking.Request request) {
        try { return reserveForm(room, request); }
        catch (PlaywrightException e) { throw new IllegalStateException("그룹웨어 예약 입력 화면을 확인하지 못했습니다. 예약을 제출하지 않았습니다. 그룹웨어 화면과 로그인 상태를 확인하세요."); }
    }
    private String reserveForm(JsonNode room, GroupwareBooking.Request request) {
        String category = Json.required(room, "category_id");
        if (!GroupwareBooking.allowedDay(schedule(category), request.date())) throw new IllegalArgumentException(request.date() + "은 그룹웨어에서 예약할 수 없는 요일입니다. 다른 날짜로 요청해 주세요.");
        Set<String> previousIds = new HashSet<>(); daily(category, request.date()).forEach(n -> previousIds.add(Json.text(n, "id")));
        page.locator(".lnb li[data-id=\"" + category + "\"] a").click();
        page.locator("table.time_table_bg").waitFor();
        page.locator("#datePickerBtn").click();
        page.locator(".ui-datepicker-year").selectOption("" + request.date().getYear());
        page.locator(".ui-datepicker-month").selectOption("" + (request.date().getMonthValue() - 1));
        page.locator(".ui-datepicker-calendar a").filter(new Locator.FilterOptions().setHasText(java.util.regex.Pattern.compile("^" + request.date().getDayOfMonth() + "$"))).click();
        page.locator("#ui-datepicker-div").waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
        if (!page.locator("#datePickerBtn").innerText().contains(request.date().toString())) throw new IllegalStateException("예약 날짜 이동을 확인하지 못했습니다.");
        Locator row = page.locator("table.time_table_bg tr").filter(new Locator.FilterOptions().setHas(page.locator("span.name").filter(new Locator.FilterOptions().setHasText(Json.required(room, "name")))));
        if (row.count() != 1) throw new IllegalStateException("회의실 행을 확인하지 못했습니다.");
        row.locator("td").nth(request.start().getHour() * 2 + 1 + request.start().getMinute() / 30).click(new Locator.ClickOptions().setForce(true));
        page.locator(".go_popup.layer_normal #startTime").waitFor();
        page.evaluate("""
            ({date,start,end,purpose}) => {
                function set(el, value) { if (!el) throw new Error('예약 입력란 없음'); el.value=value; el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})); }
                set(document.getElementById('startDate'),date); set(document.getElementById('endDate'),date);
                set(document.getElementById('startTime'),start); set(document.getElementById('endTime'),end);
                let purposeSet=false, attendeesSet=false;
                for (const tr of document.querySelectorAll('.go_popup.layer_normal tr')) {
                    const label=tr.querySelector('th')?.innerText || ''; const input=tr.querySelector('input');
                    if (label.includes('이용목적')) {set(input,purpose); purposeSet=true;}
                    if (label.includes('참석인원')) {set(input,'-'); attendeesSet=true;}
                }
                if (!purposeSet || !attendeesSet) throw new Error('예약 입력란 확인 실패');
            }
            """, Map.of("date", request.date().toString(), "start", request.start().toString(), "end", request.end().toString(), "purpose", request.purpose()));
        page.onDialog(Dialog::dismiss);
        try {
        page.locator(".go_popup.layer_normal a.btn_major_s").filter(new Locator.FilterOptions().setHasText(java.util.regex.Pattern.compile("^확인$"))).click();
        page.locator(".go_popup.layer_normal").waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
        List<JsonNode> saved = daily(category, request.date()).stream().filter(n -> !previousIds.contains(Json.text(n, "id")))
                .filter(n -> Json.text(n, "itemName").equals(Json.text(room, "name")))
                .filter(n -> KoreaTime.parse(Json.text(n, "startTime")).equals(request.date().atTime(request.start()).atZone(KoreaTime.ZONE).toInstant()) && KoreaTime.parse(Json.text(n, "endTime")).equals(request.date().atTime(request.end()).atZone(KoreaTime.ZONE).toInstant())).toList();
        if (saved.size() != 1) throw new IllegalStateException("저장된 예약을 다시 조회해 확인하지 못했습니다.");
        return Json.required(saved.getFirst(), "id");
        } catch (RuntimeException e) { throw new GroupwareBooking.Unconfirmed(e); }
    }
    public void cancel(JsonNode room, JsonNode reservation) {
        String category = Json.required(room, "category_id"), id = Json.required(reservation, "id");
        page.navigate(settings.baseUrl() + "/app/asset/" + category + "/item/" + Json.required(reservation, "itemId") + "/status/reservation/" + id, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.locator("#reservationCancel").click();
        page.locator(".btn_layer_wrap .btn_major_s:visible, .layer_confim .btn_major_s:visible").first().click(new Locator.ClickOptions().setForce(true));
        page.locator(".layer_confim").waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
        LocalDate date = KoreaTime.dateOf(Json.required(reservation, "startTime"));
        if (daily(category, date).stream().anyMatch(n -> id.equals(Json.text(n, "id")))) throw new IllegalStateException("취소 결과를 확인하지 못했습니다. 그룹웨어 예약 내역을 확인하세요.");
    }
    static List<Cookie> cookies(JsonNode raw) {
        List<Cookie> result = new ArrayList<>();
        if (!raw.isArray()) throw new IllegalArgumentException("잘못된 세션 형식입니다.");
        for (JsonNode n : raw) {
            Cookie cookie = new Cookie(Json.required(n, "name"), n.path("value").asText());
            cookie.setDomain(Json.required(n, "domain")).setPath(n.path("path").asText("/")).setHttpOnly(n.path("httpOnly").asBoolean()).setSecure(n.path("secure").asBoolean());
            if (n.has("expires")) cookie.setExpires(n.path("expires").asDouble());
            if (n.has("sameSite")) cookie.setSameSite(SameSiteAttribute.valueOf(n.path("sameSite").asText().toUpperCase(Locale.ROOT)));
            result.add(cookie);
        }
        return result;
    }
    static void login(GroupwareSettings settings) throws Exception {
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"))); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false))) {
            BrowserContext context = browser.newContext(new Browser.NewContextOptions().setTimezoneId("Asia/Seoul"));
            Page page = context.newPage();
            page.navigate(settings.baseUrl() + "/login", new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            System.out.println("브라우저에서 그룹웨어에 로그인하세요. 비밀번호와 보안문자는 저장하지 않습니다.");
            page.waitForURL(settings.baseUrl() + "/app/**", new Page.WaitForURLOptions().setTimeout(300000));
            try { page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(15000)); } catch (TimeoutError ignored) { }
            byte[] raw = Json.write(savedCookies(context.cookies())).getBytes(StandardCharsets.UTF_8);
            GroupwareSettings.atomicWrite(settings.cookies(), Crypt32Util.cryptProtectData(raw));
            System.out.println("그룹웨어 로그인 세션을 공통 저장소에 암호화해 저장했습니다.");
        }
    }
    static JsonNode savedCookies(List<Cookie> cookies) {
        JsonNode result = Json.MAPPER.valueToTree(cookies);
        for (JsonNode cookie : result) {
            var fields = cookie.fields();
            while (fields.hasNext()) if (fields.next().getValue().isNull()) fields.remove();
            String sameSite = cookie.path("sameSite").asText();
            if (!sameSite.isBlank()) ((com.fasterxml.jackson.databind.node.ObjectNode) cookie).put("sameSite", sameSite.substring(0, 1).toUpperCase(Locale.ROOT) + sameSite.substring(1).toLowerCase(Locale.ROOT));
        }
        return result;
    }
    public void close() { try { if (browser != null) browser.close(); } finally { if (playwright != null) playwright.close(); } }
}
