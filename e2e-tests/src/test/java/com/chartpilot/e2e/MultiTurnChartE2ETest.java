package com.chartpilot.e2e;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Multi-turn UX (CONTRIBUTING.md §2.3) + типы inline-графиков pie/line:
 * две команды requestChart в одной сессии/треде — история чата сохраняется,
 * тип графика проверяется в DOM по data-chart-type рендера chart_pilot_chart.
 */
class MultiTurnChartE2ETest {

    private static final String PIE_PROMPT =
            "Построй круговую диаграмму суммы заказов по регионам из индекса pg-orders";
    private static final String LINE_PROMPT =
            "А теперь сгруппируй по категориям и переключи на линейный график";

    @Test
    void multiTurnPieThenLineInSameSession() throws Exception {
        boolean realLlm = System.getProperty("realLlm") != null;
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

                // Ход 1: pie по регионам
                String baseline1 = page.evaluate("() => document.body.innerText").toString();
                JsonNode chart1 = E2eSupport.callTool(client, "requestChart",
                        Map.of("sessionId", sessionId, "prompt", PIE_PROMPT));
                assertThat(chart1.path("status").asText()).as("requestChart#1: %s", chart1).isEqualTo("ok");
                waitForAnswer(page, realLlm, baseline1);
                // continuation-run (возврат результата chart-action клиентом) ещё может идти:
                // отправка второго промпта во время активного run теряется chat-клиентом
                page.waitForTimeout(4000);
                page.locator("[data-chart-type='pie']").first()
                        .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(30000));
                page.locator("[data-chart-type='pie']").first().scrollIntoViewIfNeeded();
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(java.nio.file.Path.of("/tmp/e2e-multiturn-pie.png")).setFullPage(true));

                // Ход 2: line по категориям в ТОМ ЖЕ треде — история первого хода сохраняется
                String baseline2 = page.evaluate("() => document.body.innerText").toString();
                assertThat(baseline2).contains("круговую диаграмму");
                JsonNode chart2 = E2eSupport.callTool(client, "requestChart",
                        Map.of("sessionId", sessionId, "prompt", LINE_PROMPT));
                assertThat(chart2.path("status").asText()).as("requestChart#2: %s", chart2).isEqualTo("ok");
                waitForAnswer(page, realLlm, baseline2);
                page.locator("[data-chart-type='line']").first()
                        .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(30000));
                page.locator("[data-chart-type='line']").first().scrollIntoViewIfNeeded();
                String body = page.evaluate("() => document.body.innerText").toString();
                assertThat(body).contains("круговую диаграмму");
                assertThat(body).contains("переключи на линейный");
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(java.nio.file.Path.of("/tmp/e2e-multiturn-line.png")).setFullPage(true));

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

    private void waitForAnswer(Page page, boolean realLlm, String baseline) {
        // mock: финальный текст одинаков во всех ходах — ждём ПРИРОСТ вхождений в истории
        int baselineAnswers = countOccurrences(baseline, "успешно построен");
        for (int i = 0; i < (realLlm ? 60 : 30); i++) {
            String body = page.evaluate("() => document.body.innerText").toString();
            boolean textarea = page.locator("textarea[placeholder='How can I help you today?']").isVisible();
            if (realLlm) {
                boolean hasError = body.contains("Access denied") || body.contains("run failed")
                        || body.matches("(?s).*HTTP [45]\\d\\d.*");
                if (textarea && !hasError && body.length() > baseline.length() + 20) {
                    return;
                }
            } else if (countOccurrences(body, "успешно построен") > baselineAnswers) {
                return;
            }
            page.waitForTimeout(2000);
        }
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(java.nio.file.Path.of("/tmp/e2e-multiturn-failure.png")).setFullPage(true));
        throw new AssertionError("Agent answer not visible; baseline length=" + baseline.length());
    }

    private static int countOccurrences(String text, String marker) {
        int count = 0;
        for (int i = text.indexOf(marker); i >= 0; i = text.indexOf(marker, i + marker.length())) {
            count++;
        }
        return count;
    }
}