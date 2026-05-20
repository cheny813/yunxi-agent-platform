package io.yunxi.platform.agent.text2sql.generation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * SQL 生成器
 * <p>
 * 使用 DashScope LLM 生成 SQL，支持 Few-shot Learning 和 Self-Consistency
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Component
@ConditionalOnProperty(name = "dashscope.api-key", matchIfMissing = false)
public class SqlGenerator {

    private static final Logger log = LoggerFactory.getLogger(SqlGenerator.class);
    private static final String DASHSCOPE_API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${dashscope.api-key:}")
    private String apiKey;

    @Value("${dashscope.model:qwen-plus}")
    private String modelName;

    @Value("${dashscope.temperature:0.0}")
    private double temperature;

    @Value("${dashscope.base-url:}")
    private String baseUrl;

    /**
     * 构造函数
     */
    @Autowired
    public SqlGenerator() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .readTimeout(java.time.Duration.ofSeconds(120))
                .writeTimeout(java.time.Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 生成 SQL（单候选）
     *
     * @param question        用户问题
     * @param databaseSchema  数据库 Schema
     * @param relevantColumns 相关列
     * @param fewShots        Few-shot 示例
     * @return 生成的 SQL
     */
    public String generateSql(String question, String databaseSchema,
            List<String> relevantColumns, List<String> fewShots) {
        try {
            String prompt = buildPrompt(question, databaseSchema, relevantColumns, fewShots);

            log.debug("生成 SQL: question={}, model={}", question, modelName);

            String response = callDashScope(prompt);

            String sql = cleanSql(response);

            log.info("SQL 生成完成: question={}, sql={}", question, sql);

            return sql;

        } catch (Exception e) {
            log.error("SQL 生成失败: question={}", question, e);
            return null;
        }
    }

    /**
     * 调用 DashScope API
     *
     * @param prompt 输入提示词
     * @return API 返回的文本内容
     */
    private String callDashScope(String prompt) throws Exception {
        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelName);

        // 构建消息
        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> userMessage = new java.util.HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);
        requestBody.put("messages", messages);

        // 设置参数
        requestBody.put("temperature", temperature);
        requestBody.put("max_tokens", 2000);

        // 序列化请求体
        String jsonBody = objectMapper.writeValueAsString(requestBody);
        log.debug("DashScope 请求体: {}", jsonBody);

        // 构建请求
        String apiUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : DASHSCOPE_API_URL;
        Request request = new Request.Builder()
                .url(apiUrl)
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .build();

        // 发送请求
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                throw new RuntimeException("DashScope API error: " + response.code() + " - " + errorBody);
            }

            String responseBody = response.body().string();
            log.debug("DashScope 响应体: {}", responseBody);

            // 解析响应
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            JsonNode choices = jsonNode.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                return choices.get(0).path("message").path("content").asText();
            }

            throw new RuntimeException("Invalid response from DashScope API: no choices found");
        }
    }

    /**
     * 生成多个候选 SQL（Self-Consistency）
     *
     * @param question        用户问题
     * @param databaseSchema  数据库 Schema
     * @param relevantColumns 相关列
     * @param fewShots        Few-shot 示例
     * @param n               候选数量
     * @return 候选 SQL 列表
     */
    public List<String> generateCandidateSqls(String question, String databaseSchema,
            List<String> relevantColumns, List<String> fewShots, int n) {
        List<String> candidates = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            String sql = generateSql(question, databaseSchema, relevantColumns, fewShots);
            if (sql != null && !sql.isEmpty()) {
                candidates.add(sql);
            }
        }

        log.info("生成 {} 个候选 SQL: question={}", candidates.size(), question);

        return candidates;
    }

    /**
     * 构建 Prompt
     */
    private String buildPrompt(String question, String databaseSchema,
            List<String> relevantColumns, List<String> fewShots) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are an SQL expert. Generate SQL based on following information:\n\n");

        // 1. Database Schema
        prompt.append("# Database Schema:\n");
        prompt.append(databaseSchema).append("\n\n");

        // 2. Relevant Columns
        if (relevantColumns != null && !relevantColumns.isEmpty()) {
            prompt.append("# Relevant Columns:\n");
            for (String column : relevantColumns) {
                prompt.append("- ").append(column).append("\n");
            }
            prompt.append("\n");
        }

        // 3. Few-shot Examples
        if (fewShots != null && !fewShots.isEmpty()) {
            prompt.append("# Examples:\n");
            prompt.append(String.join("\n", fewShots)).append("\n\n");
        }

        // 4. Question
        prompt.append("# Question:\n");
        prompt.append(question).append("\n\n");

        prompt.append("Generate a SQL query to answer the question. ");
        prompt.append("Only output the SQL, no explanation needed.\n");

        return prompt.toString();
    }

    /**
     * 清理 SQL（移除 Markdown、多余空格等）
     */
    private String cleanSql(String sql) {
        if (sql == null) {
            return null;
        }

        // 移除 Markdown 代码块标记
        sql = sql.replaceAll("```sql", "");
        sql = sql.replaceAll("```", "");
        sql = sql.replaceAll("~~.*~~", "");

        // 移除多余空格和换行
        sql = sql.trim();
        sql = sql.replaceAll("\\s+", " ");

        return sql;
    }
}
