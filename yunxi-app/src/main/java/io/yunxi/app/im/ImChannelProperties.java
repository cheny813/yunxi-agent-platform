package io.yunxi.app.im;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 渠道接入配置（yunxi.im.*）。通过 ChannelManager 收敛到统一 HarnessGateway 入口。
 * IM 类渠道复用 AgentScope-Java 原生 DingTalkChannel / WeComChannel / FeishuChannel（均有框架回调控制器，本模块显式声明 Bean）；
 * ChatUiChannel 为编程式渠道（进程内、无 HTTP 端点），仅经 ChannelManager 注册后供 yunxi 内部 Java 代码 / 集成测试
 * 直接驱动 Agent，不构成第二套 web 对话入口。
 *
 * <p>IM 渠道凭据经环境变量注入，不进仓库；chatui 无凭据、默认开启。</p>
 */
@ConfigurationProperties(prefix = "yunxi.im")
public class ImChannelProperties {

    /** IM 渠道未显式指定 agentId 时的默认路由 Agent（须已注册进 AguiAgentRegistry）。 */
    private String defaultAgentId = "example-assistant";

    private final DingTalk dingtalk = new DingTalk();

    /** 企业微信同构于钉钉，经 ChannelManager 接入 AgentScope-Java 原生 WeComChannel。 */
    private final WeCom wecom = new WeCom();

    /** 飞书同构于钉钉 / 企业微信，经 ChannelManager 接入 AgentScope-Java 原生 FeishuChannel。 */
    private final Feishu feishu = new Feishu();

    /** 编程式渠道（进程内、无 HTTP 端点），经 ChannelManager 注册进 HarnessGateway；默认开启。 */
    private final ChatUi chatUi = new ChatUi();

    /** GitHub 同构于钉钉 / 企业微信 / 飞书，经 ChannelManager 接入 AgentScope-Java 原生 GitHubChannel。 */
    private final GitHub github = new GitHub();

    /** GitLab 同构于钉钉 / 企业微信 / 飞书 / GitHub，经 ChannelManager 接入 AgentScope-Java 原生 GitLabChannel。 */
    private final GitLab gitlab = new GitLab();

    /** 微信（iLink）同构于钉钉 / 企业微信 / 飞书 / GitHub / GitLab，经 ChannelManager 接入 AgentScope-Java 原生 WeixinChannel。 */
    private final Weixin weixin = new Weixin();

    public String getDefaultAgentId() {
        return defaultAgentId;
    }

    public void setDefaultAgentId(String defaultAgentId) {
        this.defaultAgentId = defaultAgentId;
    }

    public DingTalk getDingtalk() {
        return dingtalk;
    }

    public WeCom getWecom() {
        return wecom;
    }

    public Feishu getFeishu() {
        return feishu;
    }

    public ChatUi getChatUi() {
        return chatUi;
    }

    public GitHub getGithub() {
        return github;
    }

    public GitLab getGitlab() {
        return gitlab;
    }

    public Weixin getWeixin() {
        return weixin;
    }

    /** 钉钉渠道配置（DingTalkChannel.fromProperties 的 rawProperties 来源）。 */
    public static class DingTalk {
        private boolean enabled = false;
        private String channelId = "dingtalk";
        private String appKey;
        private String appSecret;
        private String robotCode;
        /** http = 被动回调随响应回包；stream = 主动长连消费。本阶段用 http。 */
        private String mode = "http";
        /** 机器人配了「加密」时必填；mode=http 且未加密可为空。 */
        private String aesKey;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getChannelId() {
            return channelId;
        }

        public void setChannelId(String channelId) {
            this.channelId = channelId;
        }

        public String getAppKey() {
            return appKey;
        }

        public void setAppKey(String appKey) {
            this.appKey = appKey;
        }

        public String getAppSecret() {
            return appSecret;
        }

        public void setAppSecret(String appSecret) {
            this.appSecret = appSecret;
        }

        public String getRobotCode() {
            return robotCode;
        }

        public void setRobotCode(String robotCode) {
            this.robotCode = robotCode;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getAesKey() {
            return aesKey;
        }

        public void setAesKey(String aesKey) {
            this.aesKey = aesKey;
        }
    }

