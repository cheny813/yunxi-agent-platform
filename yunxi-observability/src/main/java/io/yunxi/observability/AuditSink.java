package io.yunxi.observability;

/**
 * 可观测平面。{@link AuditEvent} 的排出口。
 */
public interface AuditSink {
    /** 发出一条审计事件（结构化、不携带推理语义）。 */
    void emit(AuditEvent event);
}
