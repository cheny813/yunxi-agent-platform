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

    /**
     * 私有构造器，禁止实例化（常量工具类）。
     */
    private Profession() {}

    /**
     * 判断给定职业标识是否为有效职业（已识别且非 {@link #OTHER} 兜底值）。
     *
     * @param profession 职业标识字符串
     * @return 当 profession 非空、非空串且不等于 {@link #OTHER} 时返回 true
     */
    public static boolean isIdentified(String profession) {
        return profession != null && !profession.isEmpty() && !OTHER.equals(profession);
    }
}
