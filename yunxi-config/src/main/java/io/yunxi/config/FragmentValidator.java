package io.yunxi.config;

/**
 * 配置平面。在 {@link ConfigFragment} 进入配置中心前对其进行校验。
 */
public interface FragmentValidator {
    /** 校验片段合法；不合法时抛 {@link IllegalArgumentException}。 */
    void validate(ConfigFragment fragment);
}