    /** 企业微信渠道配置（WeComChannel.fromProperties 的 rawProperties 来源，与钉钉同构接线）。 */
    public static class WeCom {
        private boolean enabled = false;
        private String channelId = "wecom";
        private String corpid;
        private String corpsecret;
        private String agentid;
        private String token;
        private String aesKey;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getChannelId() {
            return channelId;
        }

        public void setChannelId(String channelId) {
            this.channelId = channelId;
        }

        public String getCorpid() {
            return corpid;
        }

        public void setCorpid(String corpid) {
            this.corpid = corpid;
        }

        public String getCorpsecret() {
            return corpsecret;
        }

        public void setCorpsecret(String corpsecret) {
            this.corpsecret = corpsecret;
        }

        public String getAgentid() {
            return agentid;
        }

        public void setAgentid(String agentid) {
            this.agentid = agentid;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getAesKey() {
            return aesKey;
        }

        public void setAesKey(String aesKey) {
            this.aesKey = aesKey;
        }
    }

    /** 飞书渠道配置（FeishuChannel.fromProperties 的 rawProperties 来源，与钉钉 / 企微同构接线）。 */
    public static class Feishu {
        private boolean enabled = false;
        private String channelId = "feishu";
        private String appId;
        private String appSecret;
        /** 配了 encrypt-key 即开启回调体加密（FeishuCrypto 用其作 HMAC 密钥，X-Lark-Signature 验签）。 */
        private String encryptKey;
        /** URL 校验握手（challenge）与事件鉴权（header.token）的校验令牌；飞书要求必填。 */
        private String verificationToken;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getChannelId() {
            return channelId;
        }

        public void setChannelId(String channelId) {
            this.channelId = channelId;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getAppSecret() {
            return appSecret;
        }

        public void setAppSecret(String appSecret) {
            this.appSecret = appSecret;
        }

        public String getEncryptKey() {
            return encryptKey;
        }

        public void setEncryptKey(String encryptKey) {
            this.encryptKey = encryptKey;
        }

        public String getVerificationToken() {
            return verificationToken;
        }

        public void setVerificationToken(String verificationToken) {
            this.verificationToken = verificationToken;
        }
    }

    /**
     * 编程式渠道配置（ChatUiChannel）。无凭据、无 HTTP 端点：仅经 ChannelManager 注册进 HarnessGateway，
     * 由 yunxi 内部 Java 代码 / 集成测试以 ChatUiChannel.send(...) 直接驱动 Agent。
     * 默认开启，不构成第二套 web 对话入口（浏览器 5173 仍走 yunxi 既有 web ingress / AG-UI 协议）。
     */
    public static class ChatUi {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * GitHub 渠道配置（代码托管平台 Issue / PR 机器人，非传统 IM）。
     * 凭据经环境变量注入，不进仓库。token 与 webhookSecret 为必填；apiBase 默认 https://api.github.com。
     * botUserLogin 留空时，启动期联网解析 bot 身份（best-effort，失败仅告警）。
     */
    public static class GitHub {
        private boolean enabled = false;
        private String channelId = "github";
        private String token;
        private String webhookSecret;
        private String apiBase = "https://api.github.com";
        private String webhookPath;
        private String botUserLogin;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getChannelId() {
            return channelId;
        }

        public void setChannelId(String channelId) {
            this.channelId = channelId;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getWebhookSecret() {
            return webhookSecret;
        }

        public void setWebhookSecret(String webhookSecret) {
            this.webhookSecret = webhookSecret;
        }

        public String getApiBase() {
            return apiBase;
        }

        public void setApiBase(String apiBase) {
            this.apiBase = apiBase;
        }

        public String getWebhookPath() {
            return webhookPath;
        }

        public void setWebhookPath(String webhookPath) {
            this.webhookPath = webhookPath;
        }

        public String getBotUserLogin() {
            return botUserLogin;
        }

        public void setBotUserLogin(String botUserLogin) {
            this.botUserLogin = botUserLogin;
        }
    }

