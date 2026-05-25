package io.yunxi.platform.infra.config;

import io.agentscope.core.rag.Knowledge;
import io.yunxi.platform.framework.knowledge.KnowledgeCreator;
import io.yunxi.platform.infra.config.AgentscopeExtensionProperties.KnowledgeBaseConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 知识库自动配置类
 *
 * <p>
 * 根据 {@code agentscope.yml} 中的 {@code agentscope.extensions.knowledge-bases} 配置，
 * 自动创建并注册 Knowledge Bean 到 Spring 容器。
 * 每种知识库类型通过 {@link KnowledgeCreator} SPI 接口扩展。
 * </p>
 *
 * <h3>工作原理</h3>
 * <ol>
 *   <li>收集所有 {@link KnowledgeCreator} Bean（通过 Spring 组件扫描）</li>
 *   <li>构建 type → creator 映射</li>
 *   <li>遍历 {@code knowledge-bases} 配置，对每个 {@code enabled=true} 的配置项：</li>
 *   <li>调用对应 creator 创建 Knowledge 实例</li>
 *   <li>通过 {@link DefaultListableBeanFactory#registerSingleton} 注册为 Spring Bean</li>
 * </ol>
 *
 * <h3>扩展方式</h3>
 * <p>
 * 新增知识库类型只需：
 * </p>
 * <ol>
 *   <li>在 {@code agentscope.yml} 中添加配置模板</li>
 *   <li>在 {@link KnowledgeBaseConfig} 中添加专有字段（若需要）</li>
 *   <li>新建 {@code @Component implements KnowledgeCreator}</li>
 * </ol>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 * @see KnowledgeCreator
 * @see AgentscopeExtensionProperties
 */
@Slf4j
@Configuration
@ConditionalOnProperty(
        prefix = "agentscope.extensions",
        name = "autoConfigEnabled",
        havingValue = "true"
)
public class KnowledgeAutoConfiguration {

    /** 扩展功能配置属性 */
    private final AgentscopeExtensionProperties extensionConfig;

    /** Spring Bean 工厂（用于动态注册 Bean） */
    private final DefaultListableBeanFactory beanFactory;

    /** type → creator 映射 */
    private final Map<String, KnowledgeCreator> creatorMap;

    /** kebab-case 转 camelCase 的正则 */
    private static final Pattern KEBAB_PATTERN = Pattern.compile("-(\\w)");

    public KnowledgeAutoConfiguration(
            AgentscopeExtensionProperties extensionConfig,
            DefaultListableBeanFactory beanFactory,
            List<KnowledgeCreator> creators) {
        this.extensionConfig = extensionConfig;
        this.beanFactory = beanFactory;
        this.creatorMap = creators.stream()
                .collect(Collectors.toMap(
                        KnowledgeCreator::getType,
                        Function.identity(),
                        (a, b) -> {
                            log.warn("知识库类型 '{}' 存在多个 Creator，保留: {}，忽略: {}",
                                    a.getType(), a.getClass().getName(), b.getClass().getName());
                            return a;
                        }
                ));
        log.info("知识库创建器已加载: types={}", creatorMap.keySet());
    }

    /**
     * 注册知识库 Bean
     * <p>
     * 遍历 {@code knowledge-bases} 配置，对每个 {@code enabled=true} 的配置项，
     * 使用对应的 {@link KnowledgeCreator} 创建实例并注册为 Spring Bean。
     * </p>
     */
    @PostConstruct
    public void registerKnowledgeBeans() {
        Map<String, KnowledgeBaseConfig> knowledgeBases = extensionConfig.getKnowledgeBases();
        if (knowledgeBases == null || knowledgeBases.isEmpty()) {
            log.info("未配置知识库，跳过自动注册");
            return;
        }

        int registeredCount = 0;
        for (Map.Entry<String, KnowledgeBaseConfig> entry : knowledgeBases.entrySet()) {
            String configKey = entry.getKey();
            KnowledgeBaseConfig config = entry.getValue();

            if (!config.isEnabled()) {
                log.debug("知识库已禁用: {} (type={})", configKey, config.getType());
                continue;
            }

            try {
                registerKnowledgeBean(configKey, config);
                registeredCount++;
            } catch (Exception e) {
                log.error("注册知识库失败: {} (type={})", configKey, config.getType(), e);
            }
        }

        log.info("知识库自动注册完成: 成功 {} 个", registeredCount);
    }

    /**
     * 注册单个知识库 Bean
     */
    private void registerKnowledgeBean(String configKey, KnowledgeBaseConfig config) {
        String type = config.getType();
        if (type == null || type.isBlank()) {
            log.warn("知识库 '{}' 未指定 type，跳过注册", configKey);
            return;
        }

        KnowledgeCreator creator = creatorMap.get(type);
        if (creator == null) {
            log.warn("未找到知识库类型 '{}' 的创建器，可用类型: {}",
                    type, creatorMap.keySet());
            return;
        }

        Knowledge knowledge = creator.create(config);
        String beanName = toCamelCase(configKey);

        // 检查是否已存在同名的 Bean
        if (beanFactory.containsSingleton(beanName)) {
            log.warn("Bean '{}' 已存在，将被覆盖", beanName);
        }

        beanFactory.registerSingleton(beanName, knowledge);
        log.info("已自动注册知识库 Bean: {} (type={}, configKey={})",
                beanName, type, configKey);
    }

    /**
     * 将 kebab-case 转换为 camelCase
     * <p>
     * 例如：{@code tech-docs} → {@code techDocs}，{@code product-manual} → {@code productManual}
     * </p>
     *
     * @param kebabCase kebab-case 格式的字符串
     * @return camelCase 格式的字符串
     */
    static String toCamelCase(String kebabCase) {
        if (kebabCase == null || kebabCase.isEmpty()) {
            return kebabCase;
        }
        Matcher matcher = KEBAB_PATTERN.matcher(kebabCase);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, matcher.group(1).toUpperCase());
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}