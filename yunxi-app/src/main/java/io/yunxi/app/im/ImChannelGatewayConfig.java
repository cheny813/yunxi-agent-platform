package io.yunxi.app.im;

import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.extensions.channel.common.IdempotencyStore;
import io.agentscope.extensions.channel.common.InMemoryAccessTokenStore;
import io.agentscope.extensions.channel.dingtalk.DingTalkCallbackController;
import io.agentscope.extensions.channel.dingtalk.DingTalkChannel;
import io.agentscope.extensions.channel.feishu.FeishuCallbackController;
import io.agentscope.extensions.channel.feishu.FeishuChannel;
import io.agentscope.extensions.channel.github.GitHubChannel;
import io.agentscope.extensions.channel.github.GitHubWebhookController;
import io.agentscope.extensions.channel.gitlab.GitLabChannel;
import io.agentscope.extensions.channel.gitlab.GitLabWebhookController;
import io.agentscope.extensions.channel.weixin.WeixinChannel;
import io.agentscope.extensions.channel.weixin.WeixinChannelProperties;
import io.agentscope.extensions.channel.weixin.WeixinCredentialProvider;
import io.agentscope.extensions.channel.weixin.WeixinRuntimeListener;
import io.agentscope.extensions.channel.weixin.WeixinStateStore;
import org.springframework.beans.factory.ObjectProvider;
import io.agentscope.extensions.channel.wecom.WeComCallbackController;
import io.agentscope.extensions.channel.wecom.WeComChannel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.GatewayBootstrap;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

/**
 * 渠道网关装配（Stage 1 试点）：
 * 把经 ChannelManager 接入的 DingTalkChannel / WeComChannel / FeishuChannel / GitHubChannel / GitLabChannel / WeixinChannel 注册进 AgentScope-Java 统一 HarnessGateway，
 * 收敛原 yunxi 自建设施（SessionTurnGuard / AgentLookup / ProtocolUserIdResolver 在网关接入后由框架等价能力替代）。
 * ChatUiChannel 为编程式渠道（进程内、无 HTTP 端点），同样经 ChannelManager 注册，供 yunxi 内部 Java 代码 /
 * 集成测试以 ChatUiChannel.send(...) 直接驱动 Agent；不构成第二套 web 对话入口（浏览器 5173 仍走既有 ingress / AG-UI）。
 * 身份富化经 YunxiChannelRuntimeContextResolver 收口。
 *
 * <p>必须在 AgentProvisioner 把 HarnessAgent 注册进 AguiAgentRegistry 之后装配：
 * 两者均监听 ContextRefreshedEvent，@DependsOn("agentProvisioner") 保证本 Bean 晚于 provisioner 实例化，
 * 从而本监听在 provision 之后触发（与 AgentProvisioner 的 @DependsOn 排序范式一致）。</p>
 */
