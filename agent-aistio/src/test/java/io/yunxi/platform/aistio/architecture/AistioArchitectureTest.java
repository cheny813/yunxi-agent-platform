package io.yunxi.platform.aistio.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 统一接入网关架构不变量（agent-aistio 镜像，对应 {@code agent-core} 的 {@code GatewayArchitectureTest}）。
 *
 * <p>设计文档 {@code docs/unified-access-gateway-design.md} 规定的架构不变量：禁止 {@code agent-core} 等运行时模块新建散装
 * HTTP 入口。{@code agent-aistio} 原唯一的 {@code @RestController}（{@code AistioContractController}）已在
 * W2 收编到 {@code agent-ingress}（经 {@code agent-core} 的 {@code DataPlaneContract} 接口对接），本模块
 * 当前 0 个 {@code @RestController}。本规则在编译期封死其回潮。</p>
 *
 * <p>本测试位于 {@code agent-aistio} 测试模块，{@code importPackages("io.yunxi.platform")} 在 aistio 的
 * classpath 上只解析到 aistio 自身类（{@code io.yunxi.platform.aistio.*}），故等价于「禁止
 * {@code agent-aistio} 声明 {@code @RestController}」。</p>
 */
class AistioArchitectureTest {

    private static JavaClasses importedClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.yunxi.platform");
    }

    @Test
    void agentAistioMustNotDeclareRestController() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..platform..")
                .should().beAnnotatedWith(RestController.class);
        rule.check(importedClasses());
    }
}
