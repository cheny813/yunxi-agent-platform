package io.yunxi.platform.infra.config;

import io.yunxi.platform.framework.tool.Tool;
import io.yunxi.platform.framework.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * 工具自动注册配置
 *
 * <p>
 * 自动将所有 Spring 管理的 Tool bean 注册到 ToolRegistry
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Configuration
public class ToolAutoRegistrationConfig {

    private static final Logger log = LoggerFactory.getLogger(ToolAutoRegistrationConfig.class);

    /** 工具注册表 */
    private final ToolRegistry toolRegistry;
    /** Spring 管理的工具列表 */
    private final List<Tool> tools;

    /**
     * 构造函数
     *
     * @param toolRegistry 工具注册表
     * @param tools        Spring 管理的工具列表（可选）
     */
    public ToolAutoRegistrationConfig(ToolRegistry toolRegistry,
            List<Tool> tools) {
        this.toolRegistry = toolRegistry;
        this.tools = tools;
    }

    /**
     * 注册所有工具到工具注册表
     */
    @PostConstruct
    public void registerAllTools() {
        if (tools == null || tools.isEmpty()) {
            log.info("没有发现需要注册的工具");
            return;
        }

        log.info("开始自动注册 {} 个工具...", tools.size());
        for (Tool tool : tools) {
            try {
                toolRegistry.registerTool(tool);
                log.debug("已注册工具: {} - {}", tool.getName(), tool.getDescription());
            } catch (Exception e) {
                log.warn("注册工具失败: {}", tool.getName(), e);
            }
        }
        log.info("工具自动注册完成，共注册 {} 个工具", toolRegistry.getToolCount());
    }
}
