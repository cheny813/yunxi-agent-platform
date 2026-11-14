package io.yunxi.runtime.rag;

import io.agentscope.core.agent.RuntimeContext;
import io.yunxi.runtime.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRetrieveToolTest {

    private static final String TEST_TENANT = "t-real";

    @BeforeEach
    void bindTenant() {
        TenantContext.set(TEST_TENANT);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void retrieveUsesRealUserIdFromRuntimeContext() {
        DocumentChunkStore fake = new DocumentChunkStore() {
            @Override
            public void save(DocumentChunk chunk) {
            }

            @Override
            public List<DocumentChunk> findRelevant(String tenant, String collection, String userId, String query, int topK) {
                assertEquals(TEST_TENANT, tenant);
                assertEquals("kb", collection);
                assertEquals("u-real", userId);
                assertEquals("问题", query);
                assertEquals(5, topK);
                return List.of(
                        DocumentChunk.of(TEST_TENANT, "kb", "u-real", "c1", "答案一", null, 30),
                        DocumentChunk.of(TEST_TENANT, "kb", "u-real", "c2", "答案二", null, 30));
            }

            @Override
            public void delete(String chunkId) {
            }

            @Override
            public List<DocumentChunk> findAll(String tenant) {
                return List.of();
            }
        };
        RagRetrieveTool tool = new RagRetrieveTool(fake);
        RuntimeContext ctx = RuntimeContext.builder().userId("u-real").build();
        String result = tool.retrieve("kb", "问题", null, ctx);
        assertTrue(result.contains("答案一"));
        assertTrue(result.contains("答案二"));
        assertTrue(result.contains("【分块 1】"));
    }

    @Test
    void retrieveFallsBackToAnonymousWhenNoUserId() {
        DocumentChunkStore fake = new DocumentChunkStore() {
            @Override
            public void save(DocumentChunk chunk) {
            }

            @Override
            public List<DocumentChunk> findRelevant(String tenant, String collection, String userId, String query, int topK) {
                assertEquals(TEST_TENANT, tenant);
                assertEquals("anonymous", userId);
                return List.of();
            }

            @Override
            public void delete(String chunkId) {
            }

            @Override
            public List<DocumentChunk> findAll(String tenant) {
                return List.of();
            }
        };
        RagRetrieveTool tool = new RagRetrieveTool(fake);
        String result = tool.retrieve("kb", "问题", 3, null);
        assertTrue(result.contains("未检索到"));
    }

    @Test
    void retrieveRejectsCollectionOutsidePlatformAllowlist() {
        RagProperties props = new RagProperties();
        props.setAllowedCollections(List.of("kb-a", "kb-b"));
        DocumentChunkStore fake = new DocumentChunkStore() {
            @Override
            public void save(DocumentChunk chunk) {
            }

            @Override
            public List<DocumentChunk> findRelevant(String tenant, String c, String u, String q, int k) {
                throw new AssertionError("白名单外的集合不应调用 findRelevant");
            }

            @Override
            public void delete(String chunkId) {
            }

            @Override
            public List<DocumentChunk> findAll(String tenant) {
                return List.of();
            }
        };
        RagRetrieveTool tool = new RagRetrieveTool(fake, props);
        RuntimeContext ctx = RuntimeContext.builder().userId("u-real").build();
        String result = tool.retrieve("kb-x", "问题", null, ctx);
        assertTrue(result.contains("无权访问知识库集合"));
        assertTrue(result.contains("kb-x"));
    }

    @Test
    void retrieveAllowsCollectionInsidePlatformAllowlist() {
        RagProperties props = new RagProperties();
        props.setAllowedCollections(List.of("kb-a"));
        DocumentChunkStore fake = new DocumentChunkStore() {
            @Override
            public void save(DocumentChunk chunk) {
            }

            @Override
            public List<DocumentChunk> findRelevant(String tenant, String c, String u, String q, int k) {
                assertEquals(TEST_TENANT, tenant);
                assertEquals("kb-a", c);
                return List.of(DocumentChunk.of(TEST_TENANT, "kb-a", "u-real", "c1", "授权答案", null, 30));
            }

            @Override
            public void delete(String chunkId) {
            }

            @Override
            public List<DocumentChunk> findAll(String tenant) {
                return List.of();
            }
        };
        RagRetrieveTool tool = new RagRetrieveTool(fake, props);
        RuntimeContext ctx = RuntimeContext.builder().userId("u-real").build();
        String result = tool.retrieve("kb-a", "问题", null, ctx);
        assertTrue(result.contains("授权答案"));
    }
}
