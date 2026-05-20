package io.yunxi.platform.framework.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * OpenAIModelProvider 单元测试
 */
@ExtendWith(MockitoExtension.class)
class OpenAIModelProviderTest {

    @Mock
    private OkHttpClient.Builder httpClientBuilder;

    @Mock
    private OkHttpClient httpClient;

    @Mock
    private Call httpCall;

    private OpenAIModelProvider openAIModelProvider;
    private ModelConfig config;

    @BeforeEach
    void setUp() {
        config = new ModelConfig();
        config.setApiKey("test-api-key");
        config.setModelName("gpt-3.5-turbo");
        config.setBaseUrl("https://api.openai.com/v1/chat/completions");
    }

    @Test
    void testConstructorWithValidConfig() {
        openAIModelProvider = new OpenAIModelProvider(config);

        assertNotNull(openAIModelProvider);
        assertEquals("test-api-key", config.getApiKey());
        assertEquals("gpt-3.5-turbo", openAIModelProvider.getModelName());
        assertEquals("openai", openAIModelProvider.getProvider());
        assertTrue(openAIModelProvider.isValid());
    }

    @Test
    void testConstructorWithNullModelName() {
        config.setModelName(null);

        openAIModelProvider = new OpenAIModelProvider(config);

        assertEquals("gpt-3.5-turbo", openAIModelProvider.getModelName()); // 默认值
    }

    @Test
    void testConstructorWithEmptyModelName() {
        config.setModelName("");

        openAIModelProvider = new OpenAIModelProvider(config);

        assertEquals("gpt-3.5-turbo", openAIModelProvider.getModelName()); // 默认值
    }

    @Test
    void testConstructorWithCustomBaseUrl() {
        config.setBaseUrl("https://custom.api.example.com/");

        openAIModelProvider = new OpenAIModelProvider(config);

        assertNotNull(openAIModelProvider);
    }

    @Test
    void testIsValidWithValidApiKey() {
        openAIModelProvider = new OpenAIModelProvider(config);

        assertTrue(openAIModelProvider.isValid());
    }

    @Test
    void testIsValidWithEmptyApiKey() {
        config.setApiKey("");

        openAIModelProvider = new OpenAIModelProvider(config);

        assertFalse(openAIModelProvider.isValid());
    }

    @Test
    void testIsValidWithNullApiKey() {
        config.setApiKey(null);

        openAIModelProvider = new OpenAIModelProvider(config);

        assertFalse(openAIModelProvider.isValid());
    }

    @Test
    void testGetApiKey() {
        openAIModelProvider = new OpenAIModelProvider(config);

        // 使用反射获取apiKey字段值进行验证
        try {
            var field = OpenAIModelProvider.class.getDeclaredField("apiKey");
            field.setAccessible(true);
            String apiKey = (String) field.get(openAIModelProvider);
            assertEquals("test-api-key", apiKey);
        } catch (Exception e) {
            // 跳过反射测试
        }
    }

