package io.yunxi.platform.infrastructure.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Nacos 客户端统一工厂（全仓 Nacos SDK 访问单一收敛点）。
 * <p>全仓唯一允许直接调用 com.alibaba.nacos.api.NacosFactory 的位置。MCP 动态注册协调底座、
 * AI Registry、A2A 注册发现三处均经本工厂获取 ConfigService / NamingService，
 * 消除三处散落的 Nacos SDK 直连与 Properties 构造逻辑（即双份真相）。</p>
 * <p>接入 Nacos 的凭据（serverAddr / namespace / username / password）在此集中读取，
 * 调用方只需提供已归一化的 NacosConnection。</p>
 */
@Component
public class NacosClientFactory {

    public ConfigService createConfigService(NacosConnection conn) throws NacosException {
        return NacosFactory.createConfigService(toProperties(conn));
    }

    public NamingService createNamingService(NacosConnection conn) throws NacosException {
        return NacosFactory.createNamingService(toProperties(conn));
    }

    static Properties toProperties(NacosConnection conn) {
        Properties p = new Properties();
        p.setProperty(PropertyKeyConst.SERVER_ADDR, conn.getServerAddr());
        if (conn.getNamespace() != null && !conn.getNamespace().isEmpty()) {
            p.setProperty(PropertyKeyConst.NAMESPACE, conn.getNamespace());
        }
        if (conn.getUsername() != null && !conn.getUsername().isEmpty()) {
            p.setProperty(PropertyKeyConst.USERNAME, conn.getUsername());
            p.setProperty(PropertyKeyConst.PASSWORD, conn.getPassword());
        }
        return p;
    }
}
