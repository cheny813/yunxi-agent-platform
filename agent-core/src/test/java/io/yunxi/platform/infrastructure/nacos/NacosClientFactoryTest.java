package io.yunxi.platform.infrastructure.nacos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

/**
 * NacosClientFactory 属性归一化单元测试（不连接真实 Nacos，只验证 Properties 构造）。
 *
 * <p>覆盖：serverAddr 始终写入；namespace 仅在非空时写入；username/password 成对出现，
 * 缺 username 时整组凭据省略。这是全仓 Nacos SDK 直连的唯一收敛点，归一化正确性直接影响
 * ConfigService / NamingService 能否拿到正确的 serverAddr / namespace / 鉴权。</p>
 */
@DisplayName("NacosClientFactory 属性归一化")
class NacosClientFactoryTest {

    private final NacosClientFactory factory = new NacosClientFactory();

    @Test
    @DisplayName("serverAddr 始终写入，namespace 在提供时写入，无凭据时不写 username/password")
    void namespaceIncludedWhenPresent() {
        NacosConnection conn = new NacosConnection("127.0.0.1:8848", "prod", null, null);
        Properties p = NacosClientFactory.toProperties(conn);
        assertThat(p.getProperty("serverAddr")).isEqualTo("127.0.0.1:8848");
        assertThat(p.getProperty("namespace")).isEqualTo("prod");
        assertThat(p.getProperty("username")).isNull();
        assertThat(p.getProperty("password")).isNull();
    }

    @Test
    @DisplayName("namespace 为 null 时被省略（不向 Nacos 传 namespace）")
    void namespaceOmittedWhenNull() {
        assertThat(NacosClientFactory.toProperties(new NacosConnection("127.0.0.1:8848", null, null, null))
                .getProperty("namespace")).isNull();
    }

    @Test
    @DisplayName("namespace 为非空字符串时原样写入（仅 null 触发省略，空白串按调用方约定原样传递）")
    void namespaceStoredVerbatim() {
        assertThat(NacosClientFactory.toProperties(new NacosConnection("127.0.0.1:8848", "  ", null, null))
                .getProperty("namespace")).isEqualTo("  ");
        assertThat(NacosClientFactory.toProperties(new NacosConnection("127.0.0.1:8848", "prod", null, null))
                .getProperty("namespace")).isEqualTo("prod");
    }

    @Test
    @DisplayName("无 username 时不写入凭据（username/password 成对省略）")
    void credentialsOmittedWhenNoUsername() {
        NacosConnection conn = new NacosConnection("127.0.0.1:8848", "prod", null, "secret");
        Properties p = NacosClientFactory.toProperties(conn);
        assertThat(p.getProperty("username")).isNull();
        assertThat(p.getProperty("password")).isNull();
    }

    @Test
    @DisplayName("提供 username 时 credentials 成对写入")
    void credentialsIncludedWhenUsernamePresent() {
        NacosConnection conn = new NacosConnection("127.0.0.1:8848", "prod", "nacos", "secret");
        Properties p = NacosClientFactory.toProperties(conn);
        assertThat(p.getProperty("username")).isEqualTo("nacos");
        assertThat(p.getProperty("password")).isEqualTo("secret");
    }
}
