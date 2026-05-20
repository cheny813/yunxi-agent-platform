package io.yunxi.platform.shared.util;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.URLSource;
import io.yunxi.platform.shared.dto.AttachmentDto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 多模态消息构建工具
 *
 * <p>
 * 本工具类提供了构建多模态消息的便捷方法，支持将文本和附件（图片、文档等）
 * 组合成 AgentScope 的 Msg 对象。
 * </p>
 *
 * <h3>支持的多模态类型：</h3>
 * <ul>
 * <li>image - 图片（JPG、PNG、GIF 等）</li>
 * <li>document - 文档（PDF、Word 等）- 视模型能力支持</li>
 * <li>audio - 音频（MP3、WAV 等）- 视模型能力支持</li>
 * <li>video - 视频（MP4 等）- 视模型能力支持</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 *
 * <pre>
 * // 构建带图片的消息
 * AttachmentDto image = AttachmentDto.imageFromUrl("https://example.com/photo.jpg");
 * Msg msg = MultimodalMessageBuilder.builder()
 *         .text("请描述这张图片")
 *         .attachment(image)
 *         .build();
 *
 * // 构建多图消息
 * Msg msg = MultimodalMessageBuilder.builder()
 *         .text("比较这两张图片的异同")
 *         .attachments(imageList)
 *         .build();
 * </pre>
 *
 * <h3>模型兼容性说明：</h3>
 * <ul>
 * <li>qwen-vl-plus/max - 支持图片理解</li>
 * <li>gpt-4-vision - 支持图片理解</li>
 * <li>claude-3 - 支持图片和文档</li>
 * <li>qwen-plus/turbo - 仅支持文本</li>
 * </ul>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public class MultimodalMessageBuilder {

    private static final Logger log = LoggerFactory.getLogger(MultimodalMessageBuilder.class);

    /**
     * 创建 Builder 实例
     *
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 快速构建纯文本消息
     *
     * @param text 文本内容
     * @return Msg 实例
     */
    public static Msg textMessage(String text) {
        return Msg.builder()
                .textContent(text)
                .build();
    }

    /**
     * 快速构建带图片的消息
     *
     * @param text     文本内容
     * @param imageUrl 图片 URL（支持 HTTP URL 或 base64 Data URL）
     * @return Msg 实例
     */
    public static Msg imageMessage(String text, String imageUrl) {
        return builder()
                .text(text)
                .attachment(AttachmentDto.imageFromUrl(imageUrl))
                .build();
    }

    /**
     * 检查模型是否支持多模态
     *
     * <p>
     * 根据模型名称判断是否支持图片、文档等多模态输入。
     * </p>
     *
     * @param modelName 模型名称
     * @return true 表示支持多模态
     */
    public static boolean isMultimodalModel(String modelName) {
        if (modelName == null) {
            return false;
        }
        String lowerName = modelName.toLowerCase();

        // 支持多模态的模型列表
        return lowerName.contains("vl") // qwen-vl-plus, qwen-vl-max
                || lowerName.contains("vision") // gpt-4-vision
                || lowerName.contains("claude-3") // claude-3-opus, claude-3-sonnet
                || lowerName.contains("gemini"); // gemini-pro-vision
    }

    /**
     * Msg 构建器
     */
    public static class Builder {
        private String text;
        private List<AttachmentDto> attachments;

        /**
         * 设置文本内容
         *
         * @param text 文本内容
         * @return Builder 实例
         */
        public Builder text(String text) {
            this.text = text;
            return this;
        }

        /**
         * 添加单个附件
         *
         * @param attachment 附件
         * @return Builder 实例
         */
        public Builder attachment(AttachmentDto attachment) {
            if (attachment != null) {
                if (this.attachments == null) {
                    this.attachments = new ArrayList<>();
                }
                this.attachments.add(attachment);
            }
            return this;
        }

        /**
         * 设置附件列表
         *
         * @param attachments 附件列表
         * @return Builder 实例
         */
        public Builder attachments(List<AttachmentDto> attachments) {
            if (attachments != null) {
                this.attachments = attachments;
            }
            return this;
        }

        /**
         * 构建 Msg 实例
         *
         * <p>
         * 根据是否包含附件，选择不同的构建策略：
         * </p>
         * <ul>
         * <li>无附件：使用 textContent 纯文本模式</li>
         * <li>有附件：使用 content 多模态模式（ImageBlock + TextBlock）</li>
         * </ul>
         *
         * @return Msg 实例
         */
        public Msg build() {
            if (attachments == null || attachments.isEmpty()) {
                // 纯文本消息
                return Msg.builder()
                        .role(MsgRole.USER)
                        .textContent(text != null ? text : "")
                        .build();
            }

            log.info("构建多模态消息: 文本长度={}, 附件数量={}",
                    text != null ? text.length() : 0,
                    attachments.size());

            // 构建内容块列表
            List<ContentBlock> contentBlocks = new ArrayList<>();

            // 添加文本块
            if (text != null && !text.isBlank()) {
                contentBlocks.add(TextBlock.builder().text(text).build());
            }

            // 添加图片块
            for (AttachmentDto attachment : attachments) {
                if (attachment.isImage() && attachment.getUrl() != null) {
                    try {
                        // 使用 ImageBlock 构建图片内容
                        ImageBlock imageBlock = ImageBlock.builder()
                                .source(URLSource.builder()
                                        .url(attachment.getUrl())
                                        .build())
                                .build();
                        contentBlocks.add(imageBlock);
                        log.debug("添加图片块: {}",
                                attachment.getUrl().substring(0, Math.min(50, attachment.getUrl().length())));
                    } catch (Exception e) {
                        log.warn("无法创建图片块，将以文本形式添加: {}", e.getMessage());
                        // 降级处理：将图片信息作为文本添加
                        contentBlocks.add(TextBlock.builder()
                                .text("[图片: " + attachment.getUrl() + "]")
                                .build());
                    }
                } else if (!attachment.isImage()) {
                    // 非图片附件，以文本形式描述
                    String desc = String.format("[%s: %s]",
                            attachment.getType(),
                            attachment.getFilename() != null ? attachment.getFilename() : attachment.getUrl());
                    contentBlocks.add(TextBlock.builder().text(desc).build());
                }
            }

            // 使用 content 方法构建多模态消息
            return Msg.builder()
                    .role(MsgRole.USER)
                    .content(contentBlocks)
                    .build();
        }
    }
}
