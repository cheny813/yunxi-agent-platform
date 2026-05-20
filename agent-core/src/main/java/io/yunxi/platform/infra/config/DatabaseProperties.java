package io.yunxi.platform.infra.config;

import io.yunxi.platform.shared.constants.ConfigDefaults;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据库配置属性
 *
 * @author yunxi-agent-platform
 */
@Data
@Component
@ConfigurationProperties(prefix = "database")
public class DatabaseProperties {

    /**
     * 是否启用数据库持久化
     */
    private boolean enabled = false;

    /**
     * 备份配置
     */
    private Backup backup = new Backup();

    /**
     * 备份配置类
     */
    @Data
    public static class Backup {
        /**
         * 是否启用备份
         */
        private boolean enabled = false;

        /**
         * 备份路径
         */
        private String path = ConfigDefaults.DEFAULT_BACKUP_PATH;

        /**
         * 备份间隔（小时）
         */
        private int intervalHours = ConfigDefaults.DEFAULT_BACKUP_INTERVAL_HOURS;
    }
}
