package io.yunxi.platform.gateway.session;

import io.yunxi.platform.gateway.model.GatewaySession;
import java.util.List;
import java.util.Optional;

/**
 * 会话存储接口 - 支持内存和持久化两种模式
 * 生产级设计：线程安全、异常隔离、优雅降级
 */
public interface SessionStore {

    /**
     * 保存会话
     * @param session 会话对象
     * @return 是否保存成功
     */
    boolean save(GatewaySession session);

    /**
     * 根据ID获取会话
     * @param sessionKey 会话Key
     * @return 会话Optional包装
     */
    Optional<GatewaySession> findById(String sessionKey);

    /**
     * 根据用户ID获取会话列表
     * @param userId 用户ID
     * @return 会话列表
     */
    List<GatewaySession> findByUserId(String userId);

    /**
     * 删除会话
     * @param sessionKey 会话Key
     * @return 是否删除成功
     */
    boolean delete(String sessionKey);

    /**
     * 删除用户所有会话
     * @param userId 用户ID
     * @return 删除的会话数量
     */
    int deleteByUserId(String userId);

    /**
     * 清理过期会话
     * @return 清理的会话数量
     */
    int cleanupExpired();

    /**
     * 获取所有活跃会话数量
     * @return 会话数量
     */
    long count();

    /**
     * 获取存储类型
     * @return 存储类型名称
     */
    String getStoreType();

    /**
     * 检查存储是否可用
     * @return 是否可用
     */
    boolean isAvailable();

    /**
     * 关闭存储，释放资源
     */
    void close();
}
