package io.yunxi.runtime;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * 运行时平面。主备模型提供器。包装一组有序的 {@link Model} 候选，当其中某个流式失败时，回退到下一个。
 * 这是产品平面的故障转移接缝；框架 Model 接口通过委托给当前活跃候选来实现。
 */
public class FailoverModelProvider implements Model {

    private final List<Model> candidates;

    public FailoverModelProvider(List<Model> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("at least one model candidate is required");
        }
        this.candidates = new ArrayList<>(candidates);
    }

    /**
     * 便捷工厂：把主模型与一组回退模型拼成有序候选列表，构建故障转移 provider。
     * {@code fallbacks} 为 {@code null} 时仅保留主模型。
     *
     * @param primary   主模型（首位候选）
     * @param fallbacks 有序回退候选（可为 {@code null}）
     * @return 故障转移 provider
     */
    public static FailoverModelProvider of(Model primary, List<Model> fallbacks) {
        List<Model> all = new ArrayList<>();
        all.add(primary);
        if (fallbacks != null) {
            all.addAll(fallbacks);
        }
        return new FailoverModelProvider(all);
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Flux<ChatResponse> result = candidates.get(0).stream(messages, tools, options);
        for (int i = 1; i < candidates.size(); i++) {
            Model next = candidates.get(i);
            result = result.onErrorResume(error -> next.stream(messages, tools, options));
        }
        return result;
    }

    @Override
    public String getModelName() {
        return candidates.get(0).getModelName();
    }
}
