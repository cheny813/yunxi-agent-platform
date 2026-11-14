package io.yunxi.runtime.tenant;

import io.yunxi.definition.TenantStorageMeter;
import java.util.ArrayList;
import java.util.List;

/** 测试用租户存储计量替身：记录 recordWrite / recordDelete 收到的 tenant（不含断言逻辑，供断言用）。 */
final class FakeTenantStorageMeter implements TenantStorageMeter {

    final List<String> writes = new ArrayList<>();
    final List<String> deletes = new ArrayList<>();

    @Override
    public void recordWrite(String tenantId) {
        writes.add(tenantId);
    }

    @Override
    public void recordDelete(String tenantId) {
        deletes.add(tenantId);
    }
}
