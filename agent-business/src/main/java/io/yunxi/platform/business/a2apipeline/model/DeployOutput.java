package io.yunxi.platform.business.a2apipeline.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * DeployOutput 部署输出
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class DeployOutput {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 部署环境
     */
    private String environment;

    /**
     * 部署URL
     */
    private String deployUrl;

    /**
     * 部署版本
     */
    private String version;

    /**
     * 元数据
     */
    private Map<String, Object> metadata;

    /**
     * 创建成功输出
     *
     * @param environment 部署环境
     * @param deployUrl   部署 URL
     * @return 成功的部署输出实例
     */
    public static DeployOutput success(String environment, String deployUrl) {
        return DeployOutput.builder()
                .success(true)
                .environment(environment)
                .deployUrl(deployUrl)
                .build();
    }

    /**
     * 创建失败输出
     *
     * @param environment 部署环境
     * @return 失败的部署输出实例
     */
    public static DeployOutput failure(String environment) {
        return DeployOutput.builder()
                .success(false)
                .environment(environment)
                .build();
    }
}