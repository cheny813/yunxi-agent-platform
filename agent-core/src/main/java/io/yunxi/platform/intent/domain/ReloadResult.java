package io.yunxi.platform.intent.domain;

/**
 * 热更新结果。
 *
 * @param domain  目标域名（全量 reload 时可能含多个域，此时记录首个失败域或 "all"）
 * @param version 更新后版本号（失败时保留旧版本）
 * @param success 是否成功
 * @param reason  失败原因（成功为空串）
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public record ReloadResult(String domain, long version, boolean success, String reason) {

    public static ReloadResult ok(String domain, long version) {
        return new ReloadResult(domain, version, true, "");
    }

    public static ReloadResult fail(String domain, long version, String reason) {
        return new ReloadResult(domain, version, false, reason);
    }
}
