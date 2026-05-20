package io.yunxi.platform.framework.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.yunxi.platform.framework.skill.model.AutoSkillResult;
import io.yunxi.platform.framework.skill.model.SkillTemplate;
import io.yunxi.platform.framework.skill.model.SuccessPattern;
import io.yunxi.platform.framework.intelligent.config.IntelligentProperties;
import io.yunxi.platform.framework.intelligent.llm.IntelligentLlmService;
import io.yunxi.platform.shared.entity.ConversationEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 技能自动创建服务
 * <p>
 * 从成功对话中自动提取和创建技能
 * 使用LLM分析和生成技能内容
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Service
public class SkillAutoCreator {

    /** LLM 服务 */
    private final IntelligentLlmService llmService;
    /** 技能管理器 */
    private final SkillManager skillManager;
    /** 智能配置属性 */
    private final IntelligentProperties intelligentProperties;
    /** JSON 对象映射器 */
    private final ObjectMapper objectMapper;
    /** 模板填充器 */
    private final SkillTemplateFiller templateFiller;
    /** 版本管理器 */
    private final SkillVersionManager versionManager;

    /** 技能创建的置信度阈值（从配置获取，默认 0.5 以降低门槛） */
    private double minConfidenceThreshold;

    /**
     * 构造函数
     *
     * @param llmService      LLM 服务
     * @param skillManager    技能管理器
     * @param properties      智能配置属性
     * @param objectMapper    JSON 对象映射器
     * @param templateFiller  模板填充器（自动填充空缺字段）
     * @param versionManager  版本管理器（跟踪技能版本）
     */
    public SkillAutoCreator(IntelligentLlmService llmService,
            SkillManager skillManager,
            IntelligentProperties properties,
            ObjectMapper objectMapper,
            SkillTemplateFiller templateFiller,
            SkillVersionManager versionManager) {
        this.llmService = llmService;
        this.skillManager = skillManager;
        this.intelligentProperties = properties;
        this.objectMapper = objectMapper;
        this.templateFiller = templateFiller;
        this.versionManager = versionManager;
        // 从配置获取置信度阈值，默认 0.5 以降低自动创建门槛
        this.minConfidenceThreshold = properties.getSkill() != null
                ? properties.getSkill().getAutoCreatorMinConfidence()
                : 0.5;
        log.info("SkillAutoCreator 初始化完成，置信度阈值: {}", this.minConfidenceThreshold);
    }

    /**
     * 检测成功模式
     *
     * @param conversation 会话实体
     * @return 成功模式（如果检测到）
     */
    public Optional<SuccessPattern> detectSuccessPattern(ConversationEntity conversation) {
        try {
            // 1. 分析对话质量指标
            double qualityScore = assessConversationQuality(conversation);
            if (qualityScore < minConfidenceThreshold) {
                log.debug("Conversation quality below threshold: {}, score={}, threshold={}",
                        conversation.getId(), qualityScore, minConfidenceThreshold);
                return Optional.empty();
            }

            // 2. 使用LLM分析对话模式
            String analysisPrompt = buildPatternAnalysisPrompt(conversation);
            String analysisResult = llmService.generateOrDefault(
                    "你是一个对话模式分析专家，负责从成功对话中识别可复用的模式。请以JSON格式返回分析结果。",
                    analysisPrompt, "");

            // 3. 解析LLM响应，提取成功模式
            SuccessPattern pattern = parseSuccessPattern(analysisResult, conversation);
            if (pattern == null) {
                log.debug("No success pattern detected in conversation: {}", conversation.getId());
                return Optional.empty();
            }

            // 4. 评估模式置信度
            double patternConfidence = assessPatternConfidence(pattern, conversation);
            pattern.setConfidence(patternConfidence);

            if (patternConfidence >= minConfidenceThreshold) {
                log.info("Detected success pattern with confidence {} in conversation: {}",
                        patternConfidence, conversation.getId());
                return Optional.of(pattern);
            } else {
                log.debug("Pattern confidence below threshold: {}", conversation.getId());
                return Optional.empty();
            }
        } catch (Exception e) {
            log.error("Failed to detect success pattern in conversation: {}", conversation.getId(), e);
            return Optional.empty();
        }
    }

    /**
     * 从成功模式创建技能
     *
     * @param pattern 成功模式
     * @return 技能创建结果
     */
    public AutoSkillResult createSkillFromSuccess(SuccessPattern pattern) {
        return createSkillFromSuccess(pattern, null);
    }

