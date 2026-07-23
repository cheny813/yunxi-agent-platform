package io.yunxi.platform.shared.dto;

/**
 * Profile 信息 DTO — 用于前端展示可用 Profile 列表
 *
 * @param name        Profile 名称（如 "chat", "business-make"）
 * @param label       展示名称（如 "智能咨询", "内容生成"）
 * @param description Profile 描述
 * @param mode        使用的模式名称（如 "chat", "tool", "expert"）
 *
 * @author yunxi-agent-platform
 */
public record ProfileInfo(
        String name,
        String label,
        String description,
        String mode) {
}