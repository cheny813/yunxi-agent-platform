package io.yunxi.platform.agent.middleware;

import java.util.List;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;

/**
 * 调用级上下文在 {@code RuntimeContext} 中的存放约定。
 *
 * <p>装配期无法获知单次调用的参数（历史条数、检索范围等），因此由调用方在构造
 * {@code RuntimeContext} 时按调用维度注入，中间件在钩子内读取。存放的是调用参数，
 * 不承载推理语义。</p>
 *
 * @author yunxi-agent-platform
 */
public final class CallContextKeys {

    /**
     * 本次调用需要前置注入的历史消息条数上限。
     */
    public static final String HISTORY_LIMIT_KEY = "yunxi.call.historyLimit";

    /**
     * 本次调用需要前置注入的历史消息。
     */
    public static final String HISTORY_MESSAGES_KEY = "yunxi.call.historyMessages";

    /**
     * 本次调用是否启用文件上下文检索。
     */
    public static final String FILE_CONTEXT_ENABLED_KEY = "yunxi.call.fileContextEnabled";

    /**
     * 本次调用的检索词（用户输入原文）。
     */
    public static final String FILE_CONTEXT_QUERY_KEY = "yunxi.call.fileContextQuery";

    /**
     * 审计开关：为 {@code Boolean.TRUE} 时记录本次调用的审计日志。
     */
    public static final String AUDIT_ENABLED_KEY = "yunxi.call.auditEnabled";

    private CallContextKeys() {
    }

    /**
     * 从运行时上下文读取历史条数上限。
     *
     * @param ctx 运行时上下文
     * @return 上限值；未设置或非法时返回 0（表示不裁剪）
     */
    public static int historyLimit(RuntimeContext ctx) {
        Object value = ctx != null ? ctx.get(HISTORY_LIMIT_KEY) : null;
        return value instanceof Integer limit && limit > 0 ? limit : 0;
    }

    /**
     * 从运行时上下文读取需要前置注入的历史消息。
     *
     * @param ctx 运行时上下文
     * @return 历史消息；未设置时返回空列表
     */
    @SuppressWarnings("unchecked")
    public static List<Msg> historyMessages(RuntimeContext ctx) {
        Object value = ctx != null ? ctx.get(HISTORY_MESSAGES_KEY) : null;
        if (value instanceof List<?> list) {
            List<Msg> result = new java.util.ArrayList<>();
            for (Object item : list) {
                if (item instanceof Msg msg) {
                    result.add(msg);
                }
            }
            return result;
        }
        return List.of();
    }

    /**
     * 判断本次调用是否启用文件上下文检索。
     *
     * @param ctx 运行时上下文
     * @return 启用返回 true
     */
    public static boolean fileContextEnabled(RuntimeContext ctx) {
        Object value = ctx != null ? ctx.get(FILE_CONTEXT_ENABLED_KEY) : null;
        return Boolean.TRUE.equals(value);
    }

    /**
     * 读取本次调用的检索词。
     *
     * @param ctx 运行时上下文
     * @param fallback 回退值
     * @return 检索词，未设置时返回回退值
     */
    public static String fileContextQuery(RuntimeContext ctx, String fallback) {
        Object value = ctx != null ? ctx.get(FILE_CONTEXT_QUERY_KEY) : null;
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }

    /**
     * 判断本次调用是否记录审计日志。
     *
     * @param ctx 运行时上下文
     * @return 记录返回 true
     */
    public static boolean auditEnabled(RuntimeContext ctx) {
        Object value = ctx != null ? ctx.get(AUDIT_ENABLED_KEY) : null;
        return Boolean.TRUE.equals(value);
    }

    /**
     * 把调用参数写入运行时上下文构建器。
     *
     * @param builder          运行时上下文构建器
     * @param historyMessages  需要前置注入的历史消息（可为 null 或空表示不注入）
     * @param fileContextQuery 检索词（为空表示不检索文件）
     * @param auditEnabled     是否记录审计
     * @return 传入的构建器
     */
    public static RuntimeContext.Builder apply(
            RuntimeContext.Builder builder,
            List<Msg> historyMessages,
            String fileContextQuery,
            boolean auditEnabled) {
        if (builder == null) {
            return null;
        }
        if (historyMessages != null && !historyMessages.isEmpty()) {
            builder.put(HISTORY_MESSAGES_KEY, historyMessages);
            builder.put(HISTORY_LIMIT_KEY, historyMessages.size());
        }
        if (fileContextQuery != null && !fileContextQuery.isBlank()) {
            builder.put(FILE_CONTEXT_ENABLED_KEY, Boolean.TRUE);
            builder.put(FILE_CONTEXT_QUERY_KEY, fileContextQuery);
        }
        if (auditEnabled) {
            builder.put(AUDIT_ENABLED_KEY, Boolean.TRUE);
        }
        return builder;
    }

    /**
     * 取消息列表的最后 {@code n} 条。
     *
     * @param all 全部消息
     * @param n   条数
     * @return 裁剪后的消息列表
     */
    public static List<Msg> tail(List<Msg> all, int n) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        if (all.size() <= n) {
            return new java.util.ArrayList<>(all);
        }
        return new java.util.ArrayList<>(all.subList(all.size() - n, all.size()));
    }
}
