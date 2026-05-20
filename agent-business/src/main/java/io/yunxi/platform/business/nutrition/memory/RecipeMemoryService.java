package io.yunxi.platform.business.nutrition.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.yunxi.platform.framework.embedding.EmbeddingService;
import io.yunxi.platform.spi.vector.VectorPersistenceProvider;
import io.yunxi.platform.spi.vector.VectorPersistenceProvider.SearchResult;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 食谱历史记忆服务
 *
 * <p>
 * 实现食谱级别的长期记忆，支持：
 * <ul>
 * <li>历史食谱存储（向量化，语义检索）</li>
 * <li>食谱评分记录（追踪优化效果）</li>
 * <li>学校专属食谱库（隔离不同学校数据）</li>
 * <li>周期性食谱推荐（避免重复）</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>使用场景</b>：
 * <pre>
 * 1. Agent 生成新食谱时，检索历史避免重复
 * 2. Agent 优化食谱时，参考历史评分记录
 * 3. 用户查询时，快速找到历史食谱
 * </pre>
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true")
public class RecipeMemoryService {

    /** 向量持久化提供者，用于存储和检索向量数据 */
    private final VectorPersistenceProvider vectorStrategy;
    /** 向量嵌入服务，用于文本向量化 */
    private final EmbeddingService embeddingService;
    /** JSON 序列化/反序列化工具 */
    private final ObjectMapper objectMapper;

    /** 本地缓存：学校级最近食谱 */
    private final Map<String, List<RecipeMemory>> schoolRecipeCache = new ConcurrentHashMap<>();
    /** 每个学校缓存的最大食谱数量 */
    private static final int MAX_RECENT_RECIPES = 50;

