package io.yunxi.platform.framework.profile;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 节点画像实体
 *
 * @author yunxi-agent-platform
 */
@Data
public class NodeProfile {

    /** 客户端标识 */
    private String clientId;
    /** 用户ID */
    private String userId;
    /** 节点类型 */
    private String nodeType;
    /** 标签（JSON格式） */
    private String tags;           // JSON
    /** 操作系统信息（JSON格式） */
    private String osInfo;         // JSON
    /** 硬件信息（JSON格式） */
    private String hardware;       // JSON
    /** 网络信息（JSON格式） */
    private String network;        // JSON
    /** 服务列表（JSON格式） */
    private String services;       // JSON
    /** 软件列表（JSON格式） */
    private String software;       // JSON
    /** 常用路径（JSON格式） */
    private String commonPaths;    // JSON
    /** 云平台信息（JSON格式） */
    private String cloudInfo;      // JSON
    /** 云托管服务信息（JSON格式） */
    private String cloudManagedServices; // JSON
    /** 最后采集时间 */
    private LocalDateTime lastCollectedAt;
    /** 最后在线时间 */
    private LocalDateTime lastOnlineAt;
    /** 是否在线 */
    private Boolean isOnline;
}
