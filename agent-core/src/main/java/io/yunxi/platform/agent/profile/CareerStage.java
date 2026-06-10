package io.yunxi.platform.agent.profile;

/**
 * 职业发展阶段常量
 *
 * <p>阶段标识统一为 String，不再依赖枚举，可自由扩展。
 * 所有阶段元数据（名称、年限范围）由 {@link CareerStageRegistry} 管理。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public final class CareerStage {

    public static final String TRAINEE = "TRAINEE";
    public static final String JUNIOR = "JUNIOR";
    public static final String MIDDLE = "MIDDLE";
    public static final String SENIOR = "SENIOR";
    public static final String EXPERT = "EXPERT";

    private CareerStage() {}

    public static boolean isIdentified(String stage) {
        return stage != null && !stage.isEmpty();
    }
}