    /**
     * 构造方法
     *
     * @param vectorStrategy   向量持久化提供者
     * @param embeddingService 向量嵌入服务
     */
    @Autowired
    public RecipeMemoryService(
            VectorPersistenceProvider vectorStrategy,
            EmbeddingService embeddingService) {
        this.vectorStrategy = vectorStrategy;
        this.embeddingService = embeddingService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 保存食谱记忆
     *
     * @param schoolId    学校ID
     * @param recipeData  食谱数据
     * @return 是否成功
     */
    public boolean saveRecipe(String schoolId, RecipeData recipeData) {
        if (schoolId == null || recipeData == null || recipeData.getContent() == null) {
            return false;
        }

        String content = serializeRecipeContent(recipeData);
        Map<String, Object> metadata = buildRecipeMetadata(recipeData);

        boolean success = vectorStrategy.saveUserMemory(schoolId, content, metadata);

        if (success) {
            RecipeMemory memory = new RecipeMemory(schoolId, recipeData, metadata);
            addToSchoolCache(schoolId, memory);
            log.info("保存食谱记忆成功: schoolId={}, dateRange={}, score={}",
                    schoolId, recipeData.getDateRange(), recipeData.getAvgScore());
        }

        return success;
    }

    /**
     * 语义搜索食谱
     *
     * @param schoolId 学校ID
     * @param query    查询文本（如"上周食谱"、"低油低盐食谱"）
     * @param topK     返回数量
     * @return 相关食谱列表
     */
    public List<RecipeMemory> searchRecipes(String schoolId, String query, int topK) {
        List<SearchResult> results = vectorStrategy.searchSimilarMemory(query, schoolId, topK);

        List<RecipeMemory> memories = new ArrayList<>();
        for (SearchResult result : results) {
            RecipeMemory memory = convertToRecipeMemory(schoolId, result);
            if (memory != null) {
                memories.add(memory);
            }
        }

        log.debug("搜索食谱: schoolId={}, query={}, results={}", schoolId, query, memories.size());
        return memories;
    }

    /**
     * 获取最近 N 周的食谱（避免重复）
     *
     * @param schoolId 学校ID
     * @param weeks    周数
     * @return 食谱列表
     */
    public List<RecipeMemory> getRecentRecipes(String schoolId, int weeks) {
        String query = "最近 " + weeks + " 周的食谱";
        return searchRecipes(schoolId, query, weeks * 2);
    }

    /**
     * 获取指定日期范围的食谱
     *
     * @param schoolId  学校ID
     * @param startDate 开始日期
     * @param endDate   结束日期
     * @return 食谱列表
     */
    public List<RecipeMemory> getRecipesByDateRange(String schoolId, LocalDate startDate, LocalDate endDate) {
        String query = startDate.format(DateTimeFormatter.ISO_DATE) + " 至 " + 
                       endDate.format(DateTimeFormatter.ISO_DATE) + " 食谱";
        return searchRecipes(schoolId, query, 10);
    }

    /**
     * 获取评分最高的食谱
     *
     * @param schoolId 学校ID
     * @param topK     返回数量
     * @return 高分食谱列表
     */
    public List<RecipeMemory> getTopScoredRecipes(String schoolId, int topK) {
        String query = "高分优质食谱";
        List<RecipeMemory> recipes = searchRecipes(schoolId, query, topK * 2);

        return recipes.stream()
                .filter(r -> r.getRecipeData() != null && r.getRecipeData().getAvgScore() != null)
                .sorted((a, b) -> Double.compare(
                        b.getRecipeData().getAvgScore(),
                        a.getRecipeData().getAvgScore()))
                .limit(topK)
                .collect(Collectors.toList());
    }

    /**
     * 检测食谱是否与近期食谱重复
     *
     * @param schoolId       学校ID
     * @param recipeContent  食谱内容
     * @param similarityThreshold 相似度阈值（0-1）
     * @return 是否重复
     */
    public boolean isDuplicateRecipe(String schoolId, String recipeContent, double similarityThreshold) {
        List<RecipeMemory> recentRecipes = getRecentRecipes(schoolId, 2);

        for (RecipeMemory recent : recentRecipes) {
            double similarity = calculateSimilarity(recipeContent, recent.getContent());
            if (similarity > similarityThreshold) {
                log.info("检测到相似食谱: similarity={}, previousDate={}", 
                        similarity, recent.getRecipeData().getDateRange());
                return true;
            }
        }

        return false;
    }

    /**
     * 生成食谱上下文 Prompt（用于 Agent）
     *
     * @param schoolId 学校ID
     * @return 上下文 Prompt
     */
    public String buildRecipeContextPrompt(String schoolId) {
        StringBuilder context = new StringBuilder();

        // 获取最近 2 周食谱
        List<RecipeMemory> recentRecipes = getRecentRecipes(schoolId, 2);
        if (!recentRecipes.isEmpty()) {
            context.append("【近期食谱记录】（请避免重复）\n");
            for (RecipeMemory recipe : recentRecipes) {
                RecipeData data = recipe.getRecipeData();
                context.append("- ").append(data.getDateRange());
                if (data.getAvgScore() != null) {
                    context.append(" (评分: ").append(String.format("%.1f", data.getAvgScore())).append(")");
                }
                context.append("\n");
            }
        }

        // 获取高分食谱参考
        List<RecipeMemory> topRecipes = getTopScoredRecipes(schoolId, 3);
        if (!topRecipes.isEmpty()) {
            context.append("\n【高分食谱参考】\n");
            for (RecipeMemory recipe : topRecipes) {
                RecipeData data = recipe.getRecipeData();
                context.append("- ").append(data.getDateRange())
                       .append(" 评分: ").append(String.format("%.1f", data.getAvgScore()))
                       .append(" 特点: ").append(data.getHighlight() != null ? data.getHighlight() : "均衡营养")
                       .append("\n");
            }
        }

        return context.toString();
    }

    /**
     * 获取学校食谱统计
     *
     * @param schoolId 学校ID
     * @return 统计信息
     */
    public RecipeStats getRecipeStats(String schoolId) {
        List<RecipeMemory> recentRecipes = getRecentRecipes(schoolId, 4);

        double avgScore = recentRecipes.stream()
                .filter(r -> r.getRecipeData() != null && r.getRecipeData().getAvgScore() != null)
                .mapToDouble(r -> r.getRecipeData().getAvgScore())
                .average()
                .orElse(0.0);

        int totalRecipes = recentRecipes.size();

        return new RecipeStats(schoolId, totalRecipes, avgScore);
    }

    // ==================== 私有方法 ====================

    /**
     * 序列化食谱内容为 JSON 字符串
     *
     * @param data 食谱数据
     * @return JSON 字符串，序列化失败时返回原始内容
     */
    private String serializeRecipeContent(RecipeData data) {
        try {
            Map<String, Object> content = new HashMap<>();
            content.put("dateRange", data.getDateRange());
            content.put("meals", data.getMeals());
            content.put("content", data.getContent());
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            log.warn("序列化食谱内容失败", e);
            return data.getContent();
        }
    }

    /**
     * 构建食谱元数据
     *
     * @param data 食谱数据
     * @return 包含记忆类型、保存时间、评分等信息的元数据 Map
     */
    private Map<String, Object> buildRecipeMetadata(RecipeData data) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("memoryType", "RECIPE");
        metadata.put("savedAt", System.currentTimeMillis());
        metadata.put("dateRange", data.getDateRange());
        metadata.put("avgScore", data.getAvgScore());
        metadata.put("targetGroup", data.getTargetGroup());
        metadata.put("weekNumber", data.getWeekNumber());
        metadata.put("year", data.getYear());
        metadata.put("highlight", data.getHighlight());
        return metadata;
    }

