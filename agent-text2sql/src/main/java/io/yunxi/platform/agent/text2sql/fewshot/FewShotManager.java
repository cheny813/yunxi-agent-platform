package io.yunxi.platform.agent.text2sql.fewshot;

import io.yunxi.platform.agent.text2sql.config.Text2SqlProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Few-shot 示例管理器
 * <p>
 * 管理 Few-shot 示例，支持相似度检索、自动更新等
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Component
public class FewShotManager {

    private static final Logger log = LoggerFactory.getLogger(FewShotManager.class);

    @Autowired
    private Text2SqlProperties text2SqlProperties;

    // Few-shot 示例存储 (key: databaseId, value: examples list)
    private final Map<String, List<FewShotExample>> examplesByDatabase = new ConcurrentHashMap<>();

    /**
     * Few-shot 示例
     */
    public static class FewShotExample {
        private String question;
        private String sql;
        private String schema;
        private double score;
        private long timestamp;

        public FewShotExample() {
        }

        public FewShotExample(String question, String sql, String schema, double score) {
            this.question = question;
            this.sql = sql;
            this.schema = schema;
            this.score = score;
            this.timestamp = System.currentTimeMillis();
        }

        // Getters and Setters
        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }

        public String getSql() {
            return sql;
        }

        public void setSql(String sql) {
            this.sql = sql;
        }

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }
    }

    /**
     * 检索相似的 Few-shot 示例
     *
     * @param databaseId 数据库 ID
     * @param question   用户问题
     * @param schema     数据库 Schema（可选）
     * @param k          返回示例数量
     * @return 相似示例列表
     */
    public List<FewShotExample> retrieveExamples(String databaseId, String question,
            String schema, int k) {
        List<FewShotExample> allExamples = examplesByDatabase.get(databaseId);
        if (allExamples == null || allExamples.isEmpty()) {
            return Collections.emptyList();
        }

        // 简化处理：使用简单的字符串相似度
        List<FewShotExample> scoredExamples = new ArrayList<>();
        for (FewShotExample example : allExamples) {
            double similarity = calculateSimilarity(question, example.getQuestion());
            if (similarity >= text2SqlProperties.getFewshot().getSimilarityThreshold()) {
                FewShotExample scored = new FewShotExample(
                        example.getQuestion(),
                        example.getSql(),
                        example.getSchema(),
                        similarity);
                scoredExamples.add(scored);
            }
        }

        // 按相似度排序
        scoredExamples.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        // 返回前 k 个
        int limit = Math.min(k, scoredExamples.size());
        return scoredExamples.subList(0, limit);
    }

    /**
     * 添加示例
     *
     * @param databaseId 数据库 ID
     * @param question   问题
     * @param sql        SQL
     * @param schema     Schema
     * @param score      评分（可选）
     */
    public void addExample(String databaseId, String question, String sql,
            String schema, double score) {
        List<FewShotExample> examples = examplesByDatabase.computeIfAbsent(
                databaseId, k -> new ArrayList<>());

        FewShotExample example = new FewShotExample(question, sql, schema, score);
        examples.add(example);

        // 限制数量
        if (examples.size() > text2SqlProperties.getFewshot().getMaxExamples()) {
            // 移除最旧的示例
            examples.sort(Comparator.comparingLong(FewShotExample::getTimestamp));
            examples.remove(0);
        }

        log.info("添加 Few-shot 示例: databaseId={}, question={}, score={}", databaseId, question, score);
    }

    /**
     * 批量添加示例
     *
     * @param databaseId 数据库 ID
     * @param examples   示例列表
     */
    public void addExamples(String databaseId, List<FewShotExample> examples) {
        if (examples == null || examples.isEmpty()) {
            return;
        }

        List<FewShotExample> list = examplesByDatabase.computeIfAbsent(
                databaseId, k -> new ArrayList<>());

        list.addAll(examples);

        // 限制数量
        if (list.size() > text2SqlProperties.getFewshot().getMaxExamples() * 2) {
            list.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
            while (list.size() > text2SqlProperties.getFewshot().getMaxExamples()) {
                list.remove(list.size() - 1);
            }
        }

        log.info("批量添加 Few-shot 示例: databaseId={}, count={}", databaseId, examples.size());
    }

    /**
     * 删除数据库的所有示例
     *
     * @param databaseId 数据库 ID
     */
    public void clearExamples(String databaseId) {
        examplesByDatabase.remove(databaseId);
        log.info("清除 Few-shot 示例: databaseId={}", databaseId);
    }

    /**
     * 获取示例数量
     *
     * @param databaseId 数据库 ID
     * @return 示例数量
     */
    public int getExampleCount(String databaseId) {
        List<FewShotExample> examples = examplesByDatabase.get(databaseId);
        return examples == null ? 0 : examples.size();
    }

    /**
     * 计算字符串相似度（简化版）
     * <p>
     * 使用简单的字符重叠度作为相似度度量
     * </p>
     *
     * @param s1 字符串 1
     * @param s2 字符串 2
     * @return 相似度 [0, 1]
     */
    private double calculateSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return 0.0;
        }

        if (s1.equals(s2)) {
            return 1.0;
        }

        // 简化处理：使用字符重叠度
        Set<String> words1 = new HashSet<>(Arrays.asList(s1.toLowerCase().split("\\s+")));
        Set<String> words2 = new HashSet<>(Arrays.asList(s2.toLowerCase().split("\\s+")));

        int intersection = 0;
        for (String word : words1) {
            if (words2.contains(word)) {
                intersection++;
            }
        }

        int union = words1.size() + words2.size() - intersection;
        if (union == 0) {
            return 0.0;
        }

        return (double) intersection / union;
    }

    /**
     * 格式化示例为 Prompt 格式
     *
     * @param examples 示例列表
     * @return Prompt 字符串
     */
    public String formatExamplesToPrompt(List<FewShotExample> examples) {
        if (examples == null || examples.isEmpty()) {
            return "";
        }

        StringBuilder prompt = new StringBuilder();
        for (FewShotExample example : examples) {
            prompt.append("Q: ").append(example.getQuestion()).append("\n");
            prompt.append("A: ").append(example.getSql()).append("\n\n");
        }

        return prompt.toString();
    }
}
