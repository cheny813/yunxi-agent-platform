package io.yunxi.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.extensions.judge.jev.JevClient;
import io.yunxi.capability.ModelResolver;
import io.yunxi.config.ConfigCenter;
import io.yunxi.observability.AuditSink;
import io.yunxi.observability.MetricRegistry;
import io.yunxi.runtime.TenantAgentAllocationService;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 智能平面。意图引擎装配唯一点：新增解析器 / 判定器只需在此接线，不改其他模块。
 * 策略由 {@code yunxi.intent.strategy} 决定；{@code enabled=false} 时不创建管线（等价旧默认行为）。
 */
@Configuration
@EnableConfigurationProperties(IntentProperties.class)
public class IntentEngineConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(IntentEngineConfiguration.class);

    /** 归一化器。 */
    @Bean
    @ConditionalOnMissingBean
    public IntentTextNormalizer intentTextNormalizer() {
        return new IntentTextNormalizer();
    }

    /** 槽位抽取器（内置槽位 + 配置覆盖）。 */
    @Bean
    @ConditionalOnMissingBean
    public SlotExtractor slotExtractor(IntentProperties properties) {
        return new SlotExtractor(properties.getSlots(), Clock.systemDefaultZone());
    }

    /** 候选提供者：ConfigCenter 目录 ∩ 租户允许集合。 */
    @Bean
    @ConditionalOnMissingBean(AgentCandidateProvider.class)
    public AgentCandidateProvider configCenterAgentCandidateProvider(ConfigCenter configCenter,
            TenantAgentAllocationService allocationService) {
        return new ConfigCenterAgentCandidateProvider(configCenter, allocationService);
    }

    /** 本地决策模型分类器（默认实现；impl=jev 时不装配，引擎降级为关键词）。 */
    @Bean
    @ConditionalOnMissingBean(IntentClassifier.class)
    @ConditionalOnProperty(name = "yunxi.intent.classifier.impl", havingValue = "local", matchIfMissing = true)
    public IntentClassifier localDecisionIntentClassifier(IntentProperties properties,
            ObjectProvider<ModelResolver> resolverProvider,
            ObjectProvider<ObjectMapper> mapperProvider) {
        return newLocalClassifier(properties, resolverProvider, mapperProvider);
    }

    /**
     * JEV 云端判定分类器（可选增强，impl=jev 时装配）：复用框架 JEV 闭集契约做类型化语义判定。
     * 云端 Key（TYPESAFE_API_KEY / JEV_API_KEY）缺失或客户端装配失败时回落本地决策模型实现，
     * 保持启动安全与三级降级链路完整。
     */
    @Bean
    @ConditionalOnMissingBean(IntentClassifier.class)
    @ConditionalOnProperty(name = "yunxi.intent.classifier.impl", havingValue = "jev")
    public IntentClassifier jevIntentClassifier(IntentProperties properties,
            ObjectProvider<ModelResolver> resolverProvider,
            ObjectProvider<ObjectMapper> mapperProvider) {
        LocalDecisionIntentClassifier local = newLocalClassifier(properties, resolverProvider, mapperProvider);
        try {
            JevClient client = JevClient.builder()
                    .timeout(Duration.ofMillis(properties.getClassifier().getTimeoutMillis()))
                    .build();
            return new JevIntentClassifier(client, local);
        } catch (IllegalArgumentException e) {
            LOG.warn("classifier.impl=jev 但云端 API Key 未配置（TYPESAFE_API_KEY / JEV_API_KEY），回落本地决策模型");
            return local;
        }
    }

    private LocalDecisionIntentClassifier newLocalClassifier(IntentProperties properties,
            ObjectProvider<ModelResolver> resolverProvider,
            ObjectProvider<ObjectMapper> mapperProvider) {
        return new LocalDecisionIntentClassifier(
                resolverProvider.getIfAvailable(() -> ModelRegistry::resolve),
                properties.getClassifier().getModelId(),
                properties.getClassifier().getTimeoutMillis(),
                properties.getClassifier().getMaxTokens(),
                properties.getMaxInputChars(),
                properties.getClassifier().isStructuredOutput(),
                mapperProvider.getIfAvailable(ObjectMapper::new));
    }

    /** 意图引擎：按策略装配管线。 */
    @Bean
    @ConditionalOnMissingBean(IntentEngine.class)
    public IntentEngine intentEngine(IntentProperties properties,
            IntentTextNormalizer normalizer,
            SlotExtractor slotExtractor,
            AgentCandidateProvider candidateProvider,
            ObjectProvider<IntentClassifier> classifierProvider,
            ObjectProvider<MetricRegistry> metricsProvider,
            ObjectProvider<AuditSink> auditSinkProvider) {
        if (!properties.isEnabled()) {
            return request -> IntentResolution.fallback(properties.getDefaultAgentId());
        }
        KeywordIntentResolver keywordResolver = new KeywordIntentResolver(
                properties.getRules(), properties.getTargets(), properties.getDefaultAgentId(), normalizer);
        boolean keywordOnly = "keyword".equalsIgnoreCase(properties.getStrategy());
        IntentClassifier classifier = keywordOnly ? null : classifierProvider.getIfAvailable();
        return new HybridIntentEngine(
                normalizer,
                slotExtractor,
                candidateProvider,
                keywordResolver,
                classifier,
                properties,
                metricsProvider.getIfAvailable(),
                auditSinkProvider.getIfAvailable());
    }
}
