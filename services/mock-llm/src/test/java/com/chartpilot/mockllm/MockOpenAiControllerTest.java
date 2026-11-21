package com.chartpilot.mockllm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MockOpenAiControllerTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Test
    void firstStepRequestsSearchIndexTool() throws Exception {
        HttpResponse<String> response = post("""
                {"model":"mock-gpt-4o","messages":[{"role":"user","content":"построй график"}]}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"name\":\"SearchIndexTool\"")
                .contains("pg-orders")
                .contains("date_histogram")
                .contains("\"finish_reason\":\"tool_calls\"");
    }

    @Test
    void categoricalQuestionRequestsTermsAggregation() throws Exception {
        HttpResponse<String> response = post("""
                {"model":"mock-gpt-4o","messages":[{"role":"user","content":"Построй график суммы заказов по категориям"}]}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"name\":\"SearchIndexTool\"")
                .contains("terms")
                .contains("category")
                .doesNotContain("date_histogram");
    }

    @Test
    void secondStepReturnsFinalAnswerAfterToolResult() throws Exception {
        HttpResponse<String> response = post("""
                {"model":"mock-gpt-4o","messages":[
                  {"role":"user","content":"построй график"},
                  {"role":"tool","tool_call_id":"call_mock_search_1","content":"{\\"hits\\":[]}"}
                ]}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("успешно построен")
                .contains("\"finish_reason\":\"stop\"");
    }

    @Test
    void streamingModeEmitsSseChunksAndDone() throws Exception {
        HttpResponse<String> response = post("""
                {"model":"mock-gpt-4o","stream":true,"messages":[
                  {"role":"user","content":"построй график"},
                  {"role":"tool","tool_call_id":"call_mock_search_1","content":"{\\"hits\\":[]}"}
                ]}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("text/event-stream");
        assertThat(response.body())
                .contains("data: ")
                .contains("chat.completion.chunk")
                .contains("data: [DONE]");
    }

    private HttpResponse<String> post(String body) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}