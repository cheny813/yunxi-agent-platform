package io.yunxi.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 平台 JSON 编解码单一真相源。
 *
 * <p>统一 {@link ObjectMapper} 实例，避免各组件各自 {@code new ObjectMapper()} 造成实例与配置漂移。
 * 当前采用 Jackson 默认配置（与历史 {@code new ObjectMapper()} 行为一致，零行为变更）；未来若需统一升级
 * 序列化策略（如注册模块、关闭未知属性失败），只改本类一处即可，全平台生效。</p>
 */
public final class Json {

    private Json() {
    }

    private static final ObjectMapper INSTANCE = new ObjectMapper();

    /** 返回平台共享的 {@link ObjectMapper}（线程安全，可跨组件复用）。 */
    public static ObjectMapper objectMapper() {
        return INSTANCE;
    }
}
