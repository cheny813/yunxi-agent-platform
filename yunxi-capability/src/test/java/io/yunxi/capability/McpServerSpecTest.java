package io.yunxi.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link McpServerSpec} 便捷工厂与传输枚举。 */
class McpServerSpecTest {

    @Test
    void sseFactoryDefaultsEnabledWithUrl() {
        McpServerSpec spec = McpServerSpec.sse("search", "http://localhost:9000");

        assertEquals(McpServerSpec.Transport.SSE, spec.transport());
        assertEquals("http://localhost:9000", spec.url());
        assertTrue(spec.enabled());
        assertNull(spec.command());
    }

    @Test
    void stdioFactoryDefaultsEnabledWithoutUrl() {
        McpServerSpec spec = McpServerSpec.stdio("git", "npx", List.of("git-mcp"), Map.of("K", "V"));

        assertEquals(McpServerSpec.Transport.STDIO, spec.transport());
        assertEquals("npx", spec.command());
        assertEquals(List.of("git-mcp"), spec.args());
        assertTrue(spec.enabled());
        assertNull(spec.url());
    }
}
