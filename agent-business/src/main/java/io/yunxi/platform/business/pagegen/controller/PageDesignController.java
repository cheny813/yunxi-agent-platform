package io.yunxi.platform.business.pagegen.controller;

import io.yunxi.platform.business.pagegen.service.PageDesignService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * 页面设计 API（通用）
 * <p>
 * 提供设计规范查询、业务模板推荐、页面骨架生成等通用能力。
 * 前端可直接调用，也可由 Agent 通过 MCP 工具间接使用。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@RestController
@RequestMapping("/api/page-design")
@RequiredArgsConstructor
public class PageDesignController {

    /** 页面设计服务 */
    private final PageDesignService pageDesignService;

    /**
     * 获取通用 SuperDesign 设计指南
     *
     * @return 设计指南文本
     */
    @GetMapping("/guidelines")
    public ResponseEntity<String> getGuidelines() {
        return ResponseEntity.ok(pageDesignService.getSuperDesignGuidelines());
    }

    /**
     * 获取业务页面模板
     *
     * @param business 业务标识（如 nutrition）
     * @return 业务模板文本
     */
    @GetMapping("/templates")
    public ResponseEntity<String> getTemplates(@RequestParam(defaultValue = "nutrition") String business) {
        return ResponseEntity.ok(pageDesignService.getBusinessTemplates(business));
    }

    /**
     * 获取所有已加载的设计资源列表
     *
     * @return 资源 key 集合
     */
    @GetMapping("/resources")
    public ResponseEntity<Set<String>> getAvailableResources() {
        return ResponseEntity.ok(pageDesignService.getAvailableResources());
    }

    /**
     * 获取完整设计上下文（供 Agent sysPrompt 使用）
     *
     * @param business  业务标识（可选，仅加载通用规范时省略）
     * @param maxTokens 最大字符数，默认 4000
     * @return 设计上下文文本
     */
    @GetMapping("/context")
    public ResponseEntity<String> getDesignContext(
            @RequestParam(required = false) String business,
            @RequestParam(defaultValue = "4000") int maxTokens) {
        return ResponseEntity.ok(pageDesignService.getDesignContext(business, maxTokens));
    }

    /**
     * 根据页面类型获取通用模板推荐
     *
     * @param pageType 页面类型（dashboard/list/report/form）
     * @return 模板推荐描述
     */
    @GetMapping("/recommend")
    public ResponseEntity<String> recommendTemplate(@RequestParam String pageType) {
        return ResponseEntity.ok(pageDesignService.getTemplateRecommendation(pageType));
    }

    /**
     * 生成页面 HTML 骨架
     *
     * @param title       页面标题
     * @param brandColor  品牌主色（oklch 格式，可选）
     * @param bodyContent 页面主体内容（HTML）
     * @return 完整的 HTML 页面骨架
     */
    @PostMapping(value = "/skeleton", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> buildSkeleton(
            @RequestParam String title,
            @RequestParam(required = false) String brandColor,
            @RequestBody String bodyContent) {
        String html = pageDesignService.buildPageSkeleton(title, bodyContent, brandColor);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }
}
