package io.yunxi.platform.execution.interceptors;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.spi.ExecutionInterceptor;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.entity.ChatLogEntity;
import io.yunxi.platform.shared.mapper.ConversationMapper;

/**
 * 通用请求审计拦截器（order=500，默认关闭）。
 *
 * <p>补齐通用请求审计（此前仅覆盖节点命令审计），
 * pre + post 收尾实现，不订阅事件流：</p>
 * <ul>
 *   <li>preHandle：生成 requestId（UUID）写入 ctx 供日志关联；记录开始时间；</li>
 *   <li>postHandle：计算耗时，读取引擎写入的 {@code executionError} 判定成败，
 *       落库 agent_chat_logs 表（conversationId/agentName/userId/userMessage/duration/success/error）。</li>
 * </ul>
 *
 * <p>开关：{@code agentscope.core.audit.enabled}（默认 false，开启不改变既有行为）。
 * 落库异常被隔离（postHandle 异常由链隔离捕获），不影响请求主流程。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class AuditInterceptor implements ExecutionInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);

    /** ctx attribute：审计请求 ID */
    public static final String ATTR_REQUEST_ID = "audit.requestId";
    /** ctx attribute：审计开始时间（毫秒） */
    public static final String ATTR_START_TIME = "audit.startTime";
    /** 错误信息最大落库长度（对齐 agent_chat_logs.error_message VARCHAR(500)） */
    private static final int MAX_ERROR_LENGTH = 500;

    private final AgentscopeCoreProperties properties;
    private final ConversationMapper conversationMapper;

    public AuditInterceptor(AgentscopeCoreProperties properties,
                            ConversationMapper conversationMapper) {
        this.properties = properties;
        this.conversationMapper = conversationMapper;
    }

    @Override
    public int getOrder() {
        return 500;
    }

    @Override
    public void preHandle(ExecutionContext ctx) {
        if (!properties.getAudit().isEnabled()) {
            return;
        }
        ctx.setAttribute(ATTR_REQUEST_ID, UUID.randomUUID().toString().replace("-", ""));
        ctx.setAttribute(ATTR_START_TIME, System.currentTimeMillis());
    }

    @Override
    public void postHandle(ExecutionContext ctx) {
        if (!properties.getAudit().isEnabled()) {
            return;
        }
        Object startTimeObj = ctx.getAttribute(ATTR_START_TIME);
        if (!(startTimeObj instanceof Long startTime)) {
            // pre 未执行（前置拦截器失败链中断），无审计起始点，跳过落库
            return;
        }
        long durationMs = System.currentTimeMillis() - startTime;
        String errorMessage = ctx.getExecutionError();
        boolean success = errorMessage == null;
        String requestId = (String) ctx.getAttribute(ATTR_REQUEST_ID);
        try {
            ChatLogEntity entity = new ChatLogEntity();
            entity.setConversationId(ctx.getConversationId());
            entity.setAgentName(ctx.getAgentName());
            entity.setUserId(ctx.getUserId());
            entity.setUserMessage(ctx.getRequest().getMessage());
            entity.setDurationMs(durationMs);
            entity.setSuccess(success);
            entity.setErrorMessage(truncate(errorMessage));
            conversationMapper.insertChatLog(entity);
            if (log.isDebugEnabled()) {
                log.debug("审计落库: requestId={}, agent={}, success={}, durationMs={}",
                        requestId, ctx.getAgentName(), success, durationMs);
            }
        } catch (Exception e) {
            // 审计是旁路能力：落库失败仅告警，不影响请求主流程
            log.warn("审计落库失败: requestId={}, agent={}: {}",
                    requestId, ctx.getAgentName(), e.getMessage());
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH);
    }
}
