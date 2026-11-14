package io.yunxi.runtime.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

import io.yunxi.config.ConfigCenter;
import io.yunxi.definition.Tenant;
import io.yunxi.definition.TenantStatus;
import java.util.List;
import io.yunxi.runtime.tenant.TenantContext;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TenantLifecycleServiceTest {

    private final ConfigCenter configCenter = mock(ConfigCenter.class);
    private final TenantLifecycleService service = new TenantLifecycleService(configCenter);

    @Test
    void register_persistsViaConfigCenter() {
        Tenant t = Tenant.active("t1", "Tenant One");
        service.register(t);
        verify(configCenter).registerTenant(t);
    }

    @Test
    void getTenant_delegatesToConfigCenter() {
        Tenant t = Tenant.active("t1", "Tenant One");
        when(configCenter.getTenant("t1")).thenReturn(Optional.of(t));
        assertEquals(Optional.of(t), service.getTenant("t1"));
    }

    @Test
    void listTenants_delegatesToConfigCenter() {
        when(configCenter.listTenants()).thenReturn(List.of(Tenant.active("t1", "A")));
        assertEquals(1, service.listTenants().size());
    }

    @Test
    void markSuspended_transitionsActiveToSuspended() {
        Tenant active = Tenant.active("t1", "A");
        when(configCenter.getTenant("t1")).thenReturn(Optional.of(active));
        service.markSuspended("t1");
        ArgumentCaptor<Tenant> captor = ArgumentCaptor.forClass(Tenant.class);
        verify(configCenter).registerTenant(captor.capture());
        assertEquals(TenantStatus.SUSPENDED, captor.getValue().status());
        assertEquals("t1", captor.getValue().tenantId());
    }

    @Test
    void markArchived_transitionsActiveToArchived() {
        Tenant active = Tenant.active("t1", "A");
        when(configCenter.getTenant("t1")).thenReturn(Optional.of(active));
        service.markArchived("t1");
        ArgumentCaptor<Tenant> captor = ArgumentCaptor.forClass(Tenant.class);
        verify(configCenter).registerTenant(captor.capture());
        assertEquals(TenantStatus.ARCHIVED, captor.getValue().status());
        assertEquals("t1", captor.getValue().tenantId());
    }

    @Test
    void markSuspended_onUnknownTenant_throws() {
        when(configCenter.getTenant("ghost")).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.markSuspended("ghost"));
        verify(configCenter, never()).registerTenant(any());
    }

    @Test
    void getStatus_returnsConfigCenterStatus() {
        Tenant suspended = Tenant.active("t1", "A").withStatus(TenantStatus.SUSPENDED);
        when(configCenter.getTenant("t1")).thenReturn(Optional.of(suspended));
        assertEquals(TenantStatus.SUSPENDED, service.getStatus("t1"));
    }

    @Test
    void isActive_trueForActiveAndUndefined_falseForSuspended() {
        Tenant active = Tenant.active("t1", "A");
        Tenant suspended = Tenant.active("s1", "S").withStatus(TenantStatus.SUSPENDED);
        when(configCenter.getTenant("t1")).thenReturn(Optional.of(active));
        when(configCenter.getTenant("s1")).thenReturn(Optional.of(suspended));
        when(configCenter.getTenant("t2")).thenReturn(Optional.empty());
        assertTrue(service.isActive("t1"));
        assertTrue(service.isActive("t2")); // 未定义 fail-open 视为 ACTIVE
        assertFalse(service.isActive("s1"));
    }

    @Test
    void admit_allowsActiveAndUndefined_rejectsSuspendedAndArchived() {
        Tenant active = Tenant.active("a1", "A");
        Tenant suspended = Tenant.active("s1", "S").withStatus(TenantStatus.SUSPENDED);
        Tenant archived = Tenant.active("x1", "X").withStatus(TenantStatus.ARCHIVED);
        when(configCenter.getTenant("a1")).thenReturn(Optional.of(active));
        when(configCenter.getTenant("s1")).thenReturn(Optional.of(suspended));
        when(configCenter.getTenant("x1")).thenReturn(Optional.of(archived));
        when(configCenter.getTenant("u1")).thenReturn(Optional.empty());

        assertEquals(TenantStatus.ACTIVE, service.admit("a1"));
        assertEquals(TenantStatus.ACTIVE, service.admit("u1")); // 未定义 fail-open
        assertThrows(TenantNotServiceableException.class, () -> service.admit("s1"));
        assertThrows(TenantNotServiceableException.class, () -> service.admit("x1"));
    }

    @Test
    void admit_bindsStatusIntoTenantContext() {
        Tenant active = Tenant.active("a1", "A");
        when(configCenter.getTenant("a1")).thenReturn(Optional.of(active));
        service.admit("a1");
        assertEquals("a1", TenantContext.current());
        assertEquals(TenantStatus.ACTIVE, TenantContext.status());
        TenantContext.clear();
    }
}