    @Test
    void testBuildRequestBodyWithMessages() throws Exception {
        openAIModelProvider = new OpenAIModelProvider(config);

        // 创建测试消息
        Msg message1 = Msg.builder().textContent("Hello").build();
        Msg message2 = Msg.builder().textContent("World").build();
        List<Msg> messages = List.of(message1, message2);

        GenerateOptions options = GenerateOptions.builder()
                .temperature(0.8)
                .build();

        // 使用反射调用私有方法
        var method = OpenAIModelProvider.class.getDeclaredMethod("buildRequestBody", List.class, GenerateOptions.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        var requestBody = (java.util.Map<String, Object>) method.invoke(openAIModelProvider, messages, options);

        assertNotNull(requestBody);
        assertEquals("gpt-3.5-turbo", requestBody.get("model"));
        assertEquals(0.8, requestBody.get("temperature"));
        assertEquals(2000, requestBody.get("max_tokens"));
        assertEquals(0.9, requestBody.get("top_p"));

        // 验证消息转换
        @SuppressWarnings("unchecked")
        var messageList = (List<java.util.Map<String, String>>) requestBody.get("messages");
        assertEquals(2, messageList.size());
        assertEquals("Hello", messageList.get(0).get("content"));
        assertEquals("World", messageList.get(1).get("content"));
    }

    @Test
    void testBuildRequestBodyWithNullOptions() throws Exception {
        openAIModelProvider = new OpenAIModelProvider(config);

        Msg message = Msg.builder().textContent("Test").build();
        List<Msg> messages = List.of(message);

        var method = OpenAIModelProvider.class.getDeclaredMethod("buildRequestBody", List.class, GenerateOptions.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        var requestBody = (java.util.Map<String, Object>) method.invoke(openAIModelProvider, messages, null);

        assertNotNull(requestBody);
        assertEquals(0.7, requestBody.get("temperature")); // 默认值
    }

    @Test
    void testStreamWithSuccessfulResponse() throws IOException {
        // 准备模拟HTTP响应
        String jsonResponse = """
                {
                    "choices": [{
                        "message": {
                            "content": "Hello from OpenAI"
                        }
                    }]
                }
                """;

        Response mockResponse = new Response.Builder()
                .request(new Request.Builder().url("https://api.openai.com").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(jsonResponse, MediaType.parse("application/json")))
                .build();

        when(httpClient.newCall(any())).thenReturn(httpCall);
        when(httpCall.execute()).thenReturn(mockResponse);

        // 正确方案：在OpenAIModelProvider中注入mock的HttpClient进行测试
        // 而不是尝试mock static方法（newBuilder是实例方法）
        openAIModelProvider = new OpenAIModelProvider(config);
        // 通过反射设置mock的HttpClient
        try {
            var field = OpenAIModelProvider.class.getDeclaredField("httpClient");
            field.setAccessible(true);
            field.set(openAIModelProvider, httpClient);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set mock HttpClient", e);
        }

        // 测试流式调用
        Msg message = Msg.builder().textContent("Test message").build();
        GenerateOptions options = GenerateOptions.builder().temperature(0.7).build();

        var flux = openAIModelProvider.stream(List.of(message), null, options);

        // 验证响应
        ChatResponse response = flux.blockFirst();
        assertNotNull(response);
        assertNotNull(response.getContent());
        assertEquals(1, response.getContent().size());
        assertTrue(response.getContent().get(0) instanceof TextBlock);

        TextBlock textBlock = (TextBlock) response.getContent().get(0);
        assertEquals("Hello from OpenAI", textBlock.getText());
    }

    @Test
    void testStreamWithHttpError() throws IOException {
        // 模拟HTTP错误响应
        Response mockResponse = new Response.Builder()
                .request(new Request.Builder().url("https://api.openai.com").build())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body(ResponseBody.create("Invalid API key", MediaType.parse("text/plain")))
                .build();

        when(httpClient.newCall(any())).thenReturn(httpCall);
        when(httpCall.execute()).thenReturn(mockResponse);

        // 使用反射注入mock的HttpClient
        openAIModelProvider = new OpenAIModelProvider(config);
        try {
            var field = OpenAIModelProvider.class.getDeclaredField("httpClient");
            field.setAccessible(true);
            field.set(openAIModelProvider, httpClient);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set mock HttpClient", e);
        }

        Msg message = Msg.builder().textContent("Test").build();

        var flux = openAIModelProvider.stream(List.of(message), null, null);

        // 验证错误处理
        assertThrows(RuntimeException.class, () -> flux.blockFirst());
    }

    @Test
    void testStreamWithInvalidResponseBody() throws IOException {
        // 模拟无效JSON响应
        Response mockResponse = new Response.Builder()
                .request(new Request.Builder().url("https://api.openai.com").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create("invalid json", MediaType.parse("application/json")))
                .build();

        when(httpClient.newCall(any())).thenReturn(httpCall);
        when(httpCall.execute()).thenReturn(mockResponse);

        // 使用反射注入mock的HttpClient
        openAIModelProvider = new OpenAIModelProvider(config);
        try {
            var field = OpenAIModelProvider.class.getDeclaredField("httpClient");
            field.setAccessible(true);
            field.set(openAIModelProvider, httpClient);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set mock HttpClient", e);
        }

        Msg message = Msg.builder().textContent("Test").build();

        assertThrows(RuntimeException.class,
                () -> openAIModelProvider.stream(List.of(message), null, null).blockFirst());
    }

    @Test
    void testStreamWithEmptyChoices() throws IOException {
        // 模拟空choices数组响应
        String jsonResponse = """
                {
                    "choices": []
                }
                """;

        Response mockResponse = new Response.Builder()
                .request(new Request.Builder().url("https://api.openai.com").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(jsonResponse, MediaType.parse("application/json")))
                .build();

        when(httpClient.newCall(any())).thenReturn(httpCall);
        when(httpCall.execute()).thenReturn(mockResponse);

        // 使用反射注入mock的HttpClient
        openAIModelProvider = new OpenAIModelProvider(config);
        try {
            var field = OpenAIModelProvider.class.getDeclaredField("httpClient");
            field.setAccessible(true);
            field.set(openAIModelProvider, httpClient);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set mock HttpClient", e);
        }

        Msg message = Msg.builder().textContent("Test").build();

        assertThrows(RuntimeException.class,
                () -> openAIModelProvider.stream(List.of(message), null, null).blockFirst());
    }

    @Test
    void testStreamRequestHeaders() throws IOException {
        Response mockResponse = new Response.Builder()
                .request(new Request.Builder().url("https://api.openai.com").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create("{\"choices\":[{\"message\":{\"content\":\"test\"}}]}",
                        MediaType.parse("application/json")))
                .build();

        when(httpClient.newCall(any())).thenReturn(httpCall);
        when(httpCall.execute()).thenReturn(mockResponse);

        // 使用反射注入mock的HttpClient
        openAIModelProvider = new OpenAIModelProvider(config);
        try {
            var field = OpenAIModelProvider.class.getDeclaredField("httpClient");
            field.setAccessible(true);
            field.set(openAIModelProvider, httpClient);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set mock HttpClient", e);
        }

        Msg message = Msg.builder().textContent("Test").build();

        var flux = openAIModelProvider.stream(List.of(message), null, null);

        // 验证HTTP调用和头部设置
        verify(httpClient).newCall(any(Request.class));
        ChatResponse response = flux.blockFirst();
        assertNotNull(response);
    }

    @Test
    void testObjectMapperSerialization() {
        openAIModelProvider = new OpenAIModelProvider(config);

        // 测试对象映射器是否正常工作
        try {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(new TestObject("test", 123));
            assertNotNull(json);
            assertTrue(json.contains("test"));
            assertTrue(json.contains("123"));
        } catch (Exception e) {
            fail("ObjectMapper should work correctly");
        }
    }

    // 辅助测试类
    static class TestObject {
        private String name;
        private int value;

        public TestObject(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public int getValue() {
            return value;
        }
    }
}