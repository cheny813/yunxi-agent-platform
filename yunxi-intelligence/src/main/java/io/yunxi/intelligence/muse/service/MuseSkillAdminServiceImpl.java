package io.yunxi.intelligence.muse.service;

import io.yunxi.intelligence.muse.bootstrap.BuiltinSkillLoader;
import io.yunxi.intelligence.muse.config.EvolutionConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * {@link MuseSkillAdminService} 的 MUSE 侧实现。
 *
 * <p>仅委托底层 {@link BuiltinSkillLoader} 与 {@link EvolutionConfig}，不含任何 REST 语义；
 * REST 暴露由统一接入网关（yunxi-ingress）的控制器负责。仅当 yunxi.muse.enabled=true 时注册，
 * 与网关侧控制器的 {@code @ConditionalOnProperty} 同源，避免 muse 关闭时注入失败。</p>
 */
@Service
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true", matchIfMissing = true)
public class MuseSkillAdminServiceImpl implements MuseSkillAdminService {

    private final BuiltinSkillLoader builtinSkillLoader;
    private final EvolutionConfig config;

    public MuseSkillAdminServiceImpl(BuiltinSkillLoader builtinSkillLoader, EvolutionConfig config) {
        this.builtinSkillLoader = builtinSkillLoader;
        this.config = config;
    }

    @Override
    public MuseSkillCopyResult reloadBuiltinSkills() {
        BuiltinSkillLoader.BuiltinCopyResult result = builtinSkillLoader.copyBuiltinSkills();
        return new MuseSkillCopyResult(result.count(), result.targetDir(), result.error());
    }

    @Override
    public MuseSkillConfigView getConfig() {
        return new MuseSkillConfigView(
                config.isEnabled(),
                config.getBuiltinExportDir(),
                config.isBuiltinCopyOnStart());
    }
}
