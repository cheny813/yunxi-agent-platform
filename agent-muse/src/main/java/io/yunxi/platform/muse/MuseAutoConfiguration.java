package io.yunxi.platform.muse;

import io.agentscope.core.model.Model;
import io.yunxi.platform.agent.model.ModelFactory;
import io.yunxi.platform.muse.config.EvolutionConfig;
import io.yunxi.platform.muse.tool.SkillEvalTool;
import io.yunxi.platform.muse.tool.SkillPruneTool;
import io.yunxi.platform.muse.tool.SkillRefineTool;
import org.springframework.beans.factory.ObjectProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * MUSE 自进化引擎自动装配。
 *
 * <p>仅在 {@code yunxi.muse.enabled=true} 时生效（默认关闭），保持「薄壳」——
 * 不启用则不加载任何 MUSE 类，对框架零侵入。</p>
 *
 * <p>设计要点：除「自进化闭环使用的 LLM」需要手动构建外，其余组件
 * （SkillEvaluator / SkillRefiner / SkillPruner / SelfEvolutionEngine / 三个元工具 / MuseToolRegistrar）
 * 均为 {@code @Component}，由 {@code @ComponentScan} 自动注册，复用 yunxi 的
 * {@link ModelFactory} 获取模型。</p>
 */
@Configuration
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true")
@EnableConfigurationProperties(EvolutionConfig.class)
@ComponentScan(basePackages = "io.yunxi.platform.muse")
@RequiredArgsConstructor
public class MuseAutoConfiguration {

    private final EvolutionConfig config;

    /**
     * 自进化闭环使用的 LLM（复用 yunxi ModelFactory；若宿主已提供 Model Bean 则不覆盖）。
     * SkillRefiner(@Component) 通过构造注入拿到这个 Model。
     */
    @Bean
    @ConditionalOnMissingBean
    public Model museModel(ModelFactory modelFactory) {
        return modelFactory.create(
                new io.yunxi.platform.shared.config.AgentModelConfig(
                        config.getProvider(), null, config.getModel()));
    }

    /**
     * 暴露给宿主（AgentConfigurer）注册 MUSE 元工具组的便捷入口。
     *
     * @param evalToolProvider   SkillEvalTool 的懒加载提供者
     * @param refineToolProvider SkillRefineTool 的懒加载提供者
     * @param pruneToolProvider  SkillPruneTool 的懒加载提供者
     * @return MuseToolRegistrar 实例（仅 yunxi.muse.enabled=true 时作为 Bean 存在）
     */
    @Bean
    public MuseToolRegistrarImpl museToolRegistrar(
            ObjectProvider<SkillEvalTool> evalToolProvider,
            ObjectProvider<SkillRefineTool> refineToolProvider,
            ObjectProvider<SkillPruneTool> pruneToolProvider) {
        return new MuseToolRegistrarImpl(evalToolProvider, refineToolProvider, pruneToolProvider);
    }
}
