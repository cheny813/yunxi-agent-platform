package io.yunxi.platform.infra.file;

/**
 * 文件类型枚举
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public enum FileType {

    /**
     * 图片
     */
    IMAGE("image", "图片", new String[]{".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp"}),

    /**
     * 音频
     */
    AUDIO("audio", "音频", new String[]{".mp3", ".wav", ".m4a", ".flac", ".aac", ".ogg"}),

    /**
     * 视频
     */
    VIDEO("video", "视频", new String[]{".mp4", ".avi", ".mov", ".mkv", ".wmv", ".flv"}),

    /**
     * 文档
     */
    DOCUMENT("document", "文档", new String[]{".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".md", ".html"});

    /** 类型编码 */
    private final String code;
    /** 类型描述 */
    private final String description;
    /** 支持的文件扩展名 */
    private final String[] extensions;

    /**
     * 构造函数
     *
     * @param code        类型编码
     * @param description 类型描述
     * @param extensions  支持的文件扩展名
     */
    FileType(String code, String description, String[] extensions) {
        this.code = code;
        this.description = description;
        this.extensions = extensions;
    }

    /**
     * 获取类型编码
     *
     * @return 类型编码
     */
    public String getCode() {
        return code;
    }

    /**
     * 获取类型描述
     *
     * @return 类型描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 获取支持的文件扩展名
     *
     * @return 扩展名数组
     */
    public String[] getExtensions() {
        return extensions;
    }

    /**
     * 根据文件扩展名判断文件类型
     *
     * @param fileName 文件名
     * @return 文件类型，未匹配时返回 null
     */
    public static FileType fromExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }

        String lowerFileName = fileName.toLowerCase();
        for (FileType type : values()) {
            for (String ext : type.getExtensions()) {
                if (lowerFileName.endsWith(ext)) {
                    return type;
                }
            }
        }

        return null;
    }

    /**
     * 检查扩展名是否匹配
     *
     * @param fileName 文件名
     * @return 是否匹配
     */
    public boolean matchesExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }

        String lowerFileName = fileName.toLowerCase();
        for (String ext : extensions) {
            if (lowerFileName.endsWith(ext)) {
                return true;
            }
        }

        return false;
    }
}