    /**
     * 从成功模式创建技能（带对话上下文，用于模板填充）
     *
     * @param pattern      成功模式
     * @param conversation 原始对话（用于 SkillTemplateFiller 自动填充空缺字段）
     * @return 技能创建结果
     */
    public AutoSkillResult createSkillFromSuccess(SuccessPattern pattern, ConversationEntity conversation) {
        AutoSkillResult result = new AutoSkillResult();
        result.setStatus(AutoSkillResult.Status.PENDING);
        result.setCreatedAt(LocalDateTime.now());
        result.setSourcePattern(pattern);

        try {
            // 1. 检查置信度
            if (pattern.getConfidence() < minConfidenceThreshold) {
                result.setStatus(AutoSkillResult.Status.SKIPPED_LOW_CONFIDENCE);
                result.setErrorMessage("Pattern confidence below threshold");
                return result;
            }

            // 2. 检查是否已存在相似技能
            Optional<String> existingSkill = findSimilarSkill(pattern);
            if (existingSkill.isPresent()) {
                result.setStatus(AutoSkillResult.Status.SKIPPED_EXISTS);
                result.setSkillName(existingSkill.get());
                result.setErrorMessage("Similar skill already exists: " + existingSkill.get());
                return result;
            }

            // 3. 生成技能内容
            SkillTemplate skillTemplate = generateSkillContent(pattern);
            if (skillTemplate == null) {
                result.setStatus(AutoSkillResult.Status.FAILED);
                result.setErrorMessage("Failed to generate skill content");
                return result;
            }

            // 3.5 使用 SkillTemplateFiller 填充 LLM 未生成的空缺字段
            if (conversation != null) {
                templateFiller.fillTemplate(skillTemplate, conversation);
                log.debug("已使用 SkillTemplateFiller 补充模板字段: {}", skillTemplate.getName());
            }

            // 4. 创建技能
            boolean success = skillManager.createSkill(skillTemplate);
            if (!success) {
                result.setStatus(AutoSkillResult.Status.FAILED);
                result.setErrorMessage("Failed to create skill");
                return result;
            }

            // 4.5 记录技能版本（初始版本 v1）
            String versionId = versionManager.createVersion(
                    skillTemplate.getName(),
                    skillTemplate.toString(),
                    "AI 自动创建初始版本");
            versionManager.publishVersion(versionId);
            log.debug("已创建初始版本: {} -> {}", skillTemplate.getName(), versionId);

            // 5. 设置成功结果
            result.setStatus(AutoSkillResult.Status.SUCCESS);
            result.setSkillName(skillTemplate.getName());
            result.setDescription(skillTemplate.getDescription());
            result.setSkillPath(skillManager.getSkillPath(skillTemplate.getName()));
            result.setConfidence(pattern.getConfidence());

            log.info("Successfully created skill '{}' from success pattern (version: {})", skillTemplate.getName(), versionId);
            return result;

        } catch (Exception e) {
            log.error("Failed to create skill from success pattern", e);
            result.setStatus(AutoSkillResult.Status.FAILED);
            result.setErrorMessage(e.getMessage());
            return result;
        }
    }

    /**
     * 评估对话质量
     *
     * @param conversation 会话实体
     * @return 质量评分（0-1）
     */
    private double assessConversationQuality(ConversationEntity conversation) {
        double score = 0.0;

        // 1. 消息数量（20%）
        int messageCount = conversation.getMessages() != null ? conversation.getMessages().size() : 0;
        score += Math.min(messageCount / 10.0, 1.0) * 0.2;

        // 2. 用户输入质量（30%）
        double avgUserLength = conversation.getMessages().stream()
                .filter(m -> "USER".equals(m.getRole().toString()))
                .mapToDouble(m -> m.toString().length())
                .average()
                .orElse(0.0);
        score += Math.min(avgUserLength / 300.0, 1.0) * 0.3;

        // 3. 对话完整性（20%）
        boolean hasUserInput = conversation.getMessages().stream()
                .anyMatch(m -> "USER".equals(m.getRole().toString()));
        boolean hasAssistantResponse = conversation.getMessages().stream()
                .anyMatch(m -> "ASSISTANT".equals(m.getRole().toString()));
        if (hasUserInput && hasAssistantResponse) {
            score += 0.2;
        }

        // 4. 内容相关性（30%）- 简化评估
        // 在实际实现中，可以使用更复杂的语义相似度评估
        score += 0.3; // 假设内容相关

        return Math.max(0.0, Math.min(1.0, score));
    }

