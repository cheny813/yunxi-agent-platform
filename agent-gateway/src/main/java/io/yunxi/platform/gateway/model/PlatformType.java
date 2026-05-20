package io.yunxi.platform.gateway.model;

/**
 * 消息平台类型枚举
 */
public enum PlatformType {

    WECOM("wecom", "企业微信"),
    DINGTALK("dingtalk", "钉钉"),
    FEISHU("feishu", "飞书"),
    WEBAPI("webapi", "Web API");

    private final String code;
    private final String displayName;

    PlatformType(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static PlatformType fromCode(String code) {
        for (PlatformType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown platform code: " + code);
    }
}
