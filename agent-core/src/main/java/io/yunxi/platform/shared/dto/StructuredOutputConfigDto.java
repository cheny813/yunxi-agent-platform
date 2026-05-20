package io.yunxi.platform.shared.dto;

import java.util.Map;

/**
 * 结构化输出配置 DTO
 *
 * <p>
 * 定义 Agent 应该输出的结构化格式。
 * 支持两种配置方式：
 * <ul>
 * <li><b>Schema 类（推荐）</b>：使用 {@code schemaClass} 字段指定 Java 类名</li>
 * <li><b>自定义 Schema</b>：使用 {@code schema} 字段指定 JSON Schema</li>
 * <li><b>多 Schema</b>：使用 {@code schemas} 字段配置多个命名 Schema</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>Schema 类示例</b> - 在 Agent 配置文件中定义：
 *
 * <pre>
 * structured_output:
 *   enabled: true
 *   schema_class: "io.yunxi.platform.shared.dto.NutritionAnalysisSchema"
 * </pre>
 * </p>
 *
 * <p>
 * <b>多 Schema 示例</b> - 在 Agent 配置文件中定义：
 *
 * <pre>
 * structured_output:
 *   enabled: true
 *   schema_class: "io.yunxi.platform.shared.dto.NutritionAnalysisSchema"  # 默认 Schema
 *   schemas:
 *     nutritionAnalysis: "io.yunxi.platform.shared.dto.NutritionAnalysisSchema"
 *     recipeGeneration: "io.yunxi.platform.shared.dto.RecipeGenerationSchema"
 *     dishScoring: "io.yunxi.platform.shared.dto.DishScoringSchema"
 * </pre>
 * </p>
 *
 * <p>
 * <b>使用示例</b>:
 *
 * <pre>
 * // 方式1：使用 Schema 类（推荐，类型安全）
 * StructuredOutputConfigDto config = new StructuredOutputConfigDto();
 * config.setSchemaClass("io.yunxi.platform.shared.dto.UserInfoSchema");
 * config.setEnabled(true);
 *
 * // 方式2：使用自定义 JSON Schema（更灵活，但更复杂）
 * StructuredOutputConfigDto config = new StructuredOutputConfigDto();
 * config.setSchema("{\"type\":\"object\",\"properties\":{...}}");
 *
 * // 方式3：使用多 Schema（一个 Agent 支持多种输出格式）
 * StructuredOutputConfigDto config = new StructuredOutputConfigDto();
 * config.setSchemaClass("io.yunxi.platform.shared.dto.DefaultSchema");
 * config.addSchema("analysis", "io.yunxi.platform.shared.dto.AnalysisSchema");
 * config.addSchema("generation", "io.yunxi.platform.shared.dto.GenerationSchema");
 * </pre>
 * </p>
 *
 * <p>
 * <b>请求时选择 Schema</b>:
 *
 * <pre>
 * // 使用默认 Schema
 * {"message": "分析营养", "structured": true}
 *
 * // 使用命名 Schema
 * {"message": "分析营养", "structured": true, "schemaName": "nutritionAnalysis"}
 * </pre>
 * </p>
 */
public class StructuredOutputConfigDto {

    /**
     * Schema 类名（完整类名，推荐使用）
     * <p>
     * 例如：io.yunxi.platform.shared.dto.NutritionAnalysisSchema
     * </p>
     * <p>
     * <b>优先级</b>：如果设置了此字段，会优先使用 Schema 类，忽略 {@code schema} 字段
     * </p>
     * <p>
     * <b>优势</b>：
     * <ul>
     * <li>类型安全：编译时检查</li>
     * <li>自动解析：AgentScope 自动生成 JSON Schema</li>
     * <li>更好性能：使用 AgentScope 内置的优化</li>
     * </ul>
     * </p>
     */
    private String schemaClass;

    /**
     * 多 Schema 配置（Key: schemaName, Value: Schema 类名）
     * <p>
     * 支持一个 Agent 配置多个 Schema，通过 schemaName 选择使用哪个 Schema。
     * </p>
     * <p>
     * <b>配置示例</b>:
     * <pre>
     * schemas:
     *   nutritionAnalysis: "io.yunxi.platform.shared.dto.NutritionAnalysisSchema"
     *   recipeGeneration: "io.yunxi.platform.shared.dto.RecipeGenerationSchema"
     * </pre>
     * </p>
     * <p>
     * <b>使用示例</b>:
     * <pre>
     * // 请求时指定 schemaName
     * {"message": "分析营养", "structured": true, "schemaName": "nutritionAnalysis"}
     * </pre>
     * </p>
     */
    private Map<String, String> schemas;

    /**
     * 输出类型定义（JSON Schema）
     * <p>
     * 例如：{"type": "object", "properties": {...}}
     * </p>
     * <p>
     * <b>注意</b>：如果设置了 {@code schemaClass}，则此字段会被忽略
     * </p>
     */
    private String schema;

    /**
     * 输出类型描述（可选）
     */
    private String description;

    /**
     * 是否启用结构化输出
     */
    private boolean enabled = false;

    public StructuredOutputConfigDto() {
    }

    public StructuredOutputConfigDto(String schema) {
        this.schema = schema;
        this.enabled = true;
    }

    /**
     * 获取 Schema 类名
     */
    public String getSchemaClass() {
        return schemaClass;
    }

    /**
     * 设置 Schema 类名（推荐使用）
     */
    public void setSchemaClass(String schemaClass) {
        this.schemaClass = schemaClass;
    }

    /**
     * 获取多 Schema 配置
     */
    public Map<String, String> getSchemas() {
        return schemas;
    }

    /**
     * 设置多 Schema 配置
     */
    public void setSchemas(Map<String, String> schemas) {
        this.schemas = schemas;
    }

    /**
     * 添加命名 Schema
     *
     * @param schemaName Schema 名称
     * @param schemaClassName Schema 类名
     */
    public void addSchema(String schemaName, String schemaClassName) {
        if (this.schemas == null) {
            this.schemas = new java.util.HashMap<>();
        }
        this.schemas.put(schemaName, schemaClassName);
    }

    /**
     * 获取指定名称的 Schema 类名
     *
     * @param schemaName Schema 名称
     * @return Schema 类名，如果不存在返回 null
     */
    public String getSchemaByName(String schemaName) {
        if (schemas == null) {
            return null;
        }
        return schemas.get(schemaName);
    }

    /**
     * 检查是否使用 Schema 类
     */
    public boolean isUsingSchemaClass() {
        return schemaClass != null && !schemaClass.isBlank();
    }

    /**
     * 检查是否配置了多 Schema
     */
    public boolean hasMultipleSchemas() {
        return schemas != null && !schemas.isEmpty();
    }

    /**
     * 检查是否有指定的 Schema
     *
     * @param schemaName Schema 名称
     * @return true 如果存在该 Schema
     */
    public boolean hasSchema(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) {
            return isUsingSchemaClass();
        }
        return schemas != null && schemas.containsKey(schemaName);
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 检查是否使用动态 JSON Schema
     */
    public boolean isUsingJsonSchema() {
        return !isUsingSchemaClass() && schema != null && !schema.isBlank();
    }
}

