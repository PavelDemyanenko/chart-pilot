package com.chartpilot.e2e;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Save-flow: явное намерение сохранить → CreateVisualizationTool →
 * публичная ссылка в ответе + сохранённый объект в OSD; на странице визуализации
 * getDashboardContext отдаёт активный временной диапазон (CONTRIBUTING.md §2.3).
 * Только real-LLM: mock-llm не моделирует намерение сохранить.
 */
class SaveVisualizationE2ETest {

    private static final String PROMPT =
            "Сохрани визуализацию: столбчатый график суммы заказов по категориям из индекса pg-orders";

    @Test
    void saveIntentCreatesVisualizationWithPublicUrlAndTimeRange() throws Exception {
        assumeTrue(System.getProperty("realLlm") != null, "save-flow покрыт только real-LLM стендом");
        try (McpSyncClient client = E2eSupport.connectMcp()) {
            JsonNode created = E2eSupport.callTool(client, "createSession", Map.of());
            String sessionId = created.path("sessionId").asText();
            String url = created.path("url").asText();

            List<String> consoleErrors = new CopyOnWriteArrayList<>();
            List<String> pageErrors = new CopyOnWriteArrayList<>();
            try (Playwright playwright = Playwright.create()) {
                Browser browser = playwright.chromium()
                        .launch(new BrowserType.LaunchOptions()
                                .setHeadless(true)
                                .setArgs(List.of("--no-proxy-server", "--proxy-bypass-list=*")));
                BrowserContext context = browser.newContext();
                Page page = context.newPage();
                page.onConsoleMessage(message -> {
                    if ("error".equals(message.type())) {
                        consoleErrors.add(message.text());
                    }
                });
                page.onPageError(pageErrors::add);

                page.navigate(url);
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                        assertThat(E2eSupport.callTool(client, "getSessionStatus", Map.of("sessionId", sessionId))
                                .path("status").asText()).isEqualTo("CONNECTED"));

                String baseline = page.evaluate("() => document.body.innerText").toString();
                JsonNode chart = E2eSupport.callTool(client, "requestChart",
                        Map.of("sessionId", sessionId, "prompt", PROMPT));
                assertThat(chart.path("status").asText()).as("requestChart: %s", chart).isEqualTo("ok");
                await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
                    String body = page.evaluate("() => document.body.innerText").toString();
                    boolean textarea = page.locator("textarea[placeholder='How can I help you today?']").isVisible();
                    assertThat(textarea).isTrue();
                    assertThat(body.length()).isGreaterThan(baseline.length() + 20);
                });

                // Публичная ссылка на сохранённую визуализацию в ответе чата
                String href = (String) page.evaluate("""
                        () => Array.from(document.querySelectorAll('a[href]'))
                            .map(a => a.getAttribute('href'))
                            .find(h => h && h.includes('/app/visualize#/edit/')) || null
                        """);
                assertThat(href).as("visualize link in chat answer").isNotNull();
                assertThat(href).startsWith(E2eSupport.OSD_URL);
                String id = href.substring(href.indexOf("/edit/") + "/edit/".length()).split("[?#]")[0];
                HttpResponse<String> saved = java.net.http.HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(E2eSupport.OSD_URL + "/api/saved_objects/visualization/" + id))
                                .header("osd-xsrf", "true").GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(saved.statusCode()).isEqualTo(200);
                page.locator("a[href*='/app/visualize#/edit/']").first().scrollIntoViewIfNeeded();
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(java.nio.file.Path.of("/tmp/e2e-save-flow-chat.png")).setFullPage(true));

                // На странице визуализации контекст отдаёт активный временной диапазон из _g
                page.navigate(href);
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
                    JsonNode ctx = E2eSupport.callTool(client, "getDashboardContext", Map.of("sessionId", sessionId));
                    assertThat(ctx.path("status").asText()).isEqualTo("ok");
                    assertThat(ctx.path("details").path("url").asText()).contains("/app/visualize");
                    assertThat(ctx.path("details").path("timeRange").path("from").asText()).isEqualTo("now-30d");
                    assertThat(ctx.path("details").path("timeRange").path("to").asText()).isEqualTo("now");
                });
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(java.nio.file.Path.of("/tmp/e2e-save-flow.png")).setFullPage(true));
                browser.close();
            }

            List<String> realConsoleErrors = consoleErrors.stream()
                    .filter(text -> !text.contains("favicon"))
                    .filter(text -> !text.contains("React DevTools"))
                    .filter(text -> !text.contains("Download the Apollo DevTools"))
                    .toList();
            // ace-worker core-плагина console не успевает загрузиться при старте OSD
            // в headless-браузере: шум, не связанный с ChartPilot (проверено вручную)
            List<String> realPageErrors = pageErrors.stream()
                    .filter(text -> !text.contains("json_worker.js"))
                    .filter(text -> !text.contains("acequire"))
                    .filter(text -> !text.contains("reading 'setValue'"))
                    .toList();
            assertThat(realPageErrors).as("page errors: %s", realPageErrors).isEmpty();
            assertThat(realConsoleErrors).as("console errors: %s", realConsoleErrors).isEmpty();
        }
    }
}