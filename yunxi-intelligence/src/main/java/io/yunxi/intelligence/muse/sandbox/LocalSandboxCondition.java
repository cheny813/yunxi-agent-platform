package io.yunxi.intelligence.muse.sandbox;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 本机子进程沙箱激活条件：与 {@link DockerSandboxCondition} 互斥，仅当 Docker 未激活
 * （standalone 且无 muse-docker profile）时激活，保证单机 / 开发零配置可启动。
 */
public class LocalSandboxCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !DockerSandboxCondition.dockerActive(context);
    }
}
