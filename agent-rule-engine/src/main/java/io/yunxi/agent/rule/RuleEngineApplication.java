package io.yunxi.agent.rule;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 规则引擎主应用
 * 
 * 轻量级规则引擎服务，支持：
 * - 动态规则配置
 * - 规则执行引擎
 * - REST API 管理
 * - 执行日志审计
 * 
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@SpringBootApplication
@EnableAsync
public class RuleEngineApplication {
    
    private static final Logger log = LoggerFactory.getLogger(RuleEngineApplication.class);
    
    public static void main(String[] args) {
        SpringApplication.run(RuleEngineApplication.class, args);
        
        log.info("\n" +
            "=================================================\n" +
            "   Agent Rule Engine Started Successfully!       \n" +
            "=================================================\n" +
            "   REST API: http://localhost:40002/api/rules     \n" +
            "   Health:   http://localhost:40002/actuator/health\n" +
            "=================================================\n"
        );
    }
}