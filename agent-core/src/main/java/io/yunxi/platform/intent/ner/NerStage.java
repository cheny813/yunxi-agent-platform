package io.yunxi.platform.intent.ner;

import java.util.List;

import io.yunxi.platform.intent.Entity;
import io.yunxi.platform.intent.domain.DomainRuntime;

/**
 * NER 阶段 SPI。
 *
 * <p>实现注册为 Spring Bean；引擎按优先级（低者优先）顺序调用，结果合并。
 * 携带域运行时，规则数据自 {@link DomainRuntime#ner()} 快照读取。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public interface NerStage {

    /**
     * 从 query 抽取实体。
     *
     * @param query   原始查询
     * @param runtime 当前域运行时（快照数据来源；null 时返回空列表）
     * @return 实体列表（升序 start 保证——实现内保证）
     */
    List<Entity> extract(String query, DomainRuntime runtime);
}
