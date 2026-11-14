package io.yunxi.fabric.aistio;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * aistio 集成配置（yunxi 产品平面）。
 *
 * <p>仅承载 yunxi 侧的连接参数；注册/心跳/gRPC/契约等语义全部由框架 {@code ControlPlane.instrument} 负责。
 * 启用时控制面 REST URL 与内部令牌必填；{@code instanceKey} 可覆盖框架默认的 {@code HOSTNAME}
 * （建议设为 {@code ${app}-${host}-${port}} 以稳定唯一，对齐实例键稳定唯一的约定）。</p>
 */
@Component
@ConfigurationProperties(prefix = "yunxi.aistio")
public class AistioIntegrationProperties {

    private boolean enabled = false;
    /** aistiod REST base URL（HTTP 自注册），如 http://localhost:8081。 */
    private String controlPlaneUrl = "";
    /** aistiod ASDP gRPC endpoint（host:port），仅 startGrpc=true 时需要。 */
    private String controlPlane = "";
    /** 数据面宿主机可达 base URL（如 http://host.docker.internal:40001），供控制面探活 yunxi（绝不能留空让框架推导为容器 localhost）。 */
    private String baseUrl = "";
    /** 控制面内部令牌，保护 /sessions 等数据面端点（经 X-Builder-Internal-Token 头传递）。 */
    private String internalToken = "";
    /** 命名空间（默认 default）。 */
    private String namespace = "default";
    /** 覆盖框架默认 instanceKey（HOSTNAME）；未配置时自动取 ${app}-${host}-${port} 稳定唯一。 */
    private String instanceKey = "";
    /** 工作区定义 Agent 的本地根目录（含运行时文件）；受控面派发含 definition 的任务时启用。 */
    private String workspaceRoot = System.getProperty("java.io.tmpdir") + java.io.File.separator + "yunxi-workspaces";
    /** 数据面契约 HTTP 端口（默认 40001）。 */
    private int contractHttpPort = 40001;
    /** aistio 数据面事件流观测需在 Agent 构建期挂 observer 中间件；控制面事件流规范尚未最终确定，当前默认关闭。 */
    private boolean enableEvents = false;
    /** 是否启动 HTTP 自注册（默认 true）。 */
    private boolean startHttpRegister = true;
    /** 是否启动 gRPC 对话流（默认 false，仅需 ASDP 任务接活时可关闭）。 */
    private boolean startGrpc = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getControlPlaneUrl() {
        return controlPlaneUrl;
    }

    public void setControlPlaneUrl(String controlPlaneUrl) {
        this.controlPlaneUrl = controlPlaneUrl;
    }

    public String getControlPlane() {
        return controlPlane;
    }

    public void setControlPlane(String controlPlane) {
        this.controlPlane = controlPlane;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getInstanceKey() {
        return instanceKey;
    }

    public void setInstanceKey(String instanceKey) {
        this.instanceKey = instanceKey;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public int getContractHttpPort() {
        return contractHttpPort;
    }

    public void setContractHttpPort(int contractHttpPort) {
        this.contractHttpPort = contractHttpPort;
    }

    public boolean isEnableEvents() {
        return enableEvents;
    }

    public void setEnableEvents(boolean enableEvents) {
        this.enableEvents = enableEvents;
    }

    public boolean isStartHttpRegister() {
        return startHttpRegister;
    }

    public void setStartHttpRegister(boolean startHttpRegister) {
        this.startHttpRegister = startHttpRegister;
    }

    public boolean isStartGrpc() {
        return startGrpc;
    }

    public void setStartGrpc(boolean startGrpc) {
        this.startGrpc = startGrpc;
    }
}
