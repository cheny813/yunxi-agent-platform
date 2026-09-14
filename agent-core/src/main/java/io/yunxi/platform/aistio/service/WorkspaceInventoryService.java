package io.yunxi.platform.aistio.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.yunxi.platform.aistio.dto.WorkspaceInfo;
import io.yunxi.platform.aistio.registry.ActiveSessionRegistry;
import io.yunxi.platform.aistio.registry.ActiveSessionRegistry.SessionMeta;
import lombok.RequiredArgsConstructor;

/**
 * 构建 workspace 清单（{@code GET /agentscope/workspaces}）。
 *
 * <p>在 yunxi 中 workspace 即按 userId 隔离的命名空间；本服务从 {@link ActiveSessionRegistry}
 * 观测到的 userId 集合派生，并聚合该 workspace 下使用过的智能体。</p>
 */
@Component
@RequiredArgsConstructor
public class WorkspaceInventoryService {

    private final ActiveSessionRegistry registry;

    /**
     * 获取 workspace 清单。
     *
     * @return workspace 信息列表
     */
    public List<WorkspaceInfo> getWorkspaces() {
        List<WorkspaceInfo> out = new ArrayList<>();
        for (String userId : registry.workspaceIds()) {
            WorkspaceInfo workspace = new WorkspaceInfo();
            workspace.setId(userId);
            workspace.setType("user");

            Set<String> agents = new LinkedHashSet<>();
            for (SessionMeta meta : registry.list()) {
                if (userId.equals(meta.userId()) && meta.agentName() != null) {
                    agents.add(meta.agentName());
                }
            }
            workspace.setAgents(new ArrayList<>(agents.stream().filter(Objects::nonNull).toList()));
            out.add(workspace);
        }
        return out;
    }
}
