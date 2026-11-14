package io.yunxi.intelligence.muse.service;

/**
 * MUSE 技能管理能力的统一接入网关接口（yunxi 产品平面侧契约）。
 *
 * <p>原 agent-muse 的 MuseSkillController 直接暴露 REST 端点，违反「外部 HTTP 入口
 * 必须集中到统一接入网关」的架构不变量。收编后，能力以本接口形式定义在智能平面
 * （yunxi 平台唯一 MUSE API 边界），由 yunxi-intelligence 提供实现、由统一接入网关的
 * 控制器委托暴露。网关层只依赖本接口，不依赖 muse 内部类型，保持模块解耦、无环。</p>
 */
public interface MuseSkillAdminService {

    /** 触发内建技能从 classpath 重新落地到磁盘。 */
    MuseSkillCopyResult reloadBuiltinSkills();

    /** 读取 MUSE 当前配置视图。 */
    MuseSkillConfigView getConfig();

    /** 内建技能落地结果。 */
    record MuseSkillCopyResult(int count, String targetDir, String error) {
    }

    /** MUSE 配置视图。 */
    record MuseSkillConfigView(boolean enabled, String builtinExportDir, boolean builtinCopyOnStart) {
    }
}