@Configuration
@DependsOn("agentProvisioner")
@EnableConfigurationProperties(ImChannelProperties.class)
public class ImChannelGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(ImChannelGatewayConfig.class);

    /** 微信（iLink）持久化状态存储；仅当 yunxi.im.weixin.state-store=redis 时由 RedisWeixinStateStore 注册为本 Bean。 */
    private final ObjectProvider<RedisWeixinStateStore> redisStateStoreProvider;

    private final AguiAgentRegistry aguiAgentRegistry;
    private final ImChannelProperties properties;
    private final YunxiChannelRuntimeContextResolver runtimeContextResolver;

    /** 装配完成后保存的 ChannelManager，供 yunxi 内部代码 / 集成测试以 getChannel("chatui") 取回编程式渠道句柄。 */
    private volatile ChannelManager channelManager;

    /** 注入框架 Agent 真相源、IM 配置、渠道身份解析器与微信持久化状态存储。 */
    public ImChannelGatewayConfig(
            AguiAgentRegistry aguiAgentRegistry,
            ImChannelProperties properties,
            YunxiChannelRuntimeContextResolver runtimeContextResolver,
            ObjectProvider<RedisWeixinStateStore> redisStateStoreProvider) {
        this.aguiAgentRegistry = aguiAgentRegistry;
        this.properties = properties;
        this.runtimeContextResolver = runtimeContextResolver;
        this.redisStateStoreProvider = redisStateStoreProvider;
    }

    /**
     * 框架 DingTalkCallbackController 不在 yunxi 组件扫描范围内，显式声明为 Bean 以暴露回调端点
     * /api/channels/dingtalk/{channelId}/callback（控制器经 DingTalkChannelRegistry 单例按 channelId 分发）。
     */
    @Bean
    public DingTalkCallbackController dingTalkCallbackController() {
        return new DingTalkCallbackController();
    }

    /**
     * 框架 WeComCallbackController 不在 yunxi 组件扫描范围内，显式声明为 Bean 以暴露回调端点
     * /api/channels/wecom/{channelId}/callback（控制器经 WeComChannelRegistry 单例按 channelId 分发）。
     */
    @Bean
    public WeComCallbackController weComCallbackController() {
        return new WeComCallbackController();
    }

    /**
     * 框架 FeishuCallbackController 不在 yunxi 组件扫描范围内，显式声明为 Bean 以暴露回调端点
     * /api/channels/feishu/{channelId}/callback（控制器经 FeishuChannelRegistry 单例按 channelId 分发）。
     */
    @Bean
    public FeishuCallbackController feishuCallbackController() {
        return new FeishuCallbackController();
    }

    @Bean
    public GitHubWebhookController githubWebhookController() {
        // 框架 GitHubWebhookController 路由 /api/channels/github/{channelId}/webhook，
        // 真实性由框架 HmacSHA256（X-Hub-Signature-256）验签保证；SecurityConfig 已对该路径放行 permitAll。
        return new GitHubWebhookController();
    }

    @Bean
    public GitLabWebhookController gitLabWebhookController() {
        // 框架 GitLabWebhookController 路由 /api/channels/gitlab/{channelId}/webhook，
        // 真实性由框架 X-Gitlab-Token 头比对验签保证；SecurityConfig 已对该路径放行 permitAll。
        return new GitLabWebhookController();
    }

    /** 上下文刷新后装配渠道网关：IM 渠道（钉钉 / 企业微信 / 飞书）与编程式 ChatUi 渠道经 ChannelManager 接入 HarnessGateway。 */
    @EventListener(ContextRefreshedEvent.class)
    public void start() {
        boolean chatUiEnabled = properties.getChatUi().isEnabled();
        boolean anyIm = properties.getDingtalk().isEnabled()
                || properties.getWecom().isEnabled()
                || properties.getFeishu().isEnabled()
                || properties.getGithub().isEnabled()
                || properties.getGitlab().isEnabled()
                || properties.getWeixin().isEnabled();
        if (!chatUiEnabled && !anyIm) {
            log.info("渠道全部未启用（yunxi.im.*.enabled=false），跳过 HarnessGateway 装配。");
            return;
        }

        String defaultAgentId = properties.getDefaultAgentId();
        Optional<HarnessAgent> main = aguiAgentRegistry.getAgent(defaultAgentId).map(a -> (HarnessAgent) a);
        if (main.isEmpty()) {
            log.warn("渠道网关：默认 Agent '{}' 未注册进 AguiAgentRegistry，跳过装配。", defaultAgentId);
            return;
        }

        GatewayBootstrap.Builder builder = GatewayBootstrap.builder()
                .mainAgent(defaultAgentId)
                .agent(defaultAgentId, main.get())
                .runtimeContextResolver(runtimeContextResolver);

        // ChatUi 为编程式渠道：进程内、无 HTTP 端点，仅经 ChannelManager 注册，供内部代码 / 集成测试驱动。
        if (chatUiEnabled) {
            builder.channel(ChatUiChannel.create());
            log.info("渠道网关：已注册 ChatUi 编程式渠道（channelId=chatui，无 HTTP 端点，供内部调用）。");
        }

        if (properties.getDingtalk().isEnabled()) {
            builder.channel(buildDingTalk(properties.getDingtalk()));
            log.info("渠道网关：已接入钉钉渠道（channelId={}）。", properties.getDingtalk().getChannelId());
        }

        if (properties.getWecom().isEnabled()) {
            builder.channel(buildWeCom(properties.getWecom()));
            log.info("渠道网关：已接入企业微信渠道（channelId={}）。", properties.getWecom().getChannelId());
        }

        if (properties.getFeishu().isEnabled()) {
            builder.channel(buildFeishu(properties.getFeishu()));
            log.info("渠道网关：已接入飞书渠道（channelId={}）。", properties.getFeishu().getChannelId());
        }

        if (properties.getGithub().isEnabled()) {
            builder.channel(buildGitHub(properties.getGithub()));
            log.info("渠道网关：已接入 GitHub 渠道（channelId={}）。", properties.getGithub().getChannelId());
        }

        if (properties.getGitlab().isEnabled()) {
            builder.channel(buildGitLab(properties.getGitlab()));
            log.info("渠道网关：已接入 GitLab 渠道（channelId={}）。", properties.getGitlab().getChannelId());
        }

        if (properties.getWeixin().isEnabled()) {
            builder.channel(buildWeixin(properties.getWeixin()));
            log.info("渠道网关：已接入微信（iLink）渠道（channelId={}）。", properties.getWeixin().getChannelId());
        }

        try {
            GatewayBootstrap bootstrap = builder.build();
            bootstrap.start();
            this.channelManager = bootstrap.channelManager();
            log.info("渠道网关：HarnessGateway 已启动；chatui 编程式渠道已注册（无 HTTP 端点），"
                    + "钉钉 / 企业微信 / 飞书 / GitHub / GitLab 回调端点（若启用）均已可用，"
                    + "微信（iLink）长轮询渠道（若启用）已接入。");
        } catch (Exception e) {
            // 渠道装配失败不得拖垮其余四协议入口，降级为日志告警。
            log.error("渠道网关装配失败，渠道不可用（不影响 web / AG-UI / Chat Completions / A2A 入口）。", e);
        }
    }

    /**
     * 取回装配后的 ChannelManager，供 yunxi 内部代码 / 集成测试以 {@code getChannel("chatui")} 获得编程式渠道句柄。
     * 仅在本配置成功装配网关后非空（即至少 chatui 或某个 IM 渠道启用且默认 Agent 已注册）。
     */
    public ChannelManager getChannelManager() {
        return channelManager;
    }

    /** 由 yunxi.im.dingtalk.* 构造框架 DingTalkChannel（完整适配器：验签 + 入站翻译 + 出站推送）。 */
    private Channel buildDingTalk(ImChannelProperties.DingTalk dtk) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("appKey", dtk.getAppKey());
        raw.put("appSecret", dtk.getAppSecret());
        raw.put("robotCode", dtk.getRobotCode());
        raw.put("mode", dtk.getMode());
        if (dtk.getAesKey() != null && !dtk.getAesKey().isBlank()) {
            raw.put("aesKey", dtk.getAesKey());
        }
        ChannelConfig routing = ChannelConfig.of(dtk.getChannelId(), properties.getDefaultAgentId());
        return DingTalkChannel.fromProperties(
                dtk.getChannelId(), routing, raw, new IdempotencyStore(), new InMemoryAccessTokenStore());
    }

    /** 由 yunxi.im.wecom.* 构造框架 WeComChannel（同构于钉钉：验签 + 入站翻译 + 出站推送）。 */
    private Channel buildWeCom(ImChannelProperties.WeCom wc) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("corpId", wc.getCorpid());
        raw.put("agentId", wc.getAgentid());
        raw.put("secret", wc.getCorpsecret());
        raw.put("token", wc.getToken());
        if (wc.getAesKey() != null && !wc.getAesKey().isBlank()) {
            raw.put("encodingAesKey", wc.getAesKey());
        }
        ChannelConfig routing = ChannelConfig.of(wc.getChannelId(), properties.getDefaultAgentId());
        // 框架 3 参便捷重载内部自构 IdempotencyStore + InMemoryAccessTokenStore（进程内去重 / 令牌缓存）。
        return WeComChannel.fromProperties(wc.getChannelId(), routing, raw);
    }

    /** 由 yunxi.im.feishu.* 构造框架 FeishuChannel（同构于钉钉 / 企业微信：验签 + 入站翻译 + 出站推送）。 */
    private Channel buildFeishu(ImChannelProperties.Feishu fs) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("appId", fs.getAppId());
        raw.put("appSecret", fs.getAppSecret());
        if (fs.getEncryptKey() != null && !fs.getEncryptKey().isBlank()) {
            raw.put("encryptKey", fs.getEncryptKey());
        }
        if (fs.getVerificationToken() != null && !fs.getVerificationToken().isBlank()) {
            raw.put("verificationToken", fs.getVerificationToken());
        }
        ChannelConfig routing = ChannelConfig.of(fs.getChannelId(), properties.getDefaultAgentId());
        // 飞书无 3 参便捷重载（不同于企业微信）：用 4 参重载显式传入进程内去重器，
        // InMemoryAccessTokenStore 由框架内部默认自构（与钉钉同构）。
        return FeishuChannel.fromProperties(fs.getChannelId(), routing, raw, new IdempotencyStore());
    }

    /** 由 yunxi.im.github.* 构造框架 GitHubChannel（同构于钉钉 / 企业微信 / 飞书：验签 + 入站翻译 + 出站推送）。 */
    private Channel buildGitHub(ImChannelProperties.GitHub gh) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("token", gh.getToken());
        raw.put("webhookSecret", gh.getWebhookSecret());
        if (gh.getApiBase() != null && !gh.getApiBase().isBlank()) {
            raw.put("apiBase", gh.getApiBase());
        }
        if (gh.getWebhookPath() != null && !gh.getWebhookPath().isBlank()) {
            raw.put("webhookPath", gh.getWebhookPath());
        }
        if (gh.getBotUserLogin() != null && !gh.getBotUserLogin().isBlank()) {
            raw.put("botUserLogin", gh.getBotUserLogin());
        }
        ChannelConfig routing = ChannelConfig.of(gh.getChannelId(), properties.getDefaultAgentId());
        // 框架 3 参便捷重载内部自构 IdempotencyStore（进程内去重），同构于企业微信。
        return GitHubChannel.fromProperties(gh.getChannelId(), routing, raw);
    }

    /** 由 yunxi.im.gitlab.* 构造框架 GitLabChannel（同构于钉钉 / 企业微信 / 飞书 / GitHub：验签 + 入站翻译 + 出站推送）。 */
    private Channel buildGitLab(ImChannelProperties.GitLab gl) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("token", gl.getToken());
        raw.put("webhookToken", gl.getWebhookToken());
        if (gl.getApiBase() != null && !gl.getApiBase().isBlank()) {
            raw.put("apiBase", gl.getApiBase());
        }
        ChannelConfig routing = ChannelConfig.of(gl.getChannelId(), properties.getDefaultAgentId());
        // 框架 3 参便捷重载内部自构 IdempotencyStore（进程内去重），同构于 GitHub。
        return GitLabChannel.fromProperties(gl.getChannelId(), routing, raw);
    }

    /**
     * 由 yunxi.im.weixin.* 构造框架 WeixinChannel（iLink 长轮询模型，与 Webhook 系列结构不同：
     * 无 HTTP 入站端点、凭据为固定 botToken、状态持久化由 WeixinStateStore 负责）。
     * 用 6 参 create 显式注入持久化状态存储（而非 3 参 fromProperties 默认的进程内实现），
     * 满足"完整状态持久化"诉求；当 redis 状态存储未启用时回退到框架 WeixinStateStore.inMemory()。
     */
    private Channel buildWeixin(ImChannelProperties.Weixin wx) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("accountId", wx.getAccountId());
        raw.put("baseUrl", wx.getBaseUrl());
        if (wx.getIlinkUserId() != null && !wx.getIlinkUserId().isBlank()) {
            raw.put("ilinkUserId", wx.getIlinkUserId());
        }
        ChannelConfig routing = ChannelConfig.of(wx.getChannelId(), properties.getDefaultAgentId());
        WeixinChannelProperties props = WeixinChannelProperties.from(wx.getChannelId(), raw);
        WeixinCredentialProvider creds = WeixinCredentialProvider.fixed(wx.getBotToken());
        WeixinStateStore store = redisStateStoreProvider.getIfAvailable();
        if (store == null) {
            store = WeixinStateStore.inMemory();
        }
        // 第六参 WeixinRuntimeListener：框架全 default，noOp 满足启动期接线；后续如需审计 / 度量可替换为自定义实现。
        return WeixinChannel.create(
                wx.getChannelId(), routing, props, creds, store, WeixinRuntimeListener.noOp());
    }
}
