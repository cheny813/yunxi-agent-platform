package io.yunxi.platform.framework.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent 工作区初始化器
 * <p>
 * 在 Agent 构建时自动初始化工作区目录结构，创建 AGENTS.md、knowledge/ 等文件。
 * 工作区目录：{@code .agentscope/workspace/<agentName>/}
 * </p>
 *
 * <pre>
 * workspace/
 * ├── AGENTS.md              ← Agent 身份描述
 * ├── MEMORY.md              ← 整理过的长期记忆（HarnessAgent 自动管理）
 * ├── knowledge/             ← 领域知识文件
 * │   └── KNOWLEDGE.md       ← 领域知识入口
 * ├── memory/                ← 每日记忆台账（MemoryFlushManager 自动写入）
 * ├── skills/                ← 技能定义
 * └── subagents/             ← 子代理声明（SubagentsHook 自动发现）
 * </pre>
 */
@Component
public class AgentWorkspaceInitializer {

    private static final Logger log = LoggerFactory.getLogger(AgentWorkspaceInitializer.class);

    /**
     * 初始化 Agent 工作区目录结构
     *
     * @param agentName     Agent 名称
     * @param agentDisplayName Agent 展示名称
     * @param sysPrompt     Agent 系统提示词（用于生成 AGENTS.md 身份描述）
     * @param workspacePath 工作区根路径（如 {@code .agentscope/workspace/nutrition-assistant}）
     */
    public void initialize(String agentName, String agentDisplayName, String sysPrompt, String workspacePath) {
        try {
            Path root = Paths.get(workspacePath);

            // 创建目录结构
            createDir(root);
            createDir(root.resolve("knowledge"));
            createDir(root.resolve("memory"));
            createDir(root.resolve("skills"));
            createDir(root.resolve("subagents"));

            // 创建 AGENTS.md — Agent 身份描述
            Path agentsMd = root.resolve("AGENTS.md");
            if (Files.notExists(agentsMd)) {
                String agentsContent = generateAgentsMd(agentName, agentDisplayName, sysPrompt);
                Files.writeString(agentsMd, agentsContent);
                log.info("已创建 AGENTS.md: {}", agentsMd);
            }

            // 创建 KNOWLEDGE.md — 领域知识入口
            Path knowledgeMd = root.resolve("knowledge/KNOWLEDGE.md");
            if (Files.notExists(knowledgeMd)) {
                String knowledgeContent = generateKnowledgeMd(agentName);
                Files.writeString(knowledgeMd, knowledgeContent);
                log.info("已创建 KNOWLEDGE.md: {}", knowledgeMd);
            }

            log.info("工作区初始化完成: {} (path={})", agentName, workspacePath);

        } catch (IOException e) {
            log.warn("工作区初始化失败: {} - {}", workspacePath, e.getMessage());
        }
    }

    private void createDir(Path dir) throws IOException {
        if (Files.notExists(dir)) {
            Files.createDirectories(dir);
        }
    }

    /**
     * 生成 AGENTS.md
     * <p>
     * AGENTS.md 包含 Agent 的身份描述、行为约定和工作区使用指南。
     * WorkspaceContextHook 在每次 call() 时将其注入系统消息。
     * </p>
     */
    private String generateAgentsMd(String agentName, String displayName, String sysPrompt) {
        return "---\n"
                + "id: " + agentName + "\n"
                + "name: " + (displayName != null ? displayName : agentName) + "\n"
                + "---\n"
                + "\n"
                + "## 身份\n"
                + "\n"
                + "你是 " + (displayName != null ? displayName : agentName) + "。\n"
                + "\n"
                + "## 核心指令\n"
                + "\n"
                + sysPrompt + "\n"
                + "\n"
                + "## 工作区使用指南\n"
                + "\n"
                + "- 使用 `read_file` 读取 knowledge/ 下的领域知识文件\n"
                + "- 使用 `memory_search` 查询历史记忆\n"
                + "- 使用 `agent_spawn` 创建子代理执行特定任务\n"
                + "- 不要修改 AGENTS.md 或 MEMORY.md 文件\n"
                + "\n";
    }

    /**
     * 生成 KNOWLEDGE.md
     */
    private String generateKnowledgeMd(String agentName) {
        return "# " + agentName + " 领域知识\n"
                + "\n"
                + "在此目录下放置 Agent 工作时需要参考的领域知识文件。\n"
                + "支持的文件格式：.md, .txt, .json, .csv, .pdf\n"
                + "\n"
                + "## 文件索引\n"
                + "\n"
                + "（在此添加文件链接和说明）\n"
                + "\n";
    }
}