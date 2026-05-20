package io.yunxi.platform.business.pagegen.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 页面设计服务（通用）
 * <p>
 * 管理页面设计规范和业务模板，
 * 支持多业务场景的模板加载和设计上下文组装。
 * </p>
 *
 * <h3>架构说明</h3>
 * <ul>
 *   <li><b>superdesign-guidelines</b> — 通用设计规范（布局、色彩、动画、响应式）</li>
 *   <li><b>业务模板</b> — 按业务场景加载，如 nutrition-page-templates</li>
 * </ul>
 * <p>
 * 通过 SkillBox（未来）或 MCP 工具注入设计规范到 Agent prompt。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Service
public class PageDesignService {

    private static final Logger log = LoggerFactory.getLogger(PageDesignService.class);

    /** 缓存已加载的设计指南和模板 */
    private final Map<String, String> designResources = new ConcurrentHashMap<>();

    /**
     * 业务模板配置（key=业务标识, value=classpath路径）
     * 可通过配置文件扩展
     */
    private static final Map<String, String> BUSINESS_TEMPLATES = Map.of(
            "nutrition", "prompts/nutrition-page-templates.md"
            // 扩展更多业务: "safety", "prompts/safety-page-templates.md"
    );

    /**
     * 初始化加载设计资源，包括通用设计规范和业务模板
     */
    @PostConstruct
    public void init() {
        loadResource("superdesign-guidelines", "prompts/superdesign-guidelines.md");
        BUSINESS_TEMPLATES.forEach((biz, path) -> loadResource(biz + "-templates", path));
        log.info("页面设计服务初始化完成，加载 {} 个设计资源", designResources.size());
    }

    /**
     * 获取通用 SuperDesign 设计指南
     *
     * @return 设计指南内容，不存在返回空字符串
     */
    public String getSuperDesignGuidelines() {
        return designResources.getOrDefault("superdesign-guidelines", "");
    }

    /**
     * 获取指定业务的页面模板
     *
     * @param business 业务标识（如 nutrition）
     * @return 模板内容，不存在返回空字符串
     */
    public String getBusinessTemplates(String business) {
        return designResources.getOrDefault(business + "-templates", "");
    }

    /**
     * 获取所有已加载的资源 key 列表
     *
     * @return 资源 key 集合
     */
    public java.util.Set<String> getAvailableResources() {
        return designResources.keySet();
    }

    /**
     * 组装设计上下文（供 Agent sysPrompt 注入）
     * <p>
     * 通用设计规范 + 指定业务模板（如有），截断到安全长度。
     * </p>
     *
     * @param business  业务标识（可为 null，仅加载通用规范）
     * @param maxTokens 最大字符数
     * @return 格式化的设计上下文
     */
    public String getDesignContext(String business, int maxTokens) {
        StringBuilder sb = new StringBuilder();

        sb.append("\n\n## 页面设计规范\n");
        sb.append("生成页面时请遵循以下设计规范：\n\n");

        sb.append(getSuperDesignGuidelines());

        if (business != null && !business.isBlank()) {
            String templates = getBusinessTemplates(business);
            if (!templates.isBlank()) {
                sb.append("\n\n## 业务专属模板\n");
                sb.append(templates);
            }
        }

        if (sb.length() > maxTokens) {
            log.warn("设计上下文过长 ({} > {})，截断处理", sb.length(), maxTokens);
            return sb.substring(0, maxTokens) + "\n\n...(设计规范已截断)";
        }

        return sb.toString();
    }

    /**
     * 根据页面类型获取通用模板推荐
     *
     * @param pageType 页面类型（dashboard/list/report/form）
     * @return 模板描述
     */
    public String getTemplateRecommendation(String pageType) {
        return switch (pageType.toLowerCase()) {
            case "dashboard", "仪表盘", "概览" ->
                    "使用「数据仪表盘」模板：顶部 KPI 卡片(3-4列) → 趋势图表区 → 分布图/排名 → 预警指标";
            case "list", "列表", "展示" ->
                    "使用「数据展示页」模板：筛选区 + Tab 切换 → 卡片/表格列表 → 分页";
            case "report", "报告", "统计", "分析" ->
                    "使用「统计报告页」模板：时间范围选择 → KPI 概览 → 趋势折线图 → TOP 排行 → 导出/打印";
            case "form", "表单", "管理" ->
                    "使用「管理后台」模板：表格(筛选/排序/分页) → 新增/编辑弹窗表单 → 操作确认";
            default ->
                    "根据内容自动选择合适的页面模板，遵循通用设计规范（色板、组件规范、响应式布局）";
        };
    }

    /**
     * 生成页面 HTML 骨架（通用，含 CDN 引用和 CSS 变量）
     *
     * @param title       页面标题
     * @param bodyContent 页面主体内容
     * @param brandColor  品牌主色（oklch 格式，可为 null 使用默认）
     * @return 完整 HTML 字符串
     */
    public String buildPageSkeleton(String title, String bodyContent, String brandColor) {
        String primary = (brandColor != null && !brandColor.isBlank()) ? brandColor : "oklch(0.55 0.15 250)";
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>%s</title>
                    <script src="https://cdn.tailwindcss.com"></script>
                    <script src="https://cdn.jsdelivr.net/npm/chart.js@4"></script>
                    <script src="https://unpkg.com/lucide@latest/dist/umd/lucide.min.js"></script>
                    <link rel="preconnect" href="https://fonts.googleapis.com">
                    <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700&display=swap" rel="stylesheet">
                    <style>
                        :root {
                            --primary: %s;
                            --success: oklch(0.60 0.18 145);
                            --warning: oklch(0.70 0.16 85);
                            --danger: oklch(0.55 0.22 25);
                            --bg-primary: oklch(0.985 0.002 247);
                            --bg-card: oklch(1 0 0);
                            --text-primary: oklch(0.15 0.01 247);
                            --text-secondary: oklch(0.45 0.01 247);
                            --border: oklch(0.92 0.005 247);
                            --radius: 0.75rem;
                            --shadow-sm: 0 1px 3px oklch(0 0 0 / 0.06);
                            --shadow-md: 0 4px 12px oklch(0 0 0 / 0.08);
                            --font-sans: "Plus Jakarta Sans", system-ui, sans-serif;
                        }
                        body { font-family: var(--font-sans); background: var(--bg-primary); color: var(--text-primary); }
                        .card { background: var(--bg-card); border-radius: var(--radius); box-shadow: var(--shadow-sm); padding: 1.25rem; }
                        .card:hover { box-shadow: var(--shadow-md); }
                        @media print {
                            .no-print { display: none !important; }
                            body { background: white; }
                            .card { box-shadow: none; border: 1px solid #ddd; break-inside: avoid; }
                        }
                    </style>
                </head>
                <body class="min-h-screen p-4 md:p-6">
                    %s
                    <script>lucide.createIcons();</script>
                </body>
                </html>
                """.formatted(title, primary, bodyContent);
    }

    /**
     * 从 classpath 加载设计资源文件到缓存
     *
     * @param key      资源缓存键
     * @param classpath classpath 路径
     */
    private void loadResource(String key, String classpath) {
        try {
            ClassPathResource resource = new ClassPathResource(classpath);
            try (InputStream is = resource.getInputStream()) {
                String content = StreamUtils.copyToString(is, StandardCharsets.UTF_8);
                designResources.put(key, content);
                log.info("加载设计资源: {} ({} 字符)", key, content.length());
            }
        } catch (IOException e) {
            log.warn("无法加载设计资源: {} (路径: {})", key, classpath, e);
        }
    }
}
