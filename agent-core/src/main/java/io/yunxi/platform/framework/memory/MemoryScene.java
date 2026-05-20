package io.yunxi.platform.framework.memory;

/**
 * 记忆场景常量
 *
 * <p>
 * 用于区分不同的业务场景，实现场景感知的记忆管理。
 * 场景标识统一为 String，不再依赖枚举，可自由扩展。
 * 所有场景元数据（名称、描述、保留天数）由 {@link MemorySceneRegistry} 管理。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public final class MemoryScene {

    /** 个人助手场景：长期记忆，永久保留 */
    public static final String PERSONAL_ASSISTANT = "PERSONAL_ASSISTANT";

    /** 通用场景：默认场景，不长期存储 */
    public static final String GENERAL = "GENERAL";

    private MemoryScene() {
    }

    /**
     * 是否为长期保留场景
     */
    public static boolean isLongTerm(String sceneName) {
        return PERSONAL_ASSISTANT.equals(sceneName);
    }
}
