package io.yunxi.platform.business.a2apipeline.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * DeployResult 部署结果
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Data
@Builder
public class DeployResult {

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
     * 输出数据
     */
    private Map<String, Object> output;

    /**
     * 创建成功结果
     *
     * @param environment 部署环境
     * @param deployUrl   部署 URL
     * @return 成功的部署结果实例
     */
    public static DeployResult success(String environment, String deployUrl) {
        return DeployResult.builder()
                .success(true)
                .environment(environment)
                .deployUrl(deployUrl)
                .build();
    }

    /**
     * 创建失败结果
     *
     * @param environment 部署环境
     * @return 失败的部署结果实例
     */
    public static DeployResult failure(String environment) {
        return DeployResult.builder()
                .success(false)
                .environment(environment)
                .build();
    }
}