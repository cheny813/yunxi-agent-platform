package io.yunxi.platform.intent.reload;

import io.yunxi.platform.intent.domain.ReloadResult;

/**
 * 意图域热更新监听器（预留扩展点）。
 *
 * <p>对接 Nacos / Spring Cloud Config 等配置中心刷新事件，或内部文件轮询
 * 发现变更后，业务方可注册监听器接收每次 reload 结果。
 * 默认无实现，Spring 注入空集合安全。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@FunctionalInterface
public interface IntentReloadListener {

    /** 每次 reload（单域或全量中的每个域）完成后回调 */
    void onReload(ReloadResult result);
}
