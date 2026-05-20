package io.yunxi.platform.framework.profile;

/**
 * 职业类型常量
 *
 * <p>
 * 职业标识统一为 String，由 {@link ProfessionRegistry} 作为唯一数据源管理。
 * 内置职业通过 application.yml 配置注册，业务层通过 ProfessionRegistry.register() 动态注册。
 * </p>
 *
 * <p>
 * 此常量类仅提供标准标识符，不再限制可用的职业类型。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public final class Profession {

    /** 未知/其他职业的标识符 */
    public static final String OTHER = "OTHER";

    private Profession() {
    } // 禁止实例化

    /**
     * 判断是否为已识别的职业（非OTHER职业标识）
     *
     * @param profession 职业标识字符串，可为null或空
     * @return 如果职业标识有效且不是OTHER职业类型，则返回true；否则返回false
     */
    public static boolean isIdentified(String profession) {
        return profession != null && !profession.isEmpty() && !OTHER.equals(profession);
    }
}
