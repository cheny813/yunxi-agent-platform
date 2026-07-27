package io.yunxi.platform.agent.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.credential.CredentialBase;
import io.agentscope.core.model.ChatModelBase;

import java.util.Objects;

/**
 * 华为云盘古（Huawei Pangu）供应商的 Credential 元数据。
 *
 * <p>仅用于与框架 {@link CredentialBase} 抽象对齐：声明该凭据由 {@link HuaweiModelProvider}
 * 消费，使「凭据 → 反向构造默认 model」的钩子对华为供应商同样闭合。
 *
 * <p>实际模型创建仍由 yunxi 的 {@code ModelFactory} 经 {@code ModelRegistry.resolve} 完成
 * （华为认证协议含 apiKey + secretKey，需走 {@link HuaweiModelProvider} 自建实现）。
 * {@link #listModels()} 沿用 {@link CredentialBase} 默认实现（直接抛
 * {@link UnsupportedOperationException}）—— 这与框架内置的 OpenAI / Anthropic / DashScope 等
 * 完全一致：AgentScope 2.0 的模型发现为桩，待 {@code ChatModelBase.listModels} hook 成熟后再接入。
 * 前端模型目录发现应走 yunxi 自有模型清单，而非依赖本 Credential 的 {@code listModels()}。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class HuaweiCredential extends CredentialBase {

    public static final String TYPE = "huawei_credential";

    private final String apiKey;

    private HuaweiCredential(String id, String apiKey) {
        super(id);
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
    }

    @JsonCreator
    static HuaweiCredential fromJson(
            @JsonProperty("id") String id,
            @JsonProperty("api_key") String apiKey) {
        return new HuaweiCredential(id, apiKey);
    }

    @JsonProperty("type")
    public String getType() {
        return TYPE;
    }

    @JsonProperty("api_key")
    public String getApiKey() {
        return apiKey;
    }

    @Override
    public Class<? extends ChatModelBase> getChatModelClass() {
        return HuaweiModelProvider.class;
    }

    @Override
    public String toString() {
        return "HuaweiCredential{id=" + getId() + ", apiKey=***}";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private String apiKey;

        private Builder() {}

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public HuaweiCredential build() {
            return new HuaweiCredential(id, apiKey);
        }
    }
}
