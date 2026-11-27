package io.yunxi.a2ui;

import io.a2ui4j.core.message.A2uiEnvelope;
import io.a2ui4j.core.spec.ValidationIssue;
import java.util.List;

/**
 * A2UI 响应解析结果。
 *
 * @param a2uiBlockFound 模型文本中是否含 <a2ui-json> 哨兵块
 * @param remainingText  去掉哨兵块后的普通文本（供宿主照常呈现）
 * @param validationIssues 校验问题（空表示全部合法）
 * @param outboundMessages Agent→Renderer 方向消息（如 CreateSurface），校验全部
 *     通过时由宿主转发给渲染端；存在校验问题时为空（fail closed）
 */
public record A2uiParseResult(
        boolean a2uiBlockFound,
        String remainingText,
        List<ValidationIssue> validationIssues,
        List<A2uiEnvelope> outboundMessages) {
}
