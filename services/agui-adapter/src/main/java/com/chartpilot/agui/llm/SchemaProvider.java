package com.chartpilot.agui.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.chartpilot.agui.config.AdapterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Схема индекса pg-orders для system-prompt'а: без неё LLM галлюцинирует
 * имена индексов/полей (orders, region, order_amount...) и строит неверные запросы.
 */
@Component
public class SchemaProvider {

    private static final Logger log = LoggerFactory.getLogger(SchemaProvider.class);

    private final AdapterProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private volatile String cachedSchema;
    private volatile JsonNode cachedProperties;

    public SchemaProvider(AdapterProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public JsonNode mappingProperties() {
        JsonNode current = cachedProperties;
        if (current != null) {
            return current;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(properties.opensearchUrl() + "/pg-orders/_mapping"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                // alias pg-orders resolves to concrete pg-orders-v1: ключ — concrete-имя
                Collection<String> names = root.propertyNames();
                JsonNode indexNode = names.isEmpty() ? root : root.path(names.iterator().next());
                current = indexNode.path("mappings").path("properties");
            }
        } catch (Exception e) {
            log.warn("Failed to load pg-orders mapping: {}", e.getMessage());
        }
        if (current == null || current.isMissingNode() || current.isEmpty()) {
            // не кэшируем пустую схему: повторим загрузку на следующем вызове
            return mapper.createObjectNode();
        }
        cachedProperties = current;
        return current;
    }

    public String fieldType(String name) {
        return mappingProperties().path(name).path("type").asText("");
    }

    /** JSON-массив fields для saved-object index-pattern (как в provisioning-скрипте). */
    public String fieldsJson() {
        List<String> fields = new ArrayList<>();
        mappingProperties().propertyNames().forEach(name -> {
            String type = fieldType(name);
            String osdType = switch (type) {
                case "date" -> "date";
                case "keyword", "text" -> "string";
                case "long", "integer", "double", "float", "scaled_float" -> "number";
                default -> "string";
            };
            fields.add("{\"name\":\"" + name + "\",\"type\":\"" + osdType + "\",\"esTypes\":[\"" + type
                    + "\"],\"count\":0,\"scripted\":false,\"searchable\":true,\"aggregatable\":true,\"readFromDocValues\":true}");
        });
        return "[" + String.join(",", fields) + "]";
    }

    public String systemPrompt() {
        String schema = cachedSchema;
        if (schema == null) {
            JsonNode props = mappingProperties();
            List<String> fields = new ArrayList<>();
            props.propertyNames().forEach(name -> fields.add("- " + name + " (" + fieldType(name) + ")"));
            schema = fields.isEmpty() ? "(схема недоступна)" : String.join("\n", fields);
            if (!fields.isEmpty()) {
                cachedSchema = schema;
            }
        }
        return """
                Ты аналитик данных OpenSearch в ChartPilot. Отвечай на русском, кратко и по делу.
                Единственный индекс с данными: pg-orders. Его поля:
                %s
                Правила запросов через SearchIndexTool:
                - аргумент query — это ПОЛНОЕ тело _search: {"size":N,"query":{...},"aggs":{...}};
                  голый clause ({"range":...}) недопустим — оборачивай в {"query": clause}.
                - для сумм/средних/топов используй aggs (terms по keyword-полям, sum/avg по amount);
                - для графиков по времени используй date_histogram по created_at (calendar_interval day)
                  с вложенной sum по amount — клиент нарисует inline-график временного ряда;
                - для категориальных графиков используй terms-агрегацию по keyword-полям
                  с вложенной sum/avg по amount — клиент нарисует inline bar/pie график сам;
                - CreateVisualizationTool вызывай ТОЛЬКО если пользователь просит сохранить/создать
                  визуализацию в Dashboards; в финальном ответе ОБЯЗАТЕЛЬНО приведи численные итоги
                  из data_summary результата этого инструмента (таблица/список) И ссылку url;
                  только ссылка без чисел — неверный ответ;
                - фильтры по времени: range по created_at (формат now-30d/d допустим);
                - не выдумывай индексы и поля: используй только перечисленные выше.
                После получения данных дай численный ответ (таблица/список), без выдуманных цифр.
                """.formatted(schema);
    }
}