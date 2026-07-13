package io.yunxi.platform.agent.profile;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 节点画像实体
 *
 * <p>记录某客户端（clientId）下某用户（userId）所在节点的环境信息，
 * 用于结构化管理客户端主机的软硬件与网络特征。其中 {@code tags}、{@code osInfo}、
 * {@code hardware}、{@code network}、{@code services}、{@code software}、
 * {@code commonPaths}、{@code cloudInfo}、{@code cloudManagedServices} 等字段
 * 均以 JSON 字符串形式存储，由采集端序列化后写入。</p>
 *
 * @author yunxi-agent-platform
 */
@Data
public class NodeProfile {
    private String clientId;
    private String userId;
    private String nodeType;
    private String tags;           // JSON
    private String osInfo;         // JSON
    private String hardware;       // JSON
    private String network;        // JSON
    private String services;       // JSON
    private String software;       // JSON
    private String commonPaths;    // JSON
    private String cloudInfo;      // JSON
    private String cloudManagedServices; // JSON
    private LocalDateTime lastCollectedAt;
    private LocalDateTime lastOnlineAt;
    private Boolean isOnline;
}
