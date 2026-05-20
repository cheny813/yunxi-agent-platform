package io.yunxi.platform.infra.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.yunxi.platform.shared.mapper.UserFileMapper;
import jakarta.annotation.PostConstruct;

/**
 * 数据库表自动创建配置
 *
 * <p>
 * 在应用启动时自动检查并创建必要的数据库表
 * SQL语句通过MyBatis Mapper执行，符合最佳实践
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "file-upload.enabled", havingValue = "true", matchIfMissing = true)
public class DatabaseTableInitializer {

    /** 用户文件 Mapper */
    @Autowired
    private UserFileMapper userFileMapper;

    /**
     * 初始化：创建必要的数据库表
     */
    @PostConstruct
    public void init() {
        try {
            log.info("开始检查并创建文件上传相关数据库表...");

            createUserFilesTable();

            log.info("数据库表检查和创建完成");

        } catch (Exception e) {
            log.error("数据库表初始化失败", e);
        }
    }

    /**
     * 创建用户文件表
     */
    private void createUserFilesTable() {
        try {
            // 检查表是否存在
            boolean exists = userFileMapper.checkUserFilesTableExists();

            if (exists) {
                log.debug("用户文件表已存在，跳过创建");
                return;
            }

            // 创建表
            userFileMapper.createUserFilesTable();
            log.info("用户文件表创建成功: user_files");

        } catch (Exception e) {
            log.error("创建用户文件表失败", e);
        }
    }
}
