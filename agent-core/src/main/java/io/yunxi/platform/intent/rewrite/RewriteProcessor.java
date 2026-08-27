package io.yunxi.platform.intent.rewrite;

import java.util.List;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.intent.Entity;
import io.yunxi.platform.intent.domain.DomainRuntime;

/**
 * 问题改写处理器 SPI。实现注册为 Spring Bean 后，
 * 由 {@code DefaultIntentEngine} 按 {@code yunxi.intent.rewrite-processors} 配置的名称顺序调用。
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public interface RewriteProcessor {

    /** 处理器名（与配置 rewrite-processors 列表中的名称对应） */
    String name();

    /**
     * 处理改写。
     *
     * @param query 当前改写中文本
     * @param ctx   改写上下文
     * @return 改写后文本；返回 null 表示本处理器不修改（沿用输入）
     */
    String process(String query, RewriteContext ctx);

    /** 改写上下文（携带当前域运行时，处理器从快照取数据） */
    record RewriteContext(List<Msg> recentMessages, List<Entity> entities, DomainRuntime runtime) {
    }
}
