package io.yunxi.platform.framework.sync;

import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.QueryIteratorReq;
import io.milvus.orm.iterator.QueryIterator;
import io.milvus.response.QueryResultsWrapper;
import io.yunxi.platform.infra.milvus.MilvusOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Milvus 集合管理服务
 *
 * <p>
 * 封装 Milvus 的集合操作（upsert、计数、存在检查），
 * 通过 MilvusOperations 门面类访问 Milvus，确保 null 安全。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Service
public class MilvusCollectionService {

    private static final Logger log = LoggerFactory.getLogger(MilvusCollectionService.class);

    /** Milvus 操作门面 */
    private final MilvusOperations milvusOps;

    /**
     * 构造函数
     *
     * @param milvusOps Milvus 操作门面
     */
    public MilvusCollectionService(MilvusOperations milvusOps) {
        this.milvusOps = milvusOps;
    }

    /**
     * 批量 upsert 数据到 Milvus（主键已存在则更新，不存在则插入）
     *
     * @param collectionName 集合名称
     * @param dataList       数据列表
     * @param batchSize      批次大小
     */
    public void upsertBatch(String collectionName, List<JsonObject> dataList, int batchSize) {
        milvusOps.upsertBatch(collectionName, dataList, batchSize);
    }

    /**
     * 获取 Milvus 集合中的记录数量
     *
     * @param collectionName 集合名称
     * @return 记录数量，集合不存在返回0，查询失败返回-1，Milvus不可用返回0
     */
    public long getCollectionCount(String collectionName) {
        if (!milvusOps.isAvailable()) {
            return 0;
        }
        MilvusClientV2 client = milvusOps.getRawClient();
        if (client == null) {
            return 0;
        }
        try {
            if (!isCollectionExists(collectionName)) {
                return 0;
            }
            QueryIteratorReq req = QueryIteratorReq.builder()
                    .collectionName(collectionName)
                    .outputFields(Collections.singletonList("id"))
                    .expr("")
                    .batchSize(1000L)
                    .build();

            QueryIterator iterator = client.queryIterator(req);

            long count = 0;
            while (true) {
                List<QueryResultsWrapper.RowRecord> results = iterator.next();
                if (results.isEmpty()) {
                    break;
                }
                count += results.size();
            }
            return count;
        } catch (Exception e) {
            log.error("获取集合 [{}] 数据量失败: {}", collectionName, e.getMessage());
            return -1;
        }
    }

    /**
     * 检查集合是否存在
     *
     * @param collectionName 集合名称
     * @return true-存在，false-不存在
     */
    public boolean isCollectionExists(String collectionName) {
        return milvusOps.hasCollection(collectionName);
    }
}
