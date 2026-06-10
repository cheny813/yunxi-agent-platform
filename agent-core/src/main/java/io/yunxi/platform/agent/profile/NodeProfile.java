package io.yunxi.platform.agent.profile;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 节点画像实体
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
