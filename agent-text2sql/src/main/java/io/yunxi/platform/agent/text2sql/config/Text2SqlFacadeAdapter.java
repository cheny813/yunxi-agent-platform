package io.yunxi.platform.agent.text2sql.config;

import io.yunxi.platform.agent.text2sql.service.Text2SqlService;
import io.yunxi.platform.spi.text2sql.Text2SqlFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Text2SqlFacade 适配器
 *
 * <p>
 * 将 agent-text2sql 模块的 {@link Text2SqlService} 适配为
 * agent-core 定义的 {@link Text2SqlFacade} 接口，供
 * {@link io.yunxi.platform.shared.service.MultiDatabaseQueryService} 可选注入。
 * </p>
 *
 * <p>
 * 通过 {@code text2sql.enabled=true} 启用，默认关闭。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "text2sql.enabled", havingValue = "true")
@ConditionalOnBean(Text2SqlService.class)
public class Text2SqlFacadeAdapter implements Text2SqlFacade {

    private final Text2SqlService text2SqlService;
    private final Text2SqlProperties properties;

    /**
     * 构造函数
     *
     * @param text2SqlService Text-to-SQL 服务
     * @param properties      Text-to-SQL 配置
     */
    public Text2SqlFacadeAdapter(Text2SqlService text2SqlService, Text2SqlProperties properties) {
        this.text2SqlService = text2SqlService;
        this.properties = properties;
    }

    /**
     * 执行带投票的 Text-to-SQL 生成（委托给 Text2SqlService）
     *
     * @param databaseId 数据库 ID
     * @param question   自然语言问题
     * @return 生成的 SQL 语句
     */
    @Override
    public String text2SqlWithVoting(String databaseId, String question) {
        return text2SqlService.text2SqlWithVoting(databaseId, question);
    }

    /**
     * 判断 Text-to-SQL 能力是否可用
     *
     * @return 配置启用时返回 true
     */
    @Override
    public boolean isAvailable() {
        return properties.isEnabled();
    }
}
