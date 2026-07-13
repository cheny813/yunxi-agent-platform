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

    /**
     * 构造节点画像服务。
     *
     * @param profileMapper 节点画像数据访问映射器
     */
    public NodeProfileService(NodeProfileMapper profileMapper) {
        this.profileMapper = profileMapper;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 由客户端上报的 {@link NodeInfo} 构造并持久化节点画像。
     *
     * <p>将客户端主机信息（标签、操作系统、网络等）序列化为 JSON 后写入，
     * 同时刷新采集时间与在线状态。任意异常均被捕获并记录日志，不影响主流程。</p>
     *
     * @param nodeInfo 客户端上报的节点信息
     */
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

    /**
     * 更新指定客户端的在线状态（并记录当前时间）。
     *
     * @param clientId 客户端标识
     * @param isOnline 是否在线
     */
    public void updateOnlineStatus(String clientId, boolean isOnline) {
        try { profileMapper.updateOnlineStatus(clientId, isOnline, LocalDateTime.now()); }
        catch (Exception e) { log.error("[Profile] 更新在线状态失败: clientId={}", clientId, e); }
    }

    /**
     * 按客户端 ID 查询节点画像。
     *
     * @param clientId 客户端标识
     * @return 命中的节点画像，查询失败或不存在时返回 null
     */
    public NodeProfile getByClientId(String clientId) {
        try { return profileMapper.selectByClientId(clientId); }
        catch (Exception e) { log.error("[Profile] 查询画像失败: clientId={}", clientId, e); return null; }
    }

    /**
     * 按用户 ID 查询其名下全部节点画像。
     *
     * @param userId 用户标识
     * @return 节点画像列表，查询失败时返回空列表
     */
    public List<NodeProfile> getByUserId(String userId) {
        try { return profileMapper.selectByUserId(userId); }
        catch (Exception e) { log.error("[Profile] 查询画像失败: userId={}", userId, e); return List.of(); }
    }

    /**
     * 按标签查询匹配的节点画像。
     *
     * @param tag 标签值
     * @return 命中的节点画像列表，查询失败时返回空列表
     */
    public List<NodeProfile> getByTag(String tag) {
        try { return profileMapper.selectByTag(tag); }
        catch (Exception e) { log.error("[Profile] 查询画像失败: tag={}", tag, e); return List.of(); }
    }

    /**
     * 查询当前在线的全部节点画像。
     *
     * @return 在线节点画像列表，查询失败时返回空列表
     */
    public List<NodeProfile> getOnlineNodes() {
        try { return profileMapper.selectOnlineNodes(); }
        catch (Exception e) { log.error("[Profile] 查询在线节点失败", e); return List.of(); }
    }

    /**
     * 构建面向 LLM 的节点画像文本摘要。
     *
     * @param clientId 客户端标识
     * @return 拼接好的画像摘要文本；画像不存在时返回 null
     */
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

    /**
     * 将对象安全序列化为 JSON 字符串。
     *
     * @param obj 待序列化对象
     * @return JSON 字符串，对象为 null 或序列化失败时返回 null
     */
    private String toJson(Object obj) {
        if (obj == null) return null;
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { log.warn("[Profile] JSON 序列化失败", e); return null; }
    }
}
