package io.yunxi.platform.channel;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IM 渠道运行时状态注册表（yunxi 产品平面侧唯一真相源）。
 *
 * <p>ChannelAutoConfiguration 在 ApplicationRunner 阶段填充启用渠道的注册/启动状态，
 * 供 actuator 端点（/api/actuator/channels）与监控查询，不依赖底层框架内部 ChannelManager 的私有结构。</p>
 */
@Component
public class ChannelStatusRegistry {

    private final Map<String, ChannelStatus> statuses = new ConcurrentHashMap<>();

    /** 记录一个已注册的渠道（尚未启动）。 */
    public void register(String channelId, String type, String defaultAgentId) {
        statuses.put(channelId, new ChannelStatus(channelId, type, defaultAgentId, false));
    }

    /** 标记渠道已启动。 */
    public void markStarted(String channelId) {
        statuses.computeIfPresent(channelId, (k, v) ->
                new ChannelStatus(v.channelId(), v.type(), v.defaultAgentId(), true));
    }

    public Map<String, ChannelStatus> snapshot() {
        return Map.copyOf(statuses);
    }

    public record ChannelStatus(String channelId, String type, String defaultAgentId, boolean running) {
    }
}
