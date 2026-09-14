package io.yunxi.platform.aistio.reporter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 默认事件上报实现：仅记录日志，不对外发送。启用 gRPC 时替换为真正的 gRPC 上报实现。
 */
@Primary
@Component
public class LoggingAistioReporter implements AistioEventReporter {

    private static final Logger log = LoggerFactory.getLogger(LoggingAistioReporter.class);

    @Override
    public void emitEvent(String sessionId, String type, Object payload) {
        log.debug("[aistio-reporter] event session={} type={} payload={}", sessionId, type, payload);
    }

    @Override
    public void emitContext(String sessionId, String context) {
        log.debug("[aistio-reporter] context session={} len={}",
                sessionId, context != null ? context.length() : 0);
    }

    @Override
    public void emitInventory(String agentName, Object topology) {
        log.debug("[aistio-reporter] inventory agent={}", agentName);
    }
}
