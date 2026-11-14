package io.yunxi.runtime;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * 测试用固定文本模型：不调用任何真实大模型，仅返回一条固定文本响应。
 * 用于在没有外部 API 依赖的前提下，驱动一次完整的 {@code call} 流程，
 * 触发运行时会话状态初始化（freshState），从而强验证构建期权限模板是否进入会话。
 */
public final class StubTextModel implements Model {

    private final String text;

    public StubTextModel(String text) {
        this.text = text;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.just(ChatResponse.builder()
                .id("stub-" + System.nanoTime())
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(1, 1, 2))
                .build());
    }

    @Override
    public String getModelName() {
        return "stub-text-model";
    }
}
