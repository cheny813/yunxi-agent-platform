package io.yunxi.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 集群名与诊断标量契约：前者必须全实例共享，后者只作日志标识。 */
class SchedulerConfigurationTest {

    @Test
    void clusterNameIsStableAcrossInstances() {
        assertEquals("yunxi-scheduler-cluster", SchedulerConfiguration.CLUSTER_NAME,
                "JDBC JobStore 按 SCHED_NAME 分区，集群名不得随机化");
    }

    @Test
    void generatedDiagnosticIdsAreUniqueAndNamespaced() {
        String first = SchedulerConfiguration.resolveSchedulerId("");
        String second = SchedulerConfiguration.resolveSchedulerId(null);

        assertTrue(first.startsWith("yunxi-scheduler-"));
        assertNotEquals(first, second, "诊断标量每次生成须唯一");
        assertNotEquals(SchedulerConfiguration.CLUSTER_NAME, first,
                "诊断标量不得被当作集群 instanceName 使用");
    }

    @Test
    void explicitlyConfiguredIdIsPreserved() {
        assertEquals("explicit-scheduler",
                SchedulerConfiguration.resolveSchedulerId("explicit-scheduler"));
    }
}
