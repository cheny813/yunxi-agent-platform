package io.yunxi.platform.intent.domain;

import java.time.Instant;

import io.yunxi.platform.intent.snapshot.EntityDictSnapshot;
import io.yunxi.platform.intent.snapshot.MappingSnapshot;
import io.yunxi.platform.intent.snapshot.TermSnapshot;
import io.yunxi.platform.intent.snapshot.TreeSnapshot;

/**
 * 领域运行时。
 *
 * <p>一个域的不可变聚合，只持合并后数据（base 与业务域 merge 后的最终视图），
 * 消费方零合并逻辑。四份快照对应当前域的全部规则数据。</p>
 *
 * @param name     领域名（如 "base" / "nutrition" / "ecommerce"）
 * @param version  单调递增版本号（热更新 +1），供缓存失效/观测
 * @param loadedAt 加载时间
 * @param ner      实体词典快照
 * @param terms    术语表快照
 * @param tree     意图树快照
 * @param mapping  映射表快照
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public record DomainRuntime(
        String name,
        long version,
        Instant loadedAt,
        EntityDictSnapshot ner,
        TermSnapshot terms,
        TreeSnapshot tree,
        MappingSnapshot mapping) {

    /** 基础域名（单域模式兜底） */
    public static final String BASE = "base";

    /** 空域运行时（降级兜底，未知域回落） */
    public static DomainRuntime empty(String name) {
        return new DomainRuntime(name, 0L, Instant.EPOCH,
                EntityDictSnapshot.empty(), TermSnapshot.empty(),
                TreeSnapshot.empty(), MappingSnapshot.empty());
    }
}
