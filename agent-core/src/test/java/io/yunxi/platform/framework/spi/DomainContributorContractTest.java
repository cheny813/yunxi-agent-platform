package io.yunxi.platform.framework.spi;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DomainContributor SPI 契约测试
 */
public abstract class DomainContributorContractTest extends AbstractSpiContractTest<DomainContributor> {

    @Override
    protected Class<DomainContributor> spiType() {
        return DomainContributor.class;
    }

    @Test
    void getDomainPatterns_shouldReturnNonNull() {
        Map<String, List<Pattern>> patterns = createInstance().getDomainPatterns();
        assertNotNull(patterns, "getDomainPatterns() 不应返回 null");
    }

    @Test
    void getAgentCapabilities_shouldReturnNonNull() {
        Map<String, Set<String>> capabilities = createInstance().getAgentCapabilities();
        assertNotNull(capabilities, "getAgentCapabilities() 不应返回 null");
    }

    @Test
    void getAgentCapabilities_shouldNotContainNullValues() {
        Map<String, Set<String>> capabilities = createInstance().getAgentCapabilities();
        for (Map.Entry<String, Set<String>> entry : capabilities.entrySet()) {
            assertNotNull(entry.getKey(), "capabilities map 的 key 不应为 null");
            assertNotNull(entry.getValue(), "capabilities map 的 value 不应为 null");
        }
    }
}
