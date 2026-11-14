package io.yunxi.runtime;

import org.springframework.security.core.Authentication;

/**
 * 运行时平面。四协议统一的身份抽取逻辑（单一真相源，避免各协议各写一套身份解析导致分叉）。
 *
 * <p>优先级：已认证的安全主体名 -> 请求头 {@code X-Yunxi-User} -> 降级为 {@code null}
 * （由调用方决定如何用，例如回退到协议层提供的 userId）。</p>
 *
 * <p>本组件是 web / Chat Completions / AG-UI 三个面向用户协议共用的唯一身份归一点；web 入口在
 * {@code ConversationController} 经本组件归一，Chat Completions 在 {@code YunxiChatCompletionsController}
 * 经本组件归一，AG-UI 在 {@code YunxiAguiRuntimeContextResolver} 经本组件归一。A2A 是机器到机器协议，
 * 无用户概念，按设计不接入（记为有意例外）。以 {@code @Bean} 形式在 {@code RuntimeConfiguration} 声明，
 * 因为 yunxi-runtime 不进入组件扫描范围。</p>
 */
public class ProtocolUserIdResolver {

    private static final String ANONYMOUS = "anonymousUser";

    /**
     * 把「认证主体 / 请求头 / 协议字段」归一成 userId。
     *
     * @param authentication 当前请求的安全主体（已认证时优先取主体名）
     * @param headerUser     请求头 {@code X-Yunxi-User} 的值（主体缺失时使用）
     * @return 归一后的 userId；两者皆无则返回 {@code null}，由调用方决定如何回退
     */
    public String resolve(Authentication authentication, String headerUser) {
        if (authentication != null && authentication.isAuthenticated()) {
            String name = authentication.getName();
            if (name != null && !name.isBlank() && !ANONYMOUS.equals(name)) {
                return name;
            }
        }
        if (headerUser != null && !headerUser.isBlank()) {
            return headerUser;
        }
        return null;
    }
}
