package io.yunxi.platform.framework.metrics;

import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent Metrics Service - Production-grade metrics collection
 * Provides counters, timers, gauges for monitoring agent performance
 */
@Slf4j
@Service
public class AgentMetricsService {

    private final MeterRegistry meterRegistry;

    // Counters
    private final Counter conversationStartedCounter;
    private final Counter conversationCompletedCounter;
    private final Counter conversationFailedCounter;
    private final Counter toolExecutionCounter;
    private final Counter toolExecutionFailedCounter;
    private final Counter llmInvocationCounter;
    private final Counter llmInvocationFailedCounter;
    private final Counter requestCounter;
    private final Counter requestFailedCounter;

    // Timers
    private final Timer conversationDurationTimer;
    private final Timer toolExecutionTimer;
    private final Timer llmInvocationTimer;
    private final Timer requestDurationTimer;

    // Gauges
    private final AtomicInteger activeConversations = new AtomicInteger(0);
    private final AtomicInteger activeToolExecutions = new AtomicInteger(0);
    private final AtomicInteger activeSessions = new AtomicInteger(0);
    private final AtomicInteger memoryHitCount = new AtomicInteger(0);
    private final AtomicInteger memoryTotalCount = new AtomicInteger(0);

    public AgentMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // Initialize counters
        this.conversationStartedCounter = Counter.builder("agent.conversation.started")
                .description("Total number of conversations started")
                .register(meterRegistry);

        this.conversationCompletedCounter = Counter.builder("agent.conversation.completed")
                .description("Total number of conversations completed successfully")
                .register(meterRegistry);

        this.conversationFailedCounter = Counter.builder("agent.conversation.failed")
                .description("Total number of conversations failed")
                .register(meterRegistry);

        this.toolExecutionCounter = Counter.builder("agent.tool.execution")
                .description("Total number of tool executions")
                .register(meterRegistry);

        this.toolExecutionFailedCounter = Counter.builder("agent.tool.execution.failed")
                .description("Total number of failed tool executions")
                .register(meterRegistry);

        this.llmInvocationCounter = Counter.builder("agent.llm.invocation")
                .description("Total number of LLM invocations")
                .register(meterRegistry);

        this.llmInvocationFailedCounter = Counter.builder("agent.llm.invocation.failed")
                .description("Total number of failed LLM invocations")
                .register(meterRegistry);

        this.requestCounter = Counter.builder("agent.request.count")
                .description("Total number of agent requests")
                .register(meterRegistry);

        this.requestFailedCounter = Counter.builder("agent.request.failed")
                .description("Total number of failed agent requests")
                .register(meterRegistry);

        // Initialize timers
        this.conversationDurationTimer = Timer.builder("agent.conversation.duration")
                .description("Conversation duration in milliseconds")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        this.toolExecutionTimer = Timer.builder("agent.tool.execution.duration")
                .description("Tool execution duration in milliseconds")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        this.llmInvocationTimer = Timer.builder("agent.llm.invocation.duration")
                .description("LLM invocation duration in milliseconds")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        this.requestDurationTimer = Timer.builder("agent.request.duration")
                .description("Agent request duration in milliseconds")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        // Initialize gauges
        Gauge.builder("agent.conversation.active", activeConversations, AtomicInteger::get)
                .description("Number of active conversations")
                .register(meterRegistry);

        Gauge.builder("agent.tool.execution.active", activeToolExecutions, AtomicInteger::get)
                .description("Number of active tool executions")
                .register(meterRegistry);

        Gauge.builder("agent.session.count", activeSessions, AtomicInteger::get)
                .description("Number of active sessions")
                .register(meterRegistry);

        Gauge.builder("agent.memory.hit.ratio", this, AgentMetricsService::calculateMemoryHitRatio)
                .description("Memory cache hit ratio (0.0-1.0)")
                .register(meterRegistry);

        log.info("AgentMetricsService initialized");
    }

    /**
     * Record conversation started
     */
    public void recordConversationStarted() {
        conversationStartedCounter.increment();
        activeConversations.incrementAndGet();
    }

    /**
     * Record conversation completed
     */
    public void recordConversationCompleted(Duration duration) {
        conversationCompletedCounter.increment();
        activeConversations.decrementAndGet();
        conversationDurationTimer.record(duration);
    }

    /**
     * Record conversation failed
     */
    public void recordConversationFailed() {
        conversationFailedCounter.increment();
        activeConversations.decrementAndGet();
    }

    /**
     * Record tool execution
     */
    public Timer.Sample startToolExecution() {
        toolExecutionCounter.increment();
        activeToolExecutions.incrementAndGet();
        return Timer.start(meterRegistry);
    }

    /**
     * Record tool execution completed
     */
    public void recordToolExecutionCompleted(Timer.Sample sample) {
        activeToolExecutions.decrementAndGet();
        sample.stop(toolExecutionTimer);
    }

    /**
     * Record tool execution failed
     */
    public void recordToolExecutionFailed(Timer.Sample sample) {
        toolExecutionFailedCounter.increment();
        activeToolExecutions.decrementAndGet();
        sample.stop(toolExecutionTimer);
    }

    /**
     * Record LLM invocation
     */
    public Timer.Sample startLlmInvocation() {
        llmInvocationCounter.increment();
        return Timer.start(meterRegistry);
    }

    /**
     * Record LLM invocation completed
     */
    public void recordLlmInvocationCompleted(Timer.Sample sample) {
        sample.stop(llmInvocationTimer);
    }

    /**
     * Record LLM invocation failed
     */
    public void recordLlmInvocationFailed(Timer.Sample sample) {
        llmInvocationFailedCounter.increment();
        sample.stop(llmInvocationTimer);
    }

    /**
     * Create a custom counter
     */
    public Counter createCounter(String name, String description, String... tags) {
        return Counter.builder(name)
                .description(description)
                .tags(tags)
                .register(meterRegistry);
    }

    /**
     * Create a custom timer
     */
    public Timer createTimer(String name, String description, String... tags) {
        return Timer.builder(name)
                .description(description)
                .publishPercentiles(0.5, 0.95, 0.99)
                .tags(tags)
                .register(meterRegistry);
    }

    /**
     * Create a custom gauge
     */
    public <T extends Number> void createGauge(String name, String description, T number) {
        Gauge.builder(name, number, Number::doubleValue)
                .description(description)
                .register(meterRegistry);
    }

    /**
     * Record agent request started
     */
    public Timer.Sample startRequest() {
        requestCounter.increment();
        return Timer.start(meterRegistry);
    }

    /**
     * Record agent request completed
     */
    public void recordRequestCompleted(Timer.Sample sample) {
        sample.stop(requestDurationTimer);
    }

    /**
     * Record agent request failed
     */
    public void recordRequestFailed(Timer.Sample sample) {
        requestFailedCounter.increment();
        sample.stop(requestDurationTimer);
    }

    /**
     * Record session created
     */
    public void recordSessionCreated() {
        activeSessions.incrementAndGet();
    }

    /**
     * Record session destroyed
     */
    public void recordSessionDestroyed() {
        activeSessions.decrementAndGet();
    }

    /**
     * Record memory hit
     */
    public void recordMemoryHit() {
        memoryHitCount.incrementAndGet();
        memoryTotalCount.incrementAndGet();
    }

    /**
     * Record memory miss
     */
    public void recordMemoryMiss() {
        memoryTotalCount.incrementAndGet();
    }

    /**
     * Calculate memory hit ratio
     */
    private double calculateMemoryHitRatio() {
        int total = memoryTotalCount.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) memoryHitCount.get() / total;
    }
}
