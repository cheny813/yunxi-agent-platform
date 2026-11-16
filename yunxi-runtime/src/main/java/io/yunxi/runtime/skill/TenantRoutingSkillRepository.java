package io.yunxi.runtime.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.yunxi.runtime.tenant.TenantContext;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 技能租户路由仓库：把请求期的租户维度（{@link TenantContext}，五协议入口 admit 后绑定）
 * 映射为框架原生技能 scope（namespace），把读写委托到对应租户的后端仓库实例。
 *
 * <p>框架契约（AgentSkillRepository javadoc）：每个后端实例只管一个 scope，scope 由后端
 * 原生句柄表达（JDBC 即 namespace 列）；多 scope 组合推荐"每 scope 一实例"。而 yunxi 的
 * agent 布建按 agentId 全量静态注册（无租户维度），租户只在请求期存在——本适配层即
 * "请求期选择 scope"的组合器：按租户惰性构造后端实例并缓存，每个方法调用解析当前租户
 * 后路由。
 *
 * <p>fail-open 语义：无租户上下文（MUSE 后台演进等无请求场景）或租户 ID 含框架
 * namespace 非法字符（框架构造器会抛 IllegalArgumentException）时，落默认 scope，
 * 与既有默认 namespace 数据保持兼容，绝不因租户维度缺失拒绝读写。
 *
 * <p>团队 Git 与内置 classpath 层不参与本路由（平台级共享资产），由装配层各自挂载。
 */
public final class TenantRoutingSkillRepository implements AgentSkillRepository {

    private static final Logger log = LoggerFactory.getLogger(TenantRoutingSkillRepository.class);

    /**
     * 默认 scope：与框架 {@code JdbcAgentSkillRepository} 的默认 namespace 一致，
     * 保证路由引入前写入的技能在无租户上下文时仍可见。
     */
    public static final String DEFAULT_NAMESPACE = "default";

    /** 框架 namespace 合法字符集（JdbcAgentSkillRepository 校验口径的镜像）。 */
    private static final int MAX_NAMESPACE_LENGTH = 64;

    private final Function<String, AgentSkillRepository> backendFactory;
    private final ConcurrentHashMap<String, AgentSkillRepository> backends = new ConcurrentHashMap<>();
    private final String defaultNamespace;
    private volatile boolean writeable = true;

    public TenantRoutingSkillRepository(Function<String, AgentSkillRepository> backendFactory) {
        this(backendFactory, DEFAULT_NAMESPACE);
    }

    public TenantRoutingSkillRepository(
            Function<String, AgentSkillRepository> backendFactory, String defaultNamespace) {
        this.backendFactory = java.util.Objects.requireNonNull(backendFactory, "backendFactory");
        this.defaultNamespace = java.util.Objects.requireNonNull(defaultNamespace, "defaultNamespace");
    }

    @Override
    public AgentSkill getSkill(String name) {
        return resolveBackend().getSkill(name);
    }

    @Override
    public List<String> getAllSkillNames() {
        return resolveBackend().getAllSkillNames();
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return resolveBackend().getAllSkills();
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        return resolveBackend().save(skills, force);
    }

    @Override
    public boolean delete(String skillName) {
        return resolveBackend().delete(skillName);
    }

    @Override
    public boolean skillExists(String skillName) {
        return resolveBackend().skillExists(skillName);
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return resolveBackend().getRepositoryInfo();
    }

    @Override
    public String getSource() {
        return resolveBackend().getSource();
    }

    @Override
    public void setWriteable(boolean writeable) {
        this.writeable = writeable;
        for (AgentSkillRepository backend : backends.values()) {
            backend.setWriteable(writeable);
        }
    }

    @Override
    public boolean isWriteable() {
        return writeable;
    }

    @Override
    public void close() {
        for (AgentSkillRepository backend : backends.values()) {
            try {
                backend.close();
            } catch (RuntimeException e) {
                log.warn("[skill] 租户技能仓库关闭失败: {}", e.getMessage());
            }
        }
        backends.clear();
    }

    /**
     * 解析当前租户并返回对应 scope 的后端仓库：无租户上下文或租户 ID 不满足框架
     * namespace 校验（[A-Za-z0-9._-]，长度 ≤64，非 "."/".."）时落默认 scope。
     */
    private AgentSkillRepository resolveBackend() {
        String tenant = TenantContext.current();
        String namespace = (tenant == null || tenant.isBlank()) ? defaultNamespace : tenant;
        if (!isValidNamespace(namespace)) {
            log.warn("[skill] 租户 ID 含非法 namespace 字符，路由退回默认 scope: {}", namespace);
            namespace = defaultNamespace;
        }
        return backends.computeIfAbsent(namespace, backendFactory);
    }

    private static boolean isValidNamespace(String namespace) {
        if (namespace.isEmpty() || namespace.length() > MAX_NAMESPACE_LENGTH
                || ".".equals(namespace) || "..".equals(namespace)) {
            return false;
        }
        for (int i = 0; i < namespace.length(); i++) {
            char c = namespace.charAt(i);
            if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9')
                    && c != '.' && c != '_' && c != '-') {
                return false;
            }
        }
        return true;
    }
}
