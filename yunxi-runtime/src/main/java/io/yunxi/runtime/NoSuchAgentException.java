package io.yunxi.runtime;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 运行时平面。当 {@link AgentCommand} 引用的 agent id 尚未注册（或当前租户无权取用）时抛出。
 * 经 {@code @ResponseStatus(NOT_FOUND)} 收口为 HTTP 404，供 A2A / AG-UI 等同步或异常冒泡路径直接结算为 NO_AGENT。
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class NoSuchAgentException extends RuntimeException {

    public NoSuchAgentException(String agentId) {
        super("no such agent: " + agentId);
    }
}
