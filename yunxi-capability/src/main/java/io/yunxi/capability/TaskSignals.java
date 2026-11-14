package io.yunxi.capability;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.util.List;

/**
 * 任务信号：由请求消息与生成选项提取，供 {@link ConfigurableModelRoutingPolicy#resolveAuto} 做模型选型。
 *
 * <p>信号来源于 AgentScope-Java 框架的 {@link Msg} 内容块（{@code ImageBlock}/{@code VideoBlock}/
 * {@code AudioBlock}/{@code TextBlock}，均为 core sealed {@code ContentBlock} 的子类）与
 * {@link GenerateOptions}，不涉及任何 yunxi 私有语义。
 */
public class TaskSignals {

    /** 输入媒体类型；视频优先于图片，图片优先于纯文本。 */
    public enum MediaType {
        TEXT, IMAGE, IMAGE_TEXT, VIDEO, AUDIO
    }

    private final MediaType mediaType;
    private final int estimatedTokens;
    private final boolean toolHeavy;
    private final String userIntent;

    /** 构造任务信号（不可变快照）。 */
    public TaskSignals(MediaType mediaType, int estimatedTokens, boolean toolHeavy, String userIntent) {
        this.mediaType = mediaType;
        this.estimatedTokens = estimatedTokens;
        this.toolHeavy = toolHeavy;
        this.userIntent = userIntent;
    }

    /**
     * 从请求参数提取任务信号。
     *
     * @param messages 对话消息（媒体检测与文本长度估算来源）
     * @param tools    工具 schema（非空表示工具密集，偏好 coding 强项模型）
     * @param options  生成选项（结构化输出 / 透传意图标签来源）
     */
    public static TaskSignals from(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        MediaType media = MediaType.TEXT;
        int textChars = 0;
        boolean hasImage = false;
        boolean hasVideo = false;
        boolean hasAudio = false;
        if (messages != null) {
            for (Msg msg : messages) {
                if (msg == null) {
                    continue;
                }
                if (msg.hasContentBlocks(VideoBlock.class)) {
                    hasVideo = true;
                }
                if (msg.hasContentBlocks(ImageBlock.class)) {
                    hasImage = true;
                }
                if (msg.hasContentBlocks(AudioBlock.class)) {
                    hasAudio = true;
                }
                for (TextBlock tb : msg.getContentBlocks(TextBlock.class)) {
                    String t = tb.getText();
                    if (t != null) {
                        textChars += t.length();
                    }
                }
            }
        }
        if (hasVideo) {
            media = MediaType.VIDEO;
        } else if (hasImage) {
            media = hasAudio ? MediaType.IMAGE_TEXT : MediaType.IMAGE;
        } else if (hasAudio) {
            media = MediaType.AUDIO;
        }
        boolean toolHeavy = tools != null && !tools.isEmpty();
        String intent = null;
        if (options != null && options.getAdditionalBodyParams() != null) {
            Object v = options.getAdditionalBodyParams().get("yunxi_user_intent");
            if (v instanceof String s && !s.isBlank()) {
                intent = s;
            }
        }
        return new TaskSignals(media, textChars / 4, toolHeavy, intent);
    }

    public MediaType mediaType() {
        return mediaType;
    }

    public int estimatedTokens() {
        return estimatedTokens;
    }

    public boolean toolHeavy() {
        return toolHeavy;
    }

    public String userIntent() {
        return userIntent;
    }
}