    /**
     * 将向量搜索结果转换为食谱记忆对象
     *
     * @param schoolId 学校ID
     * @param result   向量搜索结果
     * @return 食谱记忆对象，转换失败返回 null
     */
    private RecipeMemory convertToRecipeMemory(String schoolId, SearchResult result) {
        try {
            RecipeData recipeData = new RecipeData();
            
            if (result.getMetadata() != null) {
                recipeData.setDateRange((String) result.getMetadata().get("dateRange"));
                recipeData.setTargetGroup((String) result.getMetadata().get("targetGroup"));
                
                Object score = result.getMetadata().get("avgScore");
                if (score instanceof Number) {
                    recipeData.setAvgScore(((Number) score).doubleValue());
                }
                
                recipeData.setWeekNumber((Integer) result.getMetadata().get("weekNumber"));
                recipeData.setYear((Integer) result.getMetadata().get("year"));
                recipeData.setHighlight((String) result.getMetadata().get("highlight"));
            }

            recipeData.setContent(result.getContent());

            return new RecipeMemory(schoolId, recipeData, result.getMetadata());
        } catch (Exception e) {
            log.warn("转换食谱记忆失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将食谱记忆添加到学校本地缓存，超过上限时移除最早的记录
     *
     * @param schoolId 学校ID
     * @param memory   食谱记忆
     */
    private void addToSchoolCache(String schoolId, RecipeMemory memory) {
        List<RecipeMemory> memories = schoolRecipeCache.computeIfAbsent(schoolId, k -> new ArrayList<>());
        memories.add(0, memory);

        if (memories.size() > MAX_RECENT_RECIPES) {
            memories.remove(memories.size() - 1);
        }
    }

    /**
     * 计算两段文本的相似度（Jaccard 系数）
     *
     * @param text1 第一段文本
     * @param text2 第二段文本
     * @return 相似度值（0-1），任一文本为 null 时返回 0.0
     */
    private double calculateSimilarity(String text1, String text2) {
        // 简单的文本相似度计算（可替换为向量相似度）
        if (text1 == null || text2 == null) return 0.0;
        
        Set<String> words1 = Arrays.stream(text1.split("[\\s,，。、]+"))
                .filter(w -> w.length() > 1)
                .collect(Collectors.toSet());
        Set<String> words2 = Arrays.stream(text2.split("[\\s,，。、]+"))
                .filter(w -> w.length() > 1)
                .collect(Collectors.toSet());

        if (words1.isEmpty() || words2.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(words1);
        intersection.retainAll(words2);

        Set<String> union = new HashSet<>(words1);
        union.addAll(words2);

        return (double) intersection.size() / union.size();
    }

    // ==================== 数据类 ====================

    /**
     * 食谱数据
     */
    @Data
    public static class RecipeData {
        /** 日期范围（如 "2024-01-15 至 2024-01-19"） */
        private String dateRange;
        /** 食谱内容（JSON 或文本） */
        private String content;
        /** 餐次安排 */
        private List<Map<String, Object>> meals;
        /** 平均评分 */
        private Double avgScore;
        /** 目标人群 */
        private String targetGroup;
        /** 周数 */
        private Integer weekNumber;
        /** 年份 */
        private Integer year;
        /** 亮点/特色 */
        private String highlight;
    }

    /**
     * 食谱记忆
     */
    @Data
    public static class RecipeMemory {
        /** 学校ID */
        private String schoolId;
        /** 食谱数据 */
        private RecipeData recipeData;
        /** 元数据 */
        private Map<String, Object> metadata;
        /** 相似度分数 */
        private double score;
        /** 创建时间戳 */
        private Long createdAt;

        /**
         * 构造方法
         *
         * @param schoolId   学校ID
         * @param recipeData 食谱数据
         * @param metadata   元数据
         */
        public RecipeMemory(String schoolId, RecipeData recipeData, Map<String, Object> metadata) {
            this.schoolId = schoolId;
            this.recipeData = recipeData;
            this.metadata = metadata;
            this.createdAt = System.currentTimeMillis();
        }

        /**
         * 获取食谱内容文本
         *
         * @return 食谱内容字符串，无数据时返回 null
         */
        public String getContent() {
            return recipeData != null ? recipeData.getContent() : null;
        }
    }

    /**
     * 食谱统计
     */
    @Data
    public static class RecipeStats {
        /** 学校ID */
        private String schoolId;
        /** 食谱总数 */
        private int totalRecipes;
        /** 平均评分 */
        private double avgScore;

        /**
         * 构造方法
         *
         * @param schoolId     学校ID
         * @param totalRecipes 食谱总数
         * @param avgScore     平均评分
         */
        public RecipeStats(String schoolId, int totalRecipes, double avgScore) {
            this.schoolId = schoolId;
            this.totalRecipes = totalRecipes;
            this.avgScore = avgScore;
        }
    }
}
