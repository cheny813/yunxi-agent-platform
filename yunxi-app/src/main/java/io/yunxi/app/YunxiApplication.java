package io.yunxi.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.quartz.QuartzAutoConfiguration;

/**
 * 应用平面。yunxi 目标架构的可执行入口。启动后装配全部核心 Bean，并将四协议聚合入口
 * （{@code IngressGateway}）暴露为 HTTP 服务。
 *
 * <p>排除 Spring Boot 自带的 Quartz 自动配置：调度能力默认关闭，classpath 上存在 quartz
 * 不应让框架自动创建调度器 Bean 与线程池。开启时由 {@link SchedulerConfiguration}
 * 按框架原生入口自建并持有调度器，生命周期由应用上下文统一收口。</p>
 */
@SpringBootApplication(
        scanBasePackages = {"io.yunxi.app", "io.yunxi.fabric.aistio", "io.yunxi.runtime", "io.yunxi.intelligence",
                "io.yunxi.a2ui"},
        exclude = QuartzAutoConfiguration.class)
public class YunxiApplication {

    /** 平台入口：启动 Spring Boot 应用，装配全部核心 Bean 并暴露四协议聚合入口。 */
    public static void main(String[] args) {
        SpringApplication.run(YunxiApplication.class, args);
    }
}
