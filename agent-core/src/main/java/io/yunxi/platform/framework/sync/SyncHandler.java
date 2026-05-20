package io.yunxi.platform.framework.sync;

import io.milvus.v2.service.collection.request.CreateCollectionReq;

/**
 * 同步处理器接口
 *
 * <p>
 * 定义单个 Milvus 集合的同步行为，每个集合对应一个实现类。
 * StaticDataSyncService 通过 Handler 列表统一编排同步流程。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface SyncHandler {

    /**
     * 获取 Milvus 集合名称
     *
     * @return 集合名称
     */
    String getCollectionName();

    /**
     * 创建集合的 Schema 定义
     *
     * @return Milvus 集合创建请求
     */
    CreateCollectionReq createSchema();

    /**
     * 执行增量同步
     */
    void sync();
}
