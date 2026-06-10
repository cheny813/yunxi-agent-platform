package io.yunxi.platform.agent.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent 工作空间初始化器。
 *
 * <p>
 * 在 Agent 首次创建时初始化工作空间目录结构，创建 AGENTS.md 和 knowledge/ 目录等。
 * 工作空间路径格式：{@code .agentscope/workspace/<agentName>/}
 * </p>
 *
 * <pre>
 * workspace/
 * ├── AGENTS.md              — Agent 描述文件
 * ├── MEMORY.md              — 记忆存储（由 HarnessAgent 内置管理）
 * ├── knowledge/             — 知识文件目录
 * │   └── KNOWLEDGE.md       — 知识索引文件
 * ├── memory/                — 记忆持久化目录（由 memoryFlushManager 内部使用）
 * ├── skills/                — 技能目录
 * └── subagents/             — 子 Agent 定义（由 subagentsHook 内部使用）
 * </pre>
 */
@Component
public class AgentWorkspaceInitializer {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(AgentWorkspaceInitializer.class);

    /**
     * 初始化 Agent 工作空间目录结构。
     *
     * <p>
     * 创建工作空间下的所有子目录和默认文件：
     * - 根目录、knowledge/、memory/、skills/、subagents/ 目录
     * - AGENTS.md：Agent 描述文件，包含名称、系统提示词和可用工具说明
     * - knowledge/KNOWLEDGE.md：知识索引文件
     * 已存在的文件不会被覆盖。
     * </p>
     *
     * @param agentName        Agent 名称
     * @param agentDisplayName Agent 显示名称
     * @param sysPrompt        Agent 系统提示词（用于生成 AGENTS.md 描述文件）
     * @param workspacePath    工作空间路径，如
     *                         {@code .agentscope/workspace/nutrition-assistant}
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

            // 创建 AGENTS.md — Agent 描述文件
            Path agentsMd = root.resolve("AGENTS.md");
            if (Files.notExists(agentsMd)) {
                String agentsContent = generateAgentsMd(agentName, agentDisplayName, sysPrompt);
                Files.writeString(agentsMd, agentsContent);
                log.info("生成 AGENTS.md: {}", agentsMd);
            }

            // 创建 KNOWLEDGE.md — 知识索引文件
            Path knowledgeMd = root.resolve("knowledge/KNOWLEDGE.md");
            if (Files.notExists(knowledgeMd)) {
                String knowledgeContent = generateKnowledgeMd(agentName);
                Files.writeString(knowledgeMd, knowledgeContent);
                log.info("生成 KNOWLEDGE.md: {}", knowledgeMd);
            }

            log.info("工作空间初始化完成: {} (path={})", agentName, workspacePath);

        } catch (IOException e) {
            log.warn("工作空间初始化失败: {} - {}", workspacePath, e.getMessage());
        }
    }

    /**
     * 创建目录，如果目录不存在则递归创建。
     *
     * @param dir 目录路径
     * @throws IOException 创建目录失败时抛出
     */
    private void createDir(Path dir) throws IOException {
        if (Files.notExists(dir)) {
            Files.createDirectories(dir);
        }
    }

    /**
     * 生成 AGENTS.md 文件内容。
     *
     * <p>
     * AGENTS.md 作为 Agent 的元数据文件，包含名称、系统提示词和可用工具列表。
     * WorkspaceContextHook 在 call() 时会自动读取此文件内容注入到上下文中。
     * </p>
     *
     * @param agentName   Agent 名称
     * @param displayName Agent 显示名称
     * @param sysPrompt   系统提示词
     * @return AGENTS.md 文件内容
     */
    private String generateAgentsMd(String agentName, String displayName, String sysPrompt) {
        return "---\n"
                + "id: " + agentName + "\n"
                + "name: " + (displayName != null ? displayName : agentName) + "\n"
                + "---\n"
                + "\n"
                + "## 描述\n"
                + "\n"
                + "我是" + (displayName != null ? displayName : agentName) + "。\n"
                + "\n"
                + "## 系统提示词\n"
                + "\n"
                + sysPrompt + "\n"
                + "\n"
                + "## 工作空间可用资源\n"
                + "\n"
                + "- 函数 `read_file` 读取 knowledge/ 目录下的知识文件\n"
                + "- 函数 `memory_search` 搜索记忆内容\n"
                + "- 函数 `agent_spawn` 创建子 Agent 并执行任务\n"
                + "- 请勿修改 AGENTS.md 和 MEMORY.md 文件\n"
                + "\n";
    }

    /**
     * 生成 KNOWLEDGE.md 文件内容。
     *
     * <p>
     * KNOWLEDGE.md 作为知识索引文件，提供知识文件放置说明和格式要求。
     * </p>
     *
     * @param agentName Agent 名称
     * @return KNOWLEDGE.md 文件内容
     */
    private String generateKnowledgeMd(String agentName) {
        return "# " + agentName + " 知识库\n"
                + "\n"
                + "请将 Agent 需要引用的知识文件放在此目录中。\n"
                + "支持格式：md, .txt, .json, .csv, .pdf\n"
                + "\n"
                + "## 文件列表\n"
                + "\n"
                + "（请在此处列出知识文件说明）\n"
                + "\n";
    }
}
