package io.yunxi.app.config;

import io.yunxi.definition.TenantQuota;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TenantQuotaPropertiesTest {

    @Test
    void noDefaultsAndNoTenantOverride_isEmpty() {
        TenantQuotaProperties props = new TenantQuotaProperties();
        assertThat(props.resolve("any")).isEmpty();
    }

    @Test
    void tenantOverrideApplied() {
        TenantQuotaProperties props = new TenantQuotaProperties();
        props.setTenants(Map.of("t1", new TenantQuota(50L, 3L, null)));
        Optional<TenantQuota> q = props.resolve("t1");
        assertThat(q).isPresent();
        assertThat(q.get().dailyRequestLimit()).isEqualTo(50L);
        assertThat(q.get().concurrentSessionLimit()).isEqualTo(3L);
        assertThat(q.get().storageBytesLimit()).isNull();
    }

    @Test
    void tenantStorageOverrideApplied() {
        TenantQuotaProperties props = new TenantQuotaProperties();
        props.setTenants(Map.of("t1", new TenantQuota(null, null, 1000L)));
        Optional<TenantQuota> q = props.resolve("t1");
        assertThat(q).isPresent();
        assertThat(q.get().storageBytesLimit()).isEqualTo(1000L);
    }

    @Test
    void defaultStorageAppliedWhenNoOverride() {
        TenantQuotaProperties props = new TenantQuotaProperties();
        props.setDefaultStorageBytesLimit(500L);
        Optional<TenantQuota> q = props.resolve("unlisted");
        assertThat(q).isPresent();
        assertThat(q.get().storageBytesLimit()).isEqualTo(500L);
    }

    @Test
    void storageOnlyOverrideStillResolves() {
        TenantQuotaProperties props = new TenantQuotaProperties();
        props.setTenants(Map.of("t1", new TenantQuota(null, null, 1000L)));
        Optional<TenantQuota> q = props.resolve("t1");
        assertThat(q).isPresent();
        assertThat(q.get().storageBytesLimit()).isEqualTo(1000L);
        assertThat(q.get().dailyRequestLimit()).isNull();
    }

    @Test
    void defaultsAppliedWhenNoOverride() {
        TenantQuotaProperties props = new TenantQuotaProperties();
        props.setDefaultDailyRequestLimit(200L);
        props.setDefaultConcurrentSessionLimit(5L);
        Optional<TenantQuota> q = props.resolve("unlisted");
        assertThat(q).isPresent();
        assertThat(q.get().dailyRequestLimit()).isEqualTo(200L);
        assertThat(q.get().concurrentSessionLimit()).isEqualTo(5L);
    }

    @Test
    void overrideFieldOverlaysDefault() {
        TenantQuotaProperties props = new TenantQuotaProperties();
        props.setDefaultDailyRequestLimit(200L);
        props.setDefaultConcurrentSessionLimit(5L);
        props.setTenants(Map.of("t1", new TenantQuota(10L, null, null)));
        Optional<TenantQuota> q = props.resolve("t1");
        assertThat(q).isPresent();
        assertThat(q.get().dailyRequestLimit()).isEqualTo(10L);
        assertThat(q.get().concurrentSessionLimit()).isEqualTo(5L);
        assertThat(q.get().storageBytesLimit()).isNull();
    }
}
