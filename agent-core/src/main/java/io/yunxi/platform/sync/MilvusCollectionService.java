package io.yunxi.platform.sync;

import com.google.gson.JsonObject;
import io.yunxi.platform.persistence.milvus.MilvusOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Milvus 集合＄服务
 *
 * <p>
 * 封 Milvus 的集合操作（upsert、数€存ㄦ鏌ワ級锛? * 通过 MilvusOperations 闂ㄩ类闂?Milvus，‘淇?null 安全? * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Service
public class MilvusCollectionService {

    private static final Logger log = LoggerFactory.getLogger(MilvusCollectionService.class);

    /** Milvus 操作ㄩ潰 */
    private final MilvusOperations milvusOps;

    /**
     * 构€函?     *
     * @param milvusOps Milvus 操作ㄩ潰
     */
    public MilvusCollectionService(MilvusOperations milvusOps) {
        this.milvusOps = milvusOps;
    }

    /**
     * 批量 upsert 数据?Milvus（主存在则更新，不存ㄥ插入?     *
     * @param collectionName 集合名О
     * @param dataList       数据列〃
     * @param batchSize      批澶у皬
     */
    public void upsertBatch(String collectionName, List<JsonObject> dataList, int batchSize) {
        milvusOps.upsertBatch(collectionName, dataList, batchSize);
    }

    /**
     * 获取 Milvus 集合记录数量
     * <p>
     * 使用 {@link MilvusOperations#getCollectionStatistics(String)} 从元数据获取行数?     * O(1) 复杂︼相比 QueryIterator 遍历℃更高效可靠€?     * </p>
     *
     * @param collectionName 集合名О
     * @return 记录数量，集合不存在返回0，查㈠け璐ヨ繑鍥?1，Milvus不可ㄨ繑鍥?
     */
    public long getCollectionCount(String collectionName) {
        if (!milvusOps.isAvailable()) {
            return 0;
        }
        if (!isCollectionExists(collectionName)) {
            return 0;
        }
        return milvusOps.getCollectionStatistics(collectionName);
    }

    /**
     * 妫€鏌ラ合是﹀瓨鍦?     *
     * @param collectionName 集合名О
     * @return true-存在，false-不存?     */
    public boolean isCollectionExists(String collectionName) {
        return milvusOps.hasCollection(collectionName);
    }
}
