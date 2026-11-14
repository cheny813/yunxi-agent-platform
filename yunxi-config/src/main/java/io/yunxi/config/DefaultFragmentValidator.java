package io.yunxi.config;

/**
 * 配置平面。{@link ConfigFragment} 的结构校验器：id / kind / content 必须齐全。
 */
public class DefaultFragmentValidator implements FragmentValidator {

    /** 校验 id / kind 非空非空白、content 非 null；任一不满足抛 {@link IllegalArgumentException}。 */
    @Override
    public void validate(ConfigFragment fragment) {
        if (fragment == null) {
            throw new IllegalArgumentException("fragment is null");
        }
        if (fragment.id() == null || fragment.id().isBlank()) {
            throw new IllegalArgumentException("fragment.id is required");
        }
        if (fragment.kind() == null || fragment.kind().isBlank()) {
            throw new IllegalArgumentException("fragment.kind is required");
        }
        if (fragment.content() == null) {
            throw new IllegalArgumentException("fragment.content is required");
        }
    }
}
