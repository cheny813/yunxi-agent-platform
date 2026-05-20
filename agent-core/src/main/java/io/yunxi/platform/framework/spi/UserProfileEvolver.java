package io.yunxi.platform.framework.spi;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.spi.profile.UserProfileProvider.UserProfile;

import java.util.List;

/**
 * 用户画像演进器接口（SPI）
 *
 * <p>
 * 从对话中提取身份信号，增量合并到已有画像。
 * 配置是"词典"（ConceptRegistry），自动识别是"阅读理解"（UserProfileEvolver）。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface UserProfileEvolver {

    /**
     * 从对话中提取身份信号，合并到已有画像
     *
     * <p>
     * 不替换，只增量：新身份追加，已有身份 confidence 提升。
     * </p>
     *
     * @param current      当前画像
     * @param conversation 对话消息
     * @return 演进后的画像
     */
    UserProfile evolve(UserProfile current, List<Msg> conversation);

    /**
     * 定期整理画像（定时任务触发）
     *
     * <ul>
     *   <li>置信度衰减：长期未提及的身份降低 confidence，低于阈值则移除</li>
     *   <li>去重合并：相似身份合并</li>
     *   <li>冲突解决：互相矛盾的信息以最新为准</li>
     *   <li>personalContext 清理：过时信息移除</li>
     * </ul>
     *
     * @param current 当前画像
     * @return 整理后的画像
     */
    UserProfile consolidate(UserProfile current);
}
