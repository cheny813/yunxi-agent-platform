package io.yunxi.platform.intent.ner;

import java.util.List;
import io.yunxi.platform.intent.Entity;

/**
 * NER 阶段 SPI（M1 规则实现：词典最长匹配 + 正则）。
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public interface NerStage {

    /**
     * 从 query 中抽取实体。
     *
     * @return 永不返回 null；异常由实现方吞掉返回空列表
     */
    List<Entity> extract(String query);
}
