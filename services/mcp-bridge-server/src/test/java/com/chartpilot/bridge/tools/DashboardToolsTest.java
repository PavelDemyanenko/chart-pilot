package com.chartpilot.bridge.tools;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import com.chartpilot.bridge.config.BridgeProperties;
import com.chartpilot.bridge.session.DashboardSession;
import com.chartpilot.bridge.session.DashboardSessionRegistry;
import com.chartpilot.bridge.tools.ToolResponse.CommandOutcome;
import com.chartpilot.bridge.tools.ToolResponse.SessionCreated;
import com.chartpilot.bridge.tools.ToolResponse.SessionStatusView;
import com.chartpilot.bridge.ws.BrowserNotConnectedException;
import com.chartpilot.bridge.ws.CommandGateway;
import com.chartpilot.bridge.ws.CommandTimeoutException;
import com.chartpilot.bridge.ws.UnknownSessionException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardToolsTest {

    private final BridgeProperties properties = new BridgeProperties(
            "http://osd:5601", "ws://bridge:8080", Duration.ofHours(1),
            Duration.ofSeconds(15), Duration.ofSeconds(60), null, 10_000);
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final DashboardSessionRegistry registry =
            new DashboardSessionRegistry(properties, Clock.systemUTC(), new SimpleMeterRegistry());

    @Mock
    private CommandGateway gateway;

    private DashboardTools tools;

    @BeforeEach
    void setUp() {
        tools = new DashboardTools(registry, gateway, properties, mapper);
    }

    @Test
    void createSessionReturnsClickableUrlAndWaitingStatus() {
        SessionCreated result = (SessionCreated) tools.createSession();

        assertThat(result.sessionId()).hasSize(22);
        assertThat(result.status()).isEqualTo("WAITING_FOR_BROWSER");
        assertThat(result.url()).startsWith("http://osd:5601/app/home?sessionId=" + result.sessionId());
        var params = UriComponentsBuilder.fromUriString(result.url()).build().getQueryParams();
        assertThat(params.getFirst("bridgeUrl")).isEqualTo("ws://bridge:8080");
    }

    @Test
    void getSessionStatusReportsNotFoundForUnknownId() {
        assertThat(((SessionStatusView) tools.getSessionStatus("deadbeef")).status()).isEqualTo("NOT_FOUND");
    }

    @Test
    void requestChartWithoutBrowserReturnsHelpfulHintWithUrl() throws Exception {
        String sessionId = ((SessionCreated) tools.createSession()).sessionId();
        when(gateway.send(eq(sessionId), eq("RUN_AG_UI_PROMPT"), any(), any()))
                .thenThrow(new BrowserNotConnectedException(sessionId));

        CommandOutcome result = (CommandOutcome) tools.requestChart(sessionId, "построй график");

        assertThat(result.status()).isEqualTo("error");
        assertThat(result.code()).isEqualTo("BROWSER_NOT_CONNECTED");
        assertThat(result.sessionStatus()).isEqualTo("WAITING_FOR_BROWSER");
        assertThat(result.message())
                .contains("Браузер ещё не подключён")
                .contains("sessionId=" + sessionId);
    }

    @Test
    void requestChartWithUnknownSessionReturnsStructuredError() throws Exception {
        when(gateway.send(eq("deadbeef"), eq("RUN_AG_UI_PROMPT"), any(), any()))
                .thenThrow(new UnknownSessionException("deadbeef"));

        CommandOutcome result = (CommandOutcome) tools.requestChart("deadbeef", "построй график");

        assertThat(result.status()).isEqualTo("error");
        assertThat(result.code()).isEqualTo("NOT_FOUND");
        assertThat(result.message()).contains("createSession()");
    }

    @Test
    void requestChartSuccessReturnsBrowserResult() throws Exception {
        DashboardSession session = registry.create();
        var browserResult = mapper.readTree("""
                {"status":"ok","message":"График построен","details":{"chart":"bar"}}
                """);
        when(gateway.send(eq(session.id()), eq("RUN_AG_UI_PROMPT"), any(), eq(Duration.ofSeconds(60))))
                .thenReturn(browserResult);

        CommandOutcome result = (CommandOutcome) tools.requestChart(session.id(), "сумма заказов по категориям");

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.message()).isEqualTo("График построен");
        assertThat(result.sessionId()).isEqualTo(session.id());
        assertThat(result.details()).isEqualTo(Map.of("chart", "bar"));
    }

    @Test
    void requestChartRejectsEmptyAndOversizedPrompt() {
        String sessionId = ((SessionCreated) tools.createSession()).sessionId();

        CommandOutcome empty = (CommandOutcome) tools.requestChart(sessionId, "  ");
        assertThat(empty.code()).isEqualTo("INVALID_ARGUMENT");

        CommandOutcome oversized = (CommandOutcome) tools.requestChart(sessionId, "x".repeat(4001));
        assertThat(oversized.code()).isEqualTo("INVALID_ARGUMENT");
        assertThat(oversized.message()).contains("4000");
    }

    @Test
    void getDashboardContextTimeoutIsReportedAsError() throws Exception {
        DashboardSession session = registry.create();
        when(gateway.send(anyString(), eq("GET_CONTEXT"), any(), any()))
                .thenThrow(new CommandTimeoutException("GET_CONTEXT", Duration.ofSeconds(15)));

        CommandOutcome result = (CommandOutcome) tools.getDashboardContext(session.id());

        assertThat(result.status()).isEqualTo("error");
        assertThat(result.message()).contains("GET_CONTEXT").contains("15");
    }
}