    /**
     * GitLab 渠道配置（同构于 GitHub / 企业微信 / 飞书 / 钉钉）。
     *
     * <p>经 ChannelManager 接入 AgentScope-Java 原生 GitLabChannel（Webhook 驱动，Issue / MR 机器人），
     * 收敛到统一 HarnessGateway；框架 GitLabWebhookController 提供 webhook 端点
     * {@code POST /api/channels/gitlab/{channelId}/webhook}，真实性由 {@code X-Gitlab-Token} 头比对验签保证。
     * yunxi 仅显式声明 GitLabWebhookController Bean 并注入 YunxiChannelRuntimeContextResolver；
     * 渠道默认关闭，需经环境变量注入 {@code token} / {@code webhook-token} 后才在启动时注册。</p>
     *
     * <p>配置键与 AgentScope-Java {@code GitLabChannelProperties} 对齐：{@code token} / {@code webhookToken}
     * 为必填（fromProperties 校验非空），{@code apiBase} 默认 {@code https://gitlab.com/api/v4}。
     * GitLab 无 botUserLogin 概念（bot 身份由 token 启动期联网解析，best-effort 失败仅告警），故不暴露该字段。</p>
     */
    public static class GitLab {
        private boolean enabled = false;
        private String channelId = "gitlab";
        private String token;
        private String webhookToken;
        private String apiBase = "https://gitlab.com/api/v4";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getChannelId() {
            return channelId;
        }

        public void setChannelId(String channelId) {
            this.channelId = channelId;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getWebhookToken() {
            return webhookToken;
        }

        public void setWebhookToken(String webhookToken) {
            this.webhookToken = webhookToken;
        }

        public String getApiBase() {
            return apiBase;
        }

        public void setApiBase(String apiBase) {
            this.apiBase = apiBase;
        }
    }

    /**
     * 微信（iLink）渠道配置（同构于 GitHub / 企业微信 / 飞书 / 钉钉 / GitLab）。
     *
     * <p>经 ChannelManager 接入 AgentScope-Java 原生 WeixinChannel（iLink 长轮询机器人，无 HTTP 入站端点，
     * 故 SecurityConfig 无需放行任何 /api/channels/weixin/* 路径），收敛到统一 HarnessGateway。
     * 凭据为 iLink {@code botToken}（固定凭据，经 {@code WeixinCredentialProvider.fixed} 注入，非扫码登录），
     * 由 yunxi 经环境变量注入，不进仓库。状态持久化由 {@code WeixinStateStore} 负责：默认 {@code inmemory}
     * （进程内、重启丢状态），设 {@code state-store=redis} 启用 yunxi 自实现 {@code RedisWeixinStateStore}
     * （复用平台全局 RedisConnectionFactory，重启可恢复 cursor / lease / inbox / context token）。</p>
     *
     * <p>配置键与 AgentScope-Java {@code WeixinChannelProperties.from(channelId, Map)} 对齐：
     * {@code accountId} / {@code baseUrl} / {@code ilinkUserId}（botToken 走凭据注入、不进 properties Map）；
     * {@code baseUrl} 默认 {@code https://ilinkai.weixin.qq.com}。长轮询各超时采用框架默认值，本配置不暴露。</p>
     */
    public static class Weixin {
        private boolean enabled = false;
        private String channelId = "weixin";
        private String accountId = "weixin";
        private String botToken;
        private String baseUrl = "https://ilinkai.weixin.qq.com";
        private String ilinkUserId;
        private String stateStore = "inmemory";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getChannelId() {
            return channelId;
        }

        public void setChannelId(String channelId) {
            this.channelId = channelId;
        }

        public String getAccountId() {
            return accountId;
        }

        public void setAccountId(String accountId) {
            this.accountId = accountId;
        }

        public String getBotToken() {
            return botToken;
        }

        public void setBotToken(String botToken) {
            this.botToken = botToken;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getIlinkUserId() {
            return ilinkUserId;
        }

        public void setIlinkUserId(String ilinkUserId) {
            this.ilinkUserId = ilinkUserId;
        }

        public String getStateStore() {
            return stateStore;
        }

        public void setStateStore(String stateStore) {
            this.stateStore = stateStore;
        }
    }
}
