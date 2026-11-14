package io.yunxi.intelligence.muse.sandbox;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 沙箱隔离选择条件：{@code yunxi.cluster.mode=cluster}（生产集群强制强隔离）或显式
 * {@code muse-docker} profile 时激活 Docker 容器沙箱；二者皆无则退化为本机子进程沙箱。
 *
 * <p>与 {@link LocalSandboxCondition} 互斥，保证两个 {@code SandboxRunner} 至多一个激活。
 * 纯配置驱动，无需改程序源码即可在单机 / 集群间切换隔离强度。</p>
 */
public class DockerSandboxCondition implements Condition {

    /** 共享判断：cluster 模式或 muse-docker profile 任一成立即激活 Docker 强隔离。 */
    public static boolean dockerActive(ConditionContext context) {
        String mode = context.getEnvironment().getProperty("yunxi.cluster.mode");
        if ("cluster".equals(mode)) {
            return true;
        }
        return context.getEnvironment().acceptsProfiles(Profiles.of("muse-docker"));
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return dockerActive(context);
    }
}