    /**
     * 构建模式分析提示词
     *
     * @param conversation 会话实体
     * @return 提示词
     */
    private String buildPatternAnalysisPrompt(ConversationEntity conversation) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请分析以下对话，识别其中可复用的成功模式。\n\n");
        prompt.append("请以JSON格式返回分析结果，包含以下字段：\n");
        prompt.append("- name: 模式名称\n");
        prompt.append("- description: 模式描述\n");
        prompt.append("- triggerCondition: 触发条件\n");
        prompt.append("- keySteps: 关键步骤（数组）\n");
        prompt.append("- example: 使用示例\n");
        prompt.append("- successMetric: 成功指标（0-1）\n\n");
        prompt.append("对话内容：\n");

        // 添加对话内容
        StringBuilder contentBuilder = new StringBuilder();
        int charCount = 0;
        int maxChars = intelligentProperties.getLearningLoop().getMaxSessionChars();

        for (Msg msg : conversation.getMessages()) {
            String msgText = msg.toString();
            if (charCount + msgText.length() > maxChars) {
                break;
            }
            contentBuilder.append(msgText).append("\n");
            charCount += msgText.length();
        }

        prompt.append(contentBuilder.toString());

        return prompt.toString();
    }

    /**
     * 解析成功模式
     *
     * @param analysisResult LLM分析结果
     * @param conversation   会话实体
     * @return 成功模式
     */
    private SuccessPattern parseSuccessPattern(String analysisResult, ConversationEntity conversation) {
        try {
            // 尝试从LLM响应中提取JSON
            Pattern jsonPattern = Pattern.compile("\\{[^}]*\\}", Pattern.DOTALL);
            Matcher matcher = jsonPattern.matcher(analysisResult);

            if (matcher.find()) {
                String jsonStr = matcher.group();
                SuccessPattern pattern = objectMapper.readValue(jsonStr, SuccessPattern.class);
                pattern.setId(UUID.randomUUID().toString());
                pattern.setCreatedAt(LocalDateTime.now());
                pattern.setUpdatedAt(LocalDateTime.now());
                pattern.setFrequency(1); // 初次识别
                return pattern;
            } else {
                log.warn("Failed to extract JSON from analysis result");
                return null;
            }
        } catch (Exception e) {
            log.error("Failed to parse success pattern from LLM response", e);
            return null;
        }
    }

    /**
     * 评估模式置信度
     *
     * @param pattern      成功模式
     * @param conversation 会话实体
     * @return 置信度（0-1）
     */
    private double assessPatternConfidence(SuccessPattern pattern, ConversationEntity conversation) {
        double confidence = 0.0;

        // 1. 模式完整性（40%）
        if (pattern.getName() != null && !pattern.getName().isBlank())
            confidence += 0.1;
        if (pattern.getDescription() != null && !pattern.getDescription().isBlank())
            confidence += 0.1;
        if (pattern.getKeySteps() != null && !pattern.getKeySteps().isEmpty())
            confidence += 0.1;
        if (pattern.getExample() != null && !pattern.getExample().isBlank())
            confidence += 0.1;

        // 2. 成功指标（30%）
        if (pattern.getSuccessMetric() != null) {
            confidence += pattern.getSuccessMetric() * 0.3;
        }

        // 3. 对话质量影响（30%）
        double conversationQuality = assessConversationQuality(conversation);
        confidence += conversationQuality * 0.3;

        return Math.max(0.0, Math.min(1.0, confidence));
    }

    /**
     * 查找相似技能
     *
     * @param pattern 成功模式
     * @return 相似技能名称（如果存在）
     */
    private Optional<String> findSimilarSkill(SuccessPattern pattern) {
        try {
            List<String> existingSkills = skillManager.listSkills();

            // 简单的名称相似度检查
            for (String skillName : existingSkills) {
                if (skillName.equalsIgnoreCase(pattern.getName()) ||
                        skillName.toLowerCase().contains(pattern.getName().toLowerCase()) ||
                        pattern.getName().toLowerCase().contains(skillName.toLowerCase())) {
                    return Optional.of(skillName);
                }
            }

            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to find similar skill", e);
            return Optional.empty();
        }
    }

    /**
     * 生成技能内容
     *
     * @param pattern 成功模式
     * @return 技能模板
     */
    private SkillTemplate generateSkillContent(SuccessPattern pattern) {
        try {
            String prompt = buildSkillGenerationPrompt(pattern);
            String result = llmService.generateOrDefault(
                    "你是一个技能模板生成专家，负责根据成功模式生成完整的技能模板。请以JSON格式返回技能内容。",
                    prompt, "");

            SkillTemplate template = parseSkillTemplate(result, pattern);
            if (template != null) {
                // 添加元数据
                SkillTemplate.SkillMetadata metadata = new SkillTemplate.SkillMetadata();
                metadata.setAuthor("AI自动生成");
                metadata.setVersion("1.0.0");
                metadata.setCreatedAt(LocalDateTime.now().toString());
                metadata.setTags(Arrays.asList("自动生成", pattern.getName()));
                metadata.setDifficulty("中级");
                template.setMetadata(metadata);
            }

            return template;
        } catch (Exception e) {
            log.error("Failed to generate skill content", e);
            return null;
        }
    }

    /**
     * 构建技能生成提示词
     *
     * @param pattern 成功模式
     * @return 提示词
     */
    private String buildSkillGenerationPrompt(SuccessPattern pattern) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请根据以下成功模式，生成一个完整的技能模板。\n\n");
        prompt.append("请以JSON格式返回技能内容，包含以下字段：\n");
        prompt.append("- name: 技能名称\n");
        prompt.append("- description: 技能描述\n");
        prompt.append("- category: 技能分类\n");
        prompt.append("- prerequisites: 前置条件（数组）\n");
        prompt.append("- steps: 关键步骤（数组）\n");
        prompt.append("- notes: 注意事项（数组）\n");
        prompt.append("- example: 使用示例\n");
        prompt.append("- validationMethod: 验证方法\n");
        prompt.append("- relatedSkills: 相关技能（数组）\n\n");
        prompt.append("成功模式：\n");
        prompt.append("名称: ").append(pattern.getName()).append("\n");
        prompt.append("描述: ").append(pattern.getDescription()).append("\n");
        prompt.append("触发条件: ").append(pattern.getTriggerCondition()).append("\n");
        prompt.append("关键步骤:\n");
        if (pattern.getKeySteps() != null) {
            for (String step : pattern.getKeySteps()) {
                prompt.append("  - ").append(step).append("\n");
            }
        }
        prompt.append("使用示例: ").append(pattern.getExample()).append("\n");

        return prompt.toString();
    }

    /**
     * 解析技能模板
     *
     * @param result  LLM生成结果
     * @param pattern 成功模式
     * @return 技能模板
     */
    private SkillTemplate parseSkillTemplate(String result, SuccessPattern pattern) {
        try {
            Pattern jsonPattern = Pattern.compile("\\{[^}]*\\}", Pattern.DOTALL);
            Matcher matcher = jsonPattern.matcher(result);

            if (matcher.find()) {
                String jsonStr = matcher.group();
                SkillTemplate template = objectMapper.readValue(jsonStr, SkillTemplate.class);

                // 如果LLM没有生成某些字段，使用成功模式的信息填充
                if (template.getName() == null || template.getName().isBlank()) {
                    template.setName(pattern.getName());
                }
                if (template.getDescription() == null || template.getDescription().isBlank()) {
                    template.setDescription(pattern.getDescription());
                }
                if (template.getExample() == null || template.getExample().isBlank()) {
                    template.setExample(pattern.getExample());
                }
                if (template.getSteps() == null || template.getSteps().isEmpty()) {
                    template.setSteps(pattern.getKeySteps());
                }

                return template;
            } else {
                log.warn("Failed to extract JSON from skill generation result");
                return null;
            }
        } catch (Exception e) {
            log.error("Failed to parse skill template from LLM response", e);
            return null;
        }
    }

    /**
     * 批量检测成功模式
     *
     * @param conversations 会话列表
     * @return 成功模式列表
     */
    public List<SuccessPattern> detectSuccessPatternsBatch(List<ConversationEntity> conversations) {
        return conversations.stream()
                .map(this::detectSuccessPattern)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    /**
     * 批量创建技能
     *
     * @param patterns 成功模式列表
     * @return 技能创建结果列表
     */
    public List<AutoSkillResult> createSkillsBatch(List<SuccessPattern> patterns) {
        return patterns.stream()
                .map(this::createSkillFromSuccess)
                .collect(Collectors.toList());
    }
}
