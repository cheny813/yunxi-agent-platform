package io.yunxi.platform.framework.controller;

import lombok.Data;

import java.util.List;

/**
 * 节点信息（扩展自原 ClientInfo）
 *
 * <p>统一桌面客户端和服务端节点的注册信息，支持 userId、tags 等增强字段。</p>
 *
 * @author yunxi-agent-platform
 */
@Data
public class NodeInfo {

    /** 客户端唯一标识 */
    private String clientId;

    /** 关联用户 ID（用于用户→节点映射） */
    private String userId;

    /** 节点类型: desktop / server */
    private String nodeType;

    /** 节点标签（用于批量操作，如 ["county","hebei","zhaoxian"]） */
    private List<String> tags;

    /** 主机名 */
    private String hostname;

    /** 操作系统 */
    private String os;

    /** 本机 IP */
    private String localIp;

    /** 节点能力列表 */
    private String capabilities;

    /** 连接时间戳 */
    private long connectedAt;

    /** 最后心跳时间戳 */
    private long lastHeartbeat;

    /**
     * 是否为服务端节点
     */
    public boolean isServer() {
        return "server".equals(nodeType);
    }

    /**
     * 是否包含指定标签
     */
    public boolean hasTag(String tag) {
        return tags != null && tags.contains(tag);
    }
}
