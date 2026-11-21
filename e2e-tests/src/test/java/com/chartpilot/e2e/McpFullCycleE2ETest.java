package com.chartpilot.e2e;

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

/**
 * E2E-сценарий 2 (acceptance scenarios): полный цикл MCP -> браузер -> AG-UI.
 * createSession -> Playwright открывает ссылку -> CONNECTED -> getDashboardContext ->
 * requestChart -> панель чата открылась, промпт и ответ агента видны в DOM, ошибок консоли нет.
 */
class McpFullCycleE2ETest {

    private static final String PROMPT = "Построй столбчатый график суммы заказов по категориям из индекса pg-orders";

    @Test
    void fullCycleMcpBrowserAgUi() throws Exception {
        try (McpSyncClient client = E2eSupport.connectMcp()) {
            JsonNode created = E2eSupport.callTool(client, "createSession", Map.of());
            String sessionId = created.path("sessionId").asText();
            String url = created.path("url").asText();
            assertThat(sessionId).isNotBlank();
            assertThat(url).contains("sessionId=" + sessionId);

            List<String> consoleErrors = new CopyOnWriteArrayList<>();
            List<String> pageErrors = new CopyOnWriteArrayList<>();

            try (Playwright playwright = Playwright.create()) {
                Browser browser = playwright.chromium()
                        .launch(new BrowserType.LaunchOptions()
                                .setHeadless(true)
                                // системный HTTP_PROXY не должен ломать доступ к localhost-стенду
                                .setArgs(java.util.List.of("--no-proxy-server", "--proxy-bypass-list=*")));
                BrowserContext context = browser.newContext();
                Page page = context.newPage();
                List<String> allConsole = new java.util.concurrent.CopyOnWriteArrayList<>();
                List<String> chatNetwork = new java.util.concurrent.CopyOnWriteArrayList<>();
                page.onConsoleMessage(message -> {
                    allConsole.add(message.type() + ": " + message.text());
                    if ("error".equals(message.type())) {
                        consoleErrors.add(message.text());
                    }
                });
                page.onPageError(error -> pageErrors.add(error));
                page.onRequestFailed(request -> chatNetwork.add("FAILED " + request.url() + " " + request.failure()));
                page.onResponse(response -> {
                    if (response.url().contains("/api/chat") || response.url().contains("/agui")) {
                        chatNetwork.add(response.status() + " " + response.url());
                    }
                });

                page.navigate(url);
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);

                try {
                    await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                        assertThat(E2eSupport.callTool(client, "getSessionStatus", Map.of("sessionId", sessionId))
                                .path("status").asText()).isEqualTo("CONNECTED"));
                } catch (RuntimeException e) {
                    page.screenshot(new Page.ScreenshotOptions()
                            .setPath(java.nio.file.Path.of("/tmp/e2e-connect-failure.png")).setFullPage(true));
                    throw new AssertionError("Browser did not connect; page=" + page.url()
                            + ", consoleErrors=" + consoleErrors + ", pageErrors=" + pageErrors, e);
                }

                JsonNode dashboardContext = E2eSupport.callTool(client, "getDashboardContext", Map.of("sessionId", sessionId));
                assertThat(dashboardContext.path("status").asText()).isEqualTo("ok");
                assertThat(dashboardContext.path("details").path("url").asText()).contains("/app/home");

                boolean realLlm = System.getProperty("realLlm") != null;
                String baselineBody = page.evaluate("() => document.body.innerText").toString();
                JsonNode chart = E2eSupport.callTool(client, "requestChart", Map.of("sessionId", sessionId, "prompt", PROMPT));
                assertThat(chart.path("status").asText())
                        .as("requestChart result: %s", chart)
                        .isEqualTo("ok");

                // Панель чата AG-UI открылась: поле ввода чата видимо.
                page.locator("textarea[placeholder='How can I help you today?']")
                        .waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                                .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
                                .setTimeout(30000));

                // Промпт отобразился в чате.
                page.getByText("Построй столбчатый график суммы заказов")
                        .first()
                        .waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                                .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
                                .setTimeout(30000));

                // Ответ агента: mock — точный текст; realLlm — любой новый контент + разблокированный ввод.
                List<String> timeline = new java.util.concurrent.CopyOnWriteArrayList<>();
                boolean answered = false;
                for (int i = 0; i < (realLlm ? 60 : 30); i++) {
                    String body = page.evaluate("() => document.body.innerText").toString();
                    boolean textarea = page.locator("textarea[placeholder='How can I help you today?']").isVisible();
                    timeline.add("t=" + (i * 2) + "s textarea=" + textarea
                            + " net=" + chatNetwork.size() + " text=" + body.replaceAll("\\s+", " ").substring(0, Math.min(300, body.length())));
                    if (realLlm) {
                        // Ошибки раннера (RUN_ERROR/403 и т.п.) не считаются ответом агента
                        boolean hasError = body.contains("Access denied") || body.contains("run failed")
                                || body.matches("(?s).*HTTP [45]\\d\\d.*");
                        if (textarea && !hasError && body.length() > baselineBody.length() + 20) {
                            answered = true;
                            break;
                        }
                    } else if (body.contains("успешно построен")) {
                        answered = true;
                        break;
                    }
                    page.waitForTimeout(2000);
                }
                if (!answered) {
                    page.screenshot(new Page.ScreenshotOptions()
                            .setPath(java.nio.file.Path.of("/tmp/e2e-chat-failure.png")).setFullPage(true));
                    throw new AssertionError("Agent answer not visible; timeline=" + timeline
                            + ", chatNetwork=" + chatNetwork + ", console=" + allConsole + ", pageErrors=" + pageErrors);
                }

                // График в DOM: inline-рендер (ECharts canvas для категориальных данных
                // или встроенный timeseries-график) — не только текст/ссылка.
                try {
                    page.locator("canvas").first()
                            .waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                                    .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
                                    .setTimeout(30000));
                } catch (RuntimeException e) {
                    page.screenshot(new Page.ScreenshotOptions()
                            .setPath(java.nio.file.Path.of("/tmp/e2e-chart-failure.png")).setFullPage(true));
                    throw new AssertionError("No chart canvas visible in chat; timeline=" + timeline
                            + ", console=" + allConsole + ", pageErrors=" + pageErrors, e);
                }
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(java.nio.file.Path.of("/tmp/e2e-chat-chart.png")).setFullPage(true));

                browser.close();
            }

            List<String> realConsoleErrors = consoleErrors.stream()
                    .filter(text -> !text.contains("favicon"))
                    .filter(text -> !text.contains("React DevTools"))
                    .filter(text -> !text.contains("Download the Apollo DevTools"))
                    .toList();
            assertThat(pageErrors).as("page errors: %s", pageErrors).isEmpty();
            assertThat(realConsoleErrors).as("console errors: %s", realConsoleErrors).isEmpty();
        }
    }
}