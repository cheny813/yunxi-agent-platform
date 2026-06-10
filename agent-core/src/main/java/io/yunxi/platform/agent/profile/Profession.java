package io.yunxi.platform.agent.profile;

/**
 * 职业类型常量
 * <p>职业标识统一为 String，由 {@link ProfessionRegistry} 作为唯一数据源管理。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public final class Profession {

    public static final String OTHER = "OTHER";

    private Profession() {}

    public static boolean isIdentified(String profession) {
        return profession != null && !profession.isEmpty() && !OTHER.equals(profession);
    }
}
