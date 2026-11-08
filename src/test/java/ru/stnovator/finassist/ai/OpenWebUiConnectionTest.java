package ru.stnovator.finassist.ai;

import com.sun.net.httpserver.HttpServer;
import io.jmix.aitools.ChatClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.support.ResourcePropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests the configured Spring AI client against the Open WebUI HTTP contract, without live credentials. */
class OpenWebUiConnectionTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private int status = 200;
    private boolean toolCall;
    private boolean withholdResponse;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var body = json.readTree(exchange.getRequestBody().readAllBytes());
            requests.add(new Request(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"), body));
            if (withholdResponse) {
                return; // Keep the connection open until client timeout; stopServer closes it.
            }
            String response;
            if (status != 200) {
                response = "{\"error\":{\"message\":\"Gateway rejected request\",\"type\":\"api_error\"}}";
            } else if (body.path("stream").asBoolean()) {
                response = """
                        data: {"id":"reply","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"role":"assistant","content":"Ready"},"finish_reason":null}]}

                        data: {"id":"reply","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                        data: [DONE]

                        """;
            } else if (toolCall && requests.size() == 1) {
                response = completion("{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
                        + "{\"id\":\"call_1\",\"type\":\"function\",\"function\":{"
                        + "\"name\":\"lookup\",\"arguments\":\"{}\"}}]}", "tool_calls");
            } else {
                response = completion("{\"role\":\"assistant\",\"content\":\"Ready\"}", "stop");
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",
                    body.path("stream").asBoolean() ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsConfiguredModelAndBearerKeyToOpenWebUiPath() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            // Guard against accidentally making an external request when configuration regresses.
            assertThat(context.getBean(OpenAiCommonProperties.class).getBaseUrl()).isEqualTo(baseUrl());
            assertThat(context.getBean(OpenAiChatModel.class).call("Hello")).isEqualTo("Ready");
            assertThat(requests).hasSize(1);
            assertRequest(requests.getFirst());
            assertThat(requests.getFirst().body().has("reasoning_effort")).isFalse();
        });
    }

    @Test
    void executesToolLocallyAndReturnsResultToGateway() {
        toolCall = true;
        var calls = new AtomicInteger();
        var tool = FunctionToolCallback.builder("lookup", () -> {
            calls.incrementAndGet();
            return "contract-42";
        }).description("Look up a test contract").build();
        runner().run(context -> {
            assertThat(context.getBean(OpenAiCommonProperties.class).getBaseUrl()).isEqualTo(baseUrl());
            var reply = context.getBean(ChatClientFactory.class).createChatClientWithDefaultAdvisors()
                    .orElseThrow().prompt("Find contract").tools(tool).call().content();
            assertThat(reply).isEqualTo("Ready");
            assertThat(calls.get()).isEqualTo(1);
            assertThat(requests).hasSize(2).allSatisfy(this::assertRequest);
            assertThat(requests.getFirst().body().path("tools").get(0).path("function").path("name").asString())
                    .isEqualTo("lookup");
            assertThat(requests.getLast().body().path("messages").toString())
                    .contains("\"role\":\"tool\"", "call_1", "contract-42");
        });
    }

    @Test
    void readsStreamingResponseFromGateway() {
        runner().run(context -> {
            assertThat(context.getBean(OpenAiCommonProperties.class).getBaseUrl()).isEqualTo(baseUrl());
            var chunks = context.getBean(OpenAiChatModel.class).stream("Hello")
                    .collectList().block(Duration.ofSeconds(5));
            assertThat(chunks).isNotNull();
            assertThat(String.join("", chunks)).isEqualTo("Ready");
            assertThat(requests).hasSize(1).allSatisfy(this::assertRequest);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 502})
    void surfacesGatewayErrors(int httpStatus) {
        status = httpStatus;
        runner().run(context -> {
            assertThat(context.getBean(OpenAiCommonProperties.class).getBaseUrl()).isEqualTo(baseUrl());
            assertThatThrownBy(() -> context.getBean(OpenAiChatModel.class).call("Hello"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(requests).hasSize(1);
        });
    }

    @Test
    void timesOutWhenGatewayDoesNotRespond() {
        withholdResponse = true;
        runner().withPropertyValues("spring.ai.openai.timeout=200ms").run(context -> {
            assertThat(context.getBean(OpenAiCommonProperties.class).getBaseUrl()).isEqualTo(baseUrl());
            assertThatThrownBy(() -> context.getBean(OpenAiChatModel.class).call("Hello"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(requests).hasSize(1);
        });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OpenAiChatAutoConfiguration.class,
                        ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class))
                .withBean(ChatClientFactory.class)
                .withInitializer(context -> {
                    try {
                        context.getEnvironment().getPropertySources().addLast(
                                new ResourcePropertySource("classpath:application.properties"));
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                })
                .withPropertyValues("OPENAI_BASE_URL=" + baseUrl(), "OPENAI_API_KEY=test-key",
                        "OPENAI_MODEL=test-model", "spring.ai.openai.max-retries=0",
                        "spring.ai.openai.timeout=2s");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
    }

    private void assertRequest(Request request) {
        assertThat(request.path()).isEqualTo("/api/chat/completions");
        assertThat(request.authorization()).isEqualTo("Bearer test-key");
        assertThat(request.body().path("model").asString()).isEqualTo("test-model");
    }

    private String completion(String message, String finishReason) {
        return "{\"id\":\"reply\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test-model\","
                + "\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\"" + finishReason + "\"}]}";
    }

    private record Request(String path, String authorization, JsonNode body) { }
}
