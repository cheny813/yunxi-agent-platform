package io.yunxi.fabric.aistio;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * aistio 集成共享生命周期闸门。
 *
 * <p>应用优雅下线（{@code ContextClosedEvent}）时置为不接受新 AgentTask，已进行的对话流自然结束；
 * 与框架原生行为正交，是 yunxi 产品平面在控制面任务接活路径上唯一叠加的产品关切。</p>
 */
@Component
public class AistioLifecycle {

    private final AtomicBoolean accepting = new AtomicBoolean(true);

    /** 是否仍接受新的 AgentTask（默认 true，优雅下线时置 false）。 */
    public boolean isAccepting() {
        return accepting.get();
    }

    /** 优雅下线：置为不再接受新任务，已进行中的对话流自然结束。 */
    public void stopAccepting() {
        accepting.set(false);
    }
}
