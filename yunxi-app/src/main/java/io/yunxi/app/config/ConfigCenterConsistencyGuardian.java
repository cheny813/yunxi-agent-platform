package io.yunxi.app.config;

import io.yunxi.config.ConfigCenter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Agent 定义跨实例一致性启动护栏。
 *
 * <p>多副本部署（{@code yunxi.cluster.mode=cluster}）时，Agent 定义目录必须来自共享配置中心：
 * 非发布包管辖的定义若只写入本实例，各实例目录随之分叉；即使发布时定义的 Agent 相同，管理端经配置中心
 * 写入的定义也只在单实例可见（默认配置中心为进程内实现）。这是"多副本却各存一份定义"的静默一致性缺陷。
 *
 * <p>本护栏在上下文就绪后查询装配进容器的 {@link ConfigCenter} 是否真的把写入落到共享存储
 * （{@link ConfigCenter#isSharedAcrossInstances()}，覆盖"Nacos 已配置但连接失败降级"这一形态），据此：
 * <ul>
 *   <li>默认（{@code enforced=false}）：仅记录 WARN，单副本以 cluster 形态运行仍是合法部署，不阻断；</li>
 *   <li>enforced=true：直接 fail-closed，避免多副本部署在定义不共享时静默运行。</li>
 * </ul>
 *
 * <p>修复手段是让部署显式装配共享配置中心后端（{@code yunxi.config.nacos.enabled=true}），
 * 而非本护栏兜底。与 {@code CrossInstanceTurnGateGuardian} 同口径：默认可观测、强制时拒绝启动。
 */
@Component
public class ConfigCenterConsistencyGuardian implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ConfigCenterConsistencyGuardian.class);

    private final ConfigCenter configCenter;
    private final String clusterMode;
    private final boolean consistencyEnforced;

    public ConfigCenterConsistencyGuardian(
            ConfigCenter configCenter,
            @Value("${yunxi.cluster.mode:standalone}") String clusterMode,
            @Value("${yunxi.config.consistency.enforced:false}") boolean consistencyEnforced) {
        this.configCenter = configCenter;
        this.clusterMode = clusterMode;
        this.consistencyEnforced = consistencyEnforced;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!"cluster".equalsIgnoreCase(clusterMode)) {
            // 单副本部署：进程内定义目录不与其它实例分叉，无缺陷。
            return;
        }
        if (configCenter.isSharedAcrossInstances()) {
            // 写入真正落共享存储，定义跨实例一致。
            return;
        }
        // 区分两种成因：从未接入共享存储，与"已配置 Nacos 但连接失败退化为内存"——后者的修复手段不同
        // （前者改配置，后者先确认配置中心可达），且它表明部署方本以为定义已共享。
        String guidance = configCenter instanceof NacosBackedConfigCenter
                ? "当前为 cluster 部署，Nacos 已配置但连接失败，配置中心已降级为进程内实现；"
                        + "Agent 定义不跨实例共享，各副本目录将静默分叉且重启后丢失运行期变更。"
                        + "请确认 Nacos 可达（yunxi.config.nacos.server-addr）。"
                : "当前为 cluster 部署，但配置中心为进程内实现，Agent 定义不跨实例共享；"
                        + "运行期创建的 Agent 只在单实例生效，各副本定义目录将静默分叉。请设置"
                        + " yunxi.config.nacos.enabled=true 装配共享配置中心。";
        if (consistencyEnforced) {
            throw new IllegalStateException(
                    "Agent 定义未跨实例共享却要求强制（yunxi.config.consistency.enforced=true）：" + guidance);
        }
        log.warn("Agent 定义未跨实例共享（advisory）：{} 若为多副本部署，设置"
                + " yunxi.config.consistency.enforced=true 可在定义不共享时 fail-closed。", guidance);
    }
}
