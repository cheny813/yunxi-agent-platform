package io.yunxi.platform.shared.dto;

import java.util.Map;

/**
 * 附件 DTO
 *
 * <p>
 * 支持多模态对话，包括图片、文档、音频、视频等。
 * </p>
 *
 * <p>
 * <b>支持的类型</b>:
 * <ul>
 * <li>image - 图片（JPG、PNG、GIF 等）</li>
 * <li>document - 文档（PDF、Word、Excel 等）</li>
 * <li>audio - 音频（MP3、WAV 等）</li>
 * <li>video - 视频（MP4、AVI 等）</li>
 * <li>code - 代码文件（支持语法高亮）</li>
 * <li>text - 纯文本文件</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>使用示例</b>:
 * 
 * <pre>
 * // 图片（base64 编码）
 * {
 *   "type": "image",
 *   "url": "data:image/jpeg;base64,/9j/4AAQSkZJRg...",
 *   "mimeType": "image/jpeg",
 *   "metadata": {"width": 1920, "height": 1080}
 * }
 *
 * // 文档（URL）
 * {
 *   "type": "document",
 *   "url": "https://example.com/report.pdf",
 *   "mimeType": "application/pdf"
 * }
 *
 * // 文件上传后返回的 URL
 * {
 *   "type": "image",
 *   "url": "https://cdn.example.com/images/photo-123.jpg"
 * }
 * </pre>
 * </p>
 */
public class AttachmentDto {

    /**
     * 附件类型
     * <p>
     * 必填，支持的值：image, document, audio, video, code, text
     * </p>
     */
    private String type;

    /**
     * 文件 URL 或 base64 编码
     * <p>
     * 必填，可以是：
     * <ul>
     * <li>HTTP/HTTPS URL</li>
     * <li>Data URL（base64 编码，格式：data:[mediatype][;base64],data）</li>
     * <li>相对路径（针对已上传的文件）</li>
     * </ul>
     * </p>
     */
    private String url;

    /**
     * MIME 类型
     * <p>
     * 可选，但强烈建议提供。
     * 例如：image/jpeg, application/pdf, audio/mp3
     * </p>
     */
    private String mimeType;

    /**
     * 文件名（可选）
     * <p>
     * 对于上传的文件，建议提供原始文件名。
     * </p>
     */
    private String filename;

    /**
     * 文件大小（字节，可选）
     */
    private Long size;

    /**
     * 元数据（可选）
     * <p>
     * 存储附件的额外信息，如图片的尺寸、文档的页数等。
     * </p>
     */
    private Map<String, Object> metadata;

    // ==================== Getter 和 Setter 方法 ====================

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    // ==================== 便捷构造方法 ====================

    /**
     * 最简构造 - 图片（base64）
     */
    public static AttachmentDto imageFromBase64(String base64Data) {
        AttachmentDto attachmentDto = new AttachmentDto();
        attachmentDto.setType("image");
        attachmentDto.setUrl("data:image/jpeg;base64," + base64Data);
        attachmentDto.setMimeType("image/jpeg");
        return attachmentDto;
    }

    /**
     * 最简构造 - 图片（URL）
     */
    public static AttachmentDto imageFromUrl(String imageUrl) {
        AttachmentDto attachmentDto = new AttachmentDto();
        attachmentDto.setType("image");
        attachmentDto.setUrl(imageUrl);
        return attachmentDto;
    }

    /**
     * 最简构造 - 文档（URL）
     */
    public static AttachmentDto documentFromUrl(String documentUrl, String filename) {
        AttachmentDto attachmentDto = new AttachmentDto();
        attachmentDto.setType("document");
        attachmentDto.setUrl(documentUrl);
        attachmentDto.setFilename(filename);

        // 根据扩展名推断 MIME 类型
        if (filename != null) {
            if (filename.endsWith(".pdf")) {
                attachmentDto.setMimeType("application/pdf");
            } else if (filename.endsWith(".doc") || filename.endsWith(".docx")) {
                attachmentDto.setMimeType("application/msword");
            } else if (filename.endsWith(".xls") || filename.endsWith(".xlsx")) {
                attachmentDto.setMimeType("application/vnd.ms-excel");
            } else if (filename.endsWith(".txt")) {
                attachmentDto.setMimeType("text/plain");
            }
        }

        return attachmentDto;
    }

    // ==================== 工具方法 ====================

    /**
     * 是否为图片
     */
    public boolean isImage() {
        return "image".equals(type);
    }

    /**
     * 是否为文档
     */
    public boolean isDocument() {
        return "document".equals(type);
    }

    /**
     * 是否为音频
     */
    public boolean isAudio() {
        return "audio".equals(type);
    }

    /**
     * 是否为视频
     */
    public boolean isVideo() {
        return "video".equals(type);
    }

    /**
     * 是否为 base64 编码的 Data URL
     */
    public boolean isDataUrl() {
        return url != null && url.startsWith("data:");
    }

    /**
     * 是否为 HTTP/HTTPS URL
     */
    public boolean isHttpUrl() {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /**
     * 获取元数据中的指定值
     */
    public Object getMetadataValue(String key) {
        return metadata != null ? metadata.get(key) : null;
    }

    /**
     * 设置元数据
     */
    public void setMetadataValue(String key, Object value) {
        if (metadata == null) {
            metadata = new java.util.HashMap<>();
        }
        metadata.put(key, value);
    }
}
