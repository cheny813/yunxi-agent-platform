package io.yunxi.platform.muse.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 统一接入网关架构不变量（agent-muse 镜像，对应 {@code agent-core} 的 {@code GatewayArchitectureTest}
 * 与 {@code agent-aistio} 的 {@code AistioArchitectureTest}）。
 *
 * <p>设计文档 {@code docs/unified-access-gateway-design.md} 规定的架构不变量：散装 HTTP 入口必须集中到
 * {@code agent-ingress}。{@code agent-muse} 原唯一的 {@code @RestController}（{@code MuseSkillController}，
 * 暴露 {@code /api/muse/skills/*}）已在 2026-09-24 收编到 {@code agent-ingress}（muse 侧仅保留经
 * {@code agent-spi} 的 {@code MuseSkillAdminService} 接口暴露的 {@code @Service} 实现，无 {@code @RestController}）。
 * 本规则在编译期封死其回潮。</p>
 *
 * <p>本测试位于 {@code agent-muse} 测试模块，{@code importPackages("io.yunxi.platform.muse")} 只解析 muse 自身类，
 * 故等价于「禁止 {@code agent-muse} 声明 {@code @RestController}」。</p>
 */
class MuseGatewayArchitectureTest {

    private static JavaClasses importedClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.yunxi.platform.muse");
    }

    @Test
    void agentMuseMustNotDeclareRestController() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..muse..")
                .should().beAnnotatedWith(RestController.class);
        rule.check(importedClasses());
    }
}
