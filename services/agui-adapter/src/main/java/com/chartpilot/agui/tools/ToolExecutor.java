package com.chartpilot.agui.tools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.chartpilot.agui.config.AdapterProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Исполнение Backend tools прямыми запросами к OpenSearch (аналоги ListIndexTool /
 * IndexMappingTool / SearchIndexTool из ml-commons).
 */
@Component
public class ToolExecutor {

    private static final int MAX_RESULT_CHARS = 6000;

    private final AdapterProperties properties;
    private final ObjectMapper mapper;
    private final com.chartpilot.agui.llm.SchemaProvider schema;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ToolExecutor(AdapterProperties properties, ObjectMapper mapper,
            com.chartpilot.agui.llm.SchemaProvider schema) {
        this.properties = properties;
        this.mapper = mapper;
        this.schema = schema;
    }

    public static List<Map<String, Object>> toolDefinitions() {
        return List.of(
                function("ListIndexTool", "List OpenSearch indices",
                        Map.of("type", "object", "properties", Map.of(
                                "index", Map.of("type", "string", "description", "Optional index pattern")))),
                function("IndexMappingTool", "Get mapping of an OpenSearch index",
                        Map.of("type", "object",
                                "properties", Map.of("index", Map.of("type", "string")),
                                "required", List.of("index"))),
                function("SearchIndexTool", "Search an OpenSearch index with a query DSL body",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "index", Map.of("type", "string"),
                                        "query", Map.of("type", "object", "description", "Search body, e.g. {\"size\":5,\"query\":{...}}")),
                                "required", List.of("index"))),
                function("CreateVisualizationTool",
                        "Create a saved visualization in OpenSearch Dashboards and return its URL. "
                                + "Use ONLY when the user asks to save/create a visualization; "
                                + "inline chat charts (timeseries and categorical) are rendered automatically "
                                + "from SearchIndexTool aggregations. Include the returned url in the final answer.",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "title", Map.of("type", "string"),
                                        "chart_type", Map.of("type", "string",
                                                "enum", List.of("bar", "line", "area", "pie", "table", "metric")),
                                        "x_field", Map.of("type", "string", "description", "keyword or date field for segments"),
                                        "y_agg", Map.of("type", "string", "enum", List.of("sum", "avg", "count")),
                                        "y_field", Map.of("type", "string", "description", "numeric field for sum/avg")),
                                "required", List.of("chart_type", "x_field"))));
    }

    private static Map<String, Object> function(String name, String description, Map<String, Object> parameters) {
        return Map.of("type", "function", "function", Map.of(
                "name", name, "description", description, "parameters", parameters));
    }

    public String execute(String name, JsonNode arguments) throws Exception {
        String result = switch (name) {
            case "ListIndexTool" -> {
                String index = arguments.path("index").asText("");
                String url = properties.opensearchUrl() + "/_cat/indices?format=json"
                        + (index.isBlank() ? "" : "&index=" + index);
                yield get(url);
            }
            case "IndexMappingTool" -> get(properties.opensearchUrl()
                    + "/" + arguments.path("index").asText("pg-orders") + "/_mapping");
            case "SearchIndexTool" -> {
                String index = arguments.path("index").asText("pg-orders");
                JsonNode query = arguments.path("query");
                // LLM часто передаёт голый clause ({"range":...}) вместо тела _search —
                // нормализуем, иначе OpenSearch отвечает parsing_exception.
                String body;
                if (query.isMissingNode() || query.isNull()) {
                    body = "{\"size\":5,\"query\":{\"match_all\":{}}}";
                } else if (query.has("query") || query.has("aggs") || query.has("size")) {
                    body = mapper.writeValueAsString(query);
                } else {
                    body = mapper.writeValueAsString(Map.of("query", query, "size", 5));
                }
                yield post(properties.opensearchUrl() + "/" + index + "/_search", body);
            }
            case "CreateVisualizationTool" -> createVisualization(arguments);
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
        return result;
    }

    /**
     * Обрезка для транспорта (SSE/история LLM). Извлечение графиков обязано работать
     * с ПОЛНЫМ результатом: обрезанный JSON не парсится (Jackson падал на '…').
     */
    public static String truncate(String result) {
        return result.length() > MAX_RESULT_CHARS ? result.substring(0, MAX_RESULT_CHARS) + "…(truncated)" : result;
    }

    /** Метрика bucket'а: sum/avg value либо сумма вложенных buckets (nested aggs). */
    private static double metricValue(JsonNode node) {
        if (node.has("value")) {
            return node.path("value").asDouble(0);
        }
        JsonNode buckets = node.path("buckets");
        if (buckets.isArray()) {
            double sum = 0;
            for (JsonNode bucket : buckets) {
                for (String sub : bucket.propertyNames()) {
                    JsonNode subNode = bucket.path(sub);
                    if (subNode.isObject() && (subNode.has("value") || subNode.has("buckets"))) {
                        sum += metricValue(subNode);
                        break;
                    }
                }
            }
            return sum;
        }
        return 0;
    }

    public record Timeseries(List<Map<String, Object>> points, String title) {
    }

    /**
     * Извлекает временной ряд из РЕАЛЬНОГО результата SearchIndexTool (date_histogram):
     * график рисуется только по тому, что агент действительно запросил, а не всегда один и тот же.
     */
    public Timeseries extractTimeseries(JsonNode searchResult) {
        JsonNode aggs = searchResult.path("aggregations");
        if (aggs.isMissingNode()) {
            return null;
        }
        for (String aggName : aggs.propertyNames()) {
            JsonNode buckets = aggs.path(aggName).path("buckets");
            if (!buckets.isArray() || buckets.isEmpty() || !buckets.get(0).has("key_as_string")) {
                continue;
            }
            List<Map<String, Object>> points = new java.util.ArrayList<>();
            for (JsonNode bucket : buckets) {
                double value = bucket.path("doc_count").asDouble(0);
                for (String sub : bucket.propertyNames()) {
                    JsonNode subNode = bucket.path(sub);
                    if (subNode.isObject() && (subNode.has("value") || subNode.has("buckets"))) {
                        value = metricValue(subNode);
                        break;
                    }
                }
                points.add(Map.of(
                        "timestamp", bucket.path("key_as_string").asText(),
                        "value", value));
            }
            if (!points.isEmpty()) {
                return new Timeseries(points, aggName);
            }
        }
        return null;
    }

    public record Categorical(List<Map<String, Object>> points, String title) {
    }

    /**
     * Извлекает категориальный ряд из terms-агрегации (buckets без key_as_string):
     * данные для inline bar/pie графика (action chart_pilot_chart, ).
     */
    public Categorical extractCategorical(JsonNode searchResult) {
        JsonNode aggs = searchResult.path("aggregations");
        if (aggs.isMissingNode()) {
            return null;
        }
        for (String aggName : aggs.propertyNames()) {
            JsonNode buckets = aggs.path(aggName).path("buckets");
            if (!buckets.isArray() || buckets.isEmpty() || buckets.get(0).has("key_as_string")
                    || !buckets.get(0).has("key")) {
                continue;
            }
            List<Map<String, Object>> points = new java.util.ArrayList<>();
            for (JsonNode bucket : buckets) {
                double value = bucket.path("doc_count").asDouble(0);
                for (String sub : bucket.propertyNames()) {
                    JsonNode subNode = bucket.path(sub);
                    if (subNode.isObject() && (subNode.has("value") || subNode.has("buckets"))) {
                        value = metricValue(subNode);
                        break;
                    }
                }
                points.add(Map.of(
                        "name", bucket.path("key").asText(),
                        "value", value));
            }
            if (!points.isEmpty()) {
                return new Categorical(points, aggName);
            }
        }
        return null;
    }

    /** Создаёт сохранённую визуализацию в OSD и возвращает {visualizationId, url, title}. */
    private String createVisualization(JsonNode args) throws Exception {
        String title = args.path("title").asText("ChartPilot visualization");
        String chartType = args.path("chart_type").asText("bar");
        String xField = args.path("x_field").asText("category");
        String yAgg = args.path("y_agg").asText("sum");
        String yField = args.path("y_field").asText("amount");
        String indexPatternId = ensureIndexPattern();
        // Переиспользуем существующую визуализацию с таким же title (без дублей)
        String existing = osdRequest("GET", "/api/saved_objects/_find?type=visualization&search_fields=title&search="
                + java.net.URLEncoder.encode(title, java.nio.charset.StandardCharsets.UTF_8) + "&per_page=1", null);
        String id = mapper.readTree(existing).path("saved_objects").path(0).path("id").asText("");
        if (id.isEmpty()) {
            String visState = buildVisState(title, chartType, xField, yAgg, yField);
            String payload = mapper.writeValueAsString(Map.of(
                    "attributes", Map.of(
                            "title", title,
                            "description", "Created by ChartPilot agent",
                            "visState", visState,
                            "kibanaSavedObjectMeta", Map.of("searchSourceJSON",
                                    "{\"query\":{\"query\":\"\",\"language\":\"kuery\"},\"filter\":[],"
                                            + "\"indexRefName\":\"kibanaSavedObjectMeta.searchSourceJSON.index\"}")),
                    "references", List.of(Map.of(
                            "name", "kibanaSavedObjectMeta.searchSourceJSON.index",
                            "type", "index-pattern",
                            "id", indexPatternId))));
            String response = osdRequest("POST", "/api/saved_objects/visualization", payload);
            id = mapper.readTree(response).path("id").asText("");
            if (id.isEmpty()) {
                return response;
            }
        }
        // Численные итоги считаем сами: LLM обязана привести их в ответе вместе со ссылкой,
        // иначе пользователь получает только ссылку без данных.
        List<SummaryEntry> summary = aggregationSummary(xField, yAgg, yField);
        // Диапазон 30 дней в URL: дефолтные now-15m показывают пустой график (данные за месяц)
        String url = properties.osdPublicUrl() + "/app/visualize#/edit/" + id
                + "?_g=(refreshInterval:(pause:!t,value:0),time:(from:now-30d,to:now))";
        return mapper.writeValueAsString(new VisualizationResult(id, url, title, summary));
    }

    /** Доменный результат CreateVisualizationTool (ARCH-02 ). */
    public record SummaryEntry(String key, double value) {
    }

    public record VisualizationResult(
            String visualizationId,
            String url,
            String title,
            @JsonProperty("data_summary") List<SummaryEntry> dataSummary) {
    }

    private List<SummaryEntry> aggregationSummary(String xField, String yAgg, String yField) {
        try {
            String metric = "count".equals(yAgg) ? "{\"count\":{}}" : "{\"" + yAgg + "\":{\"field\":\"" + yField + "\"}}";
            String body = "{\"size\":0,\"aggs\":{\"by_x\":{\"terms\":{\"field\":\"" + xField
                    + "\",\"size\":20,\"order\":{\"_key\":\"asc\"}},\"aggs\":{\"value\":" + metric + "}}}}";
            JsonNode buckets = mapper.readTree(post(properties.opensearchUrl() + "/pg-orders/_search", body))
                    .path("aggregations").path("by_x").path("buckets");
            List<SummaryEntry> summary = new java.util.ArrayList<>();
            for (JsonNode bucket : buckets) {
                summary.add(new SummaryEntry(
                        bucket.path("key_as_string").asText(bucket.path("key").asText()),
                        bucket.path("value").path("value").asDouble(bucket.path("doc_count").asDouble(0))));
            }
            return summary;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String ensureIndexPattern() throws Exception {
        String found = osdRequest("GET",
                "/api/saved_objects/_find?type=index-pattern&search_fields=title&search=pg-orders&per_page=1", null);
        String id = mapper.readTree(found).path("saved_objects").path(0).path("id").asText("");
        if (!id.isEmpty()) {
            return id;
        }
        String created = osdRequest("POST", "/api/saved_objects/index-pattern",
                "{\"attributes\":{\"title\":\"pg-orders\",\"timeFieldName\":\"created_at\"}}");
        id = mapper.readTree(created).path("id").asText("");
        if (!id.isEmpty()) {
            osdRequest("PUT", "/api/saved_objects/index-pattern/" + id,
                    "{\"attributes\":{\"title\":\"pg-orders\",\"timeFieldName\":\"created_at\",\"fields\":"
                            + schema.fieldsJson() + "}}");
        }
        return id;
    }

    private String buildVisState(String title, String chartType, String xField, String yAgg, String yField) {
        String type = switch (chartType) {
            case "line" -> "line";
            case "area" -> "area";
            case "pie" -> "pie";
            case "table" -> "table";
            case "metric" -> "metric";
            default -> "histogram";
        };
        String yLabel = "count".equals(yAgg) ? "Count" : yAgg + " of " + yField;
        var vs = mapper.createObjectNode();
        vs.put("title", title);
        vs.put("type", type);
        var params = vs.putObject("params");
        params.put("type", type);
        params.put("addTooltip", true);
        params.put("addLegend", true);
        params.put("legendPosition", "right");
        if (type.equals("histogram") || type.equals("line") || type.equals("area")) {
            params.putObject("grid").put("categoryLines", false);
            var catAxes = params.putArray("categoryAxes");
            var cat = catAxes.addObject();
            cat.put("id", "CategoryAxis-1");
            cat.put("type", "category");
            cat.put("position", "bottom");
            cat.put("show", true);
            cat.putObject("style");
            cat.putObject("scale").put("type", "linear");
            cat.putObject("labels").put("show", true).put("filter", true).put("truncate", 100);
            cat.putObject("title");
            var valAxes = params.putArray("valueAxes");
            var val = valAxes.addObject();
            val.put("id", "ValueAxis-1");
            val.put("name", "LeftAxis-1");
            val.put("type", "value");
            val.put("position", "left");
            val.put("show", true);
            val.putObject("style");
            val.putObject("scale").put("type", "linear").put("mode", "normal");
            val.putObject("labels").put("show", true).put("rotate", 0).put("filter", false).put("truncate", 100);
            val.putObject("title").put("text", yLabel);
            var seriesParams = params.putArray("seriesParams");
            var series = seriesParams.addObject();
            series.put("show", true);
            series.put("type", type);
            series.put("mode", "stacked");
            series.putObject("data").put("label", yLabel).put("id", "1");
            series.put("valueAxis", "ValueAxis-1");
            series.put("drawLinesBetweenPoints", true);
            series.put("lineWidth", 2);
            series.put("showCircles", true);
            params.putArray("times");
            params.put("addTimeMarker", false);
            params.putObject("labels");
        } else if (type.equals("pie")) {
            params.put("isDonut", false);
        } else if (type.equals("table")) {
            params.put("perPage", 10);
            params.put("showPartialRows", false);
            params.put("showMetricsAtAllLevels", false);
            params.put("showTotal", false);
            params.put("totalFunc", "sum");
            params.putObject("sort").putNull("columnIndex").putNull("direction");
        } else {
            var metric = params.putObject("metric");
            metric.put("percentageMode", false);
            metric.put("useRanges", false);
            metric.put("colorSchema", "Green to Red");
            metric.put("metricColorMode", "None");
            metric.putArray("colorsRange").addObject().put("from", 0).put("to", 10000);
            metric.putObject("labels").put("show", true);
            metric.put("invertColors", false);
            var style = metric.putObject("style");
            style.put("bgFill", "#000");
            style.put("bgColor", false);
            style.put("labelColor", false);
            style.put("subText", "");
            style.put("fontSize", 60);
        }
        var aggs = vs.putArray("aggs");
        var metricAgg = aggs.addObject();
        metricAgg.put("id", "1");
        metricAgg.put("enabled", true);
        metricAgg.put("type", "count".equals(yAgg) ? "count" : yAgg);
        metricAgg.put("schema", "metric");
        var metricParams = metricAgg.putObject("params");
        if (!"count".equals(yAgg)) {
            metricParams.put("field", yField);
        }
        if (!type.equals("metric")) {
            var segment = aggs.addObject();
            segment.put("id", "2");
            segment.put("enabled", true);
            segment.put("schema", "segment");
            var segParams = segment.putObject("params");
            if ("date".equals(schema.fieldType(xField))) {
                segment.put("type", "date_histogram");
                segParams.put("field", xField);
                segParams.put("calendar_interval", "day");
            } else {
                segment.put("type", "terms");
                segParams.put("field", xField);
                segParams.put("orderBy", "1");
                segParams.put("order", "desc");
                segParams.put("size", 10);
                segParams.put("otherBucket", false);
                segParams.put("otherBucketLabel", "Other");
                segParams.put("missingBucket", false);
                segParams.put("missingBucketLabel", "Missing");
            }
        }
        return vs.toString();
    }

    private String osdRequest(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.osdUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("osd-xsrf", "true");
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return send(builder.build());
    }

    private String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return send(request);
    }

    private String post(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    private String send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            return "{\"error\":\"HTTP " + response.statusCode() + "\",\"body\":" + response.body() + "}";
        }
        return response.body();
    }
}