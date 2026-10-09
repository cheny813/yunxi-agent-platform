package io.yunxi.platform.agent.runtime;

import io.agentscope.core.agent.RuntimeContext;

/**
 * RuntimeContext 身份注入单一归属。
 *
 * <p>全平台 userId 注入逻辑（空判断 + builder.userId）原本散落在多个协议入口，新增身份字段须改多处。
 * 本类把「是否注入 userId」这一判定收敛为唯一实现；各入口仍从自身协议来源取 userId 后委托本方法，
 * 协议专属元数据（如会话标识、AG-UI 适配器优先级）仍由各入口自行补齐，不在此消解。</p>
 */
public final class RuntimeContextIdentityEnricher {

    private RuntimeContextIdentityEnricher() {
    }

    /**
     * 将 userId 注入运行时上下文构造器；userId 为空或空白时不注入，避免写入空身份。
     *
     * @param builder 运行时上下文构造器（不可为 null）
     * @param userId  用户标识，可为 null 或空白
     */
    public static void applyUserId(RuntimeContext.Builder builder, String userId) {
        if (userId != null && !userId.isBlank()) {
            builder.userId(userId);
        }
    }
}
