package io.yunxi.platform.framework.prompt;

/**
 * 场景检测结果
 *
 * <p>
 * 携带场景名称字符串。场景标识统一为 String，不再依赖枚举。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public record SceneDetectionResult(String sceneName) {

    /** 通用场景常量 */
    private static final SceneDetectionResult GENERAL = new SceneDetectionResult("GENERAL");

    /**
     * 创建结果
     */
    public static SceneDetectionResult of(String sceneName) {
        if (sceneName == null || sceneName.isEmpty()) {
            return GENERAL;
        }
        return new SceneDetectionResult(sceneName);
    }

    /**
     * 通用场景快捷方法
     */
    public static SceneDetectionResult general() {
        return GENERAL;
    }

    /**
     * 是否为通用场景
     */
    public boolean isGeneral() {
        return "GENERAL".equals(sceneName);
    }
}
