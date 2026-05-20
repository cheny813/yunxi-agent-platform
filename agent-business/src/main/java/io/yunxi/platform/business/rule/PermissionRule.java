package io.yunxi.platform.business.rule;

import io.yunxi.agent.rule.core.RuleContext;
import io.yunxi.agent.rule.core.RuleDefinition;
import io.yunxi.agent.rule.exception.RuleViolationException;
import io.yunxi.agent.rule.model.RulePriority;
import io.yunxi.agent.rule.model.RuleType;
import lombok.extern.slf4j.Slf4j;
import org.jeasy.rules.api.Facts;

import java.util.List;

/**
 * 权限检查规则
 *
 * <p>
 * 规则逻辑：
 * </p>
 * <ul>
 * <li>检查用户是否有权限执行当前任务</li>
 * <li>管理员（ADMIN角色）拥有所有权限</li>
 * <li>普通用户只能执行被授权的技能</li>
 * </ul>
 *
 * <p>
 * 迁移说明：从 agent-rule-engine 迁移至 agent-business，
 * 因为权限检查属于业务层职责。
 * </p>
 */
@Slf4j
public class PermissionRule implements RuleDefinition {

    @Override
    public String getName() {
        return "permission-check";
    }

    @Override
    public String getDescription() {
        return "检查用户是否有权限执行当前任务";
    }

    @Override
    public RuleType getType() {
        return RuleType.PRE;
    }

    @Override
    public int getPriority() {
        return RulePriority.HIGHEST.getValue();
    }

    @Override
    public boolean evaluate(Facts facts) {
        RuleContext context = RuleContext.fromFacts(facts);
        RuleContext.UserInfo user = context.getUserInfo();
        RuleContext.TaskInfo task = context.getTaskInfo();

        // 如果用户信息为空，跳过检查（由其他规则处理）
        if (user == null || task == null) {
            log.warn("用户信息或任务信息为空，跳过权限检查");
            return false;
        }

        // 管理员拥有所有权限，不触发规则
        if (user.getRoles() != null && user.getRoles().contains("ADMIN")) {
            log.debug("用户 {} 是管理员，拥有所有权限", user.getUsername());
            return false;
        }

        // 检查用户是否有该技能的权限
        String skillName = task.getSkillName();
        if (skillName == null || skillName.isEmpty()) {
            // 如果没有指定技能名称，允许执行（可能是系统内部调用）
            return false;
        }

        // 构建权限标识：skill:技能名
        String requiredPermission = "skill:" + skillName;
        List<String> permissions = user.getPermissions();

        // 如果用户没有权限列表，拒绝访问
        if (permissions == null || permissions.isEmpty()) {
            log.warn("用户 {} 没有任何权限", user.getUsername());
            return true; // 触发规则拒绝访问
        }

        // 检查是否拥有所需权限
        boolean hasPermission = permissions.contains(requiredPermission);

        log.debug("权限检查: user={}, skill={}, requiredPermission={}, hasPermission={}",
                user.getUsername(), skillName, requiredPermission, hasPermission);

        // 无权限则触发规则（返回 true）
        return !hasPermission;
    }

    @Override
    public void execute(Facts facts) {
        RuleContext context = RuleContext.fromFacts(facts);
        RuleContext.UserInfo user = context.getUserInfo();
        RuleContext.TaskInfo task = context.getTaskInfo();

        String skillName = task.getSkillName();
        String username = user.getUsername();

        String error = String.format("用户 %s 无权限执行技能 %s", username, skillName);
        context.setErrorMessage(error);

        log.warn("权限检查失败: {}", error);

        // 抛出异常阻止任务执行
        throw new RuleViolationException(getName(), error);
    }
}
