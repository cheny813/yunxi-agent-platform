package io.yunxi.platform.aistio.orchestration;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 基于白名单集合的实体目录实现：registeredIds 非空时，未命中白名单的引用判为未注册。
 */
public class SetEntityRegistry implements EntityRegistry {

    private final Set<String> ids;

    public SetEntityRegistry(Set<String> ids) {
        this.ids = ids == null ? Collections.emptySet() : new HashSet<>(ids);
    }

    @Override
    public boolean isRegistered(String entityId) {
        return ids.contains(entityId);
    }

    @Override
    public Set<String> registeredIds() {
        return ids;
    }
}
