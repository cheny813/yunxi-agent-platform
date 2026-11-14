package io.yunxi.intelligence;

/**
 * 智能平面。意图识别结果。把一段自然语言输入归类到某个目标 Agent 或处理分支，
 * 是平台「意图路由」的最小真相单元。
 *
 * @param label 意图标签（如 order / query / fallback）
 * @param confidence 置信度 0~1
 * @param targetAgentId 路由到的目标 Agent 标识；为空表示由默认 Agent 处理
 */
public record Intent(String label, double confidence, String targetAgentId) {

    public static Intent fallback(String defaultAgentId) {
        return new Intent("fallback", 0.0d, defaultAgentId);
    }
}
