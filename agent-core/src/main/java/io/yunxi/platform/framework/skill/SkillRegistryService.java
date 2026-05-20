package io.yunxi.platform.framework.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Skill 注册中心
 *
 * <p>存储所有已加载的 Skill，并支持按需创建过滤后的 SkillBox 实例。</p>
 *
 * <p>SkillBox 底层机制：每个技能的工具注册在独立 ToolGroup（{@code skillId_skill_tools}），
 * 默认 disabled，LLM 通过 {@code load_skill_through_path} 按需激活。
 * 因此工具隔离已由 SDK 保证，本类解决的是 <b>系统提示词 token 浪费</b>问题——
 * SkillHook 会注入所有技能描述到 prompt，50 个技能 = 大量冗余 token。
 * 通过 {@code skillConfig.enabledSkills} 配置白名单，每个 Agent 只注入自己需要的技能描述。</p>
 *
 * <p>本类由 {@link SkillBoxAutoConfiguration} 创建为 Spring Bean，
 * 不使用 {@code @Service} 注解，生命周期与 SkillBox 配置绑定。</p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
public class SkillRegistryService {

    private final Map<String, AgentSkill> allSkills = new LinkedHashMap<>();
    private final SkillBoxProperties properties;

    public SkillRegistryService(SkillBoxProperties properties) {
        this.properties = properties;
    }

    /**
     * 注册一个 Skill
     */
    public void register(AgentSkill skill) {
        allSkills.put(skill.getSkillId(), skill);
    }

    /**
     * 获取所有已注册的 Skill ID
     */
    public Set<String> getAllSkillIds() {
        return Collections.unmodifiableSet(allSkills.keySet());
    }

    /**
     * 为指定 Agent 创建 SkillBox
     *
     * <p>如果 enabledSkills 为 null 或空，包含所有 Skill（向后兼容）。
     * 否则仅包含白名单中的 Skill。</p>
     *
     * @param enabledSkills 启用的 Skill ID 白名单，null 表示全部
     * @param toolkit       Agent 的 Toolkit 实例
     * @return 绑定到 toolkit 的 SkillBox
     */
    public SkillBox createSkillBox(List<String> enabledSkills, Toolkit toolkit) {
        SkillBox skillBox = new SkillBox(toolkit);

        List<AgentSkill> skills;
        if (enabledSkills == null || enabledSkills.isEmpty()) {
            skills = List.copyOf(allSkills.values());
        } else {
            skills = enabledSkills.stream()
                    .map(id -> {
                        AgentSkill s = allSkills.get(id);
                        if (s == null) {
                            log.warn("Skill '{}' not found in registry, skipping", id);
                        }
                        return s;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            if (skills.size() < enabledSkills.size()) {
                Set<String> notFound = new LinkedHashSet<>(enabledSkills);
                allSkills.keySet().forEach(notFound::remove);
                log.warn("Skills not found: {}", notFound);
            }
        }

        for (AgentSkill skill : skills) {
            skillBox.registerSkill(skill);
        }
        skillBox.registerSkillLoadTool();

        if (properties.isCodeExecutionEnabled()) {
            enableCodeExecution(skillBox);
        }

        return skillBox;
    }

    private void enableCodeExecution(SkillBox skillBox) {
        try {
            var builder = skillBox.codeExecution()
                    .withRead()
                    .withWrite();
            if (properties.getCodeExecutionWorkDir() != null && !properties.getCodeExecutionWorkDir().isBlank()) {
                builder.workDir(properties.getCodeExecutionWorkDir());
            }
            builder.enable();
            log.info("Code execution enabled for skill box");
        } catch (Exception e) {
            log.warn("Failed to enable code execution: {}", e.getMessage());
        }
    }
}
