package io.yunxi.platform.aistio.orchestration;

import java.util.Set;

/**
 * 已注册实体目录：用于 E3 校验（编排图节点引用的 agentId / teamRef / subrun revision 须在本环境已注册）。
 * <p>服务端不校验引用存在性，此项只能本地拦。默认实现为空集合（放行），
 * 由运维通过配置提供白名单，依赖服务端导入期校验兜底。</p>
 */
public interface EntityRegistry {

    boolean isRegistered(String entityId);

    default Set<String> registeredIds() {
        return java.util.Collections.emptySet();
    }
}
