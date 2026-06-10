package io.yunxi.platform.agent.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.yunxi.platform.desktop.model.NodeInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 节点画像服务
 * <p>管理节点画像的保存、查询和自动同步。</p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Service
public class NodeProfileService {

    private final NodeProfileMapper profileMapper;
    private final ObjectMapper objectMapper;

    public NodeProfileService(NodeProfileMapper profileMapper) {
        this.profileMapper = profileMapper;
        this.objectMapper = new ObjectMapper();
    }

    public void saveFromNodeInfo(NodeInfo nodeInfo) {
        try {
            NodeProfile profile = new NodeProfile();
            profile.setClientId(nodeInfo.getClientId());
            profile.setUserId(nodeInfo.getUserId());
            profile.setNodeType(nodeInfo.getNodeType());
            profile.setTags(toJson(nodeInfo.getTags()));
            if (nodeInfo.getOs() != null || nodeInfo.getHostname() != null || nodeInfo.getLocalIp() != null) {
                profile.setOsInfo(toJson(Map.of(
                        "os", nodeInfo.getOs() != null ? nodeInfo.getOs() : "",
                        "hostname", nodeInfo.getHostname() != null ? nodeInfo.getHostname() : "",
                        "localIp", nodeInfo.getLocalIp() != null ? nodeInfo.getLocalIp() : "")));
            }
            profile.setLastCollectedAt(LocalDateTime.now());
            profile.setLastOnlineAt(LocalDateTime.now());
            profile.setIsOnline(true);
            profileMapper.insertOrUpdate(profile);
        } catch (Exception e) {
            log.error("[Profile] 保存画像失败: clientId={}", nodeInfo.getClientId(), e);
        }
    }

    public void updateOnlineStatus(String clientId, boolean isOnline) {
        try { profileMapper.updateOnlineStatus(clientId, isOnline, LocalDateTime.now()); }
        catch (Exception e) { log.error("[Profile] 更新在线状态失败: clientId={}", clientId, e); }
    }

    public NodeProfile getByClientId(String clientId) {
        try { return profileMapper.selectByClientId(clientId); }
        catch (Exception e) { log.error("[Profile] 查询画像失败: clientId={}", clientId, e); return null; }
    }

    public List<NodeProfile> getByUserId(String userId) {
        try { return profileMapper.selectByUserId(userId); }
        catch (Exception e) { log.error("[Profile] 查询画像失败: userId={}", userId, e); return List.of(); }
    }

    public List<NodeProfile> getByTag(String tag) {
        try { return profileMapper.selectByTag(tag); }
        catch (Exception e) { log.error("[Profile] 查询画像失败: tag={}", tag, e); return List.of(); }
    }

    public List<NodeProfile> getOnlineNodes() {
        try { return profileMapper.selectOnlineNodes(); }
        catch (Exception e) { log.error("[Profile] 查询在线节点失败", e); return List.of(); }
    }

    public String buildProfileSummary(String clientId) {
        NodeProfile profile = getByClientId(clientId);
        if (profile == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("【节点画像: ").append(clientId).append("】\n");
        if (profile.getOsInfo() != null) sb.append("- 系统: ").append(profile.getOsInfo()).append("\n");
        if (profile.getServices() != null) sb.append("- 服务: ").append(profile.getServices()).append("\n");
        if (profile.getTags() != null) sb.append("- 标签: ").append(profile.getTags()).append("\n");
        sb.append("- 在线: ").append(profile.getIsOnline()).append("\n");
        return sb.toString();
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { log.warn("[Profile] JSON 序列化失败", e); return null; }
    }
}
