package io.yunxi.platform.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 统一接入网关架构不变量：HTTP 入口必须集中到统一接入网关模块。
 *
 * <p>物理强制（设计文档 {@code docs/unified-access-gateway-design.md} 规定的架构不变量）：本测试位于 {@code agent-core}
 * 测试模块，{@code importPackages("io.yunxi.platform")} 在 core 的 classpath 上只解析到 core 自身的类
 * （{@code io.yunxi.platform.*} 下各子包），故本规则等价于「禁止 {@code agent-core} 声明
 * {@code @RestController}」——{@code agent-core} 曾是散装 HTTP 入口的来源（原 10 个业务 {@code @RestController}
 * 已全部收编到 {@code agent-ingress}）。把它们新建散装入口的路径在编译期封死，守护「胶水指数 = 1」。</p>
 *
 * <p>{@code agent-aistio} 的镜像规则见 {@code agent-aistio} 测试模块的同名测试（aistio 为 profile 可选模块，
 * 不在 core 的 classpath 上，故需独立覆盖）。{@code agent-muse} 的 {@code MuseSkillController} 也已收编：
 * muse 侧仅保留经 {@code agent-spi} 接口暴露的实现（{@code MuseSkillAdminServiceImpl}），不含
 * {@code @RestController}；REST 端点迁至 {@code agent-ingress} 的 {@code MuseSkillController}
 * （委托 spi 接口）。故本规则覆盖范围内的 core / muse 侧均已无散装入口。</p>
 */
class GatewayArchitectureTest {

    private static JavaClasses importedClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.yunxi.platform");
    }

    @Test
    void agentCoreMustNotDeclareRestController() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..platform..")
                .should().beAnnotatedWith(RestController.class);
        rule.check(importedClasses());
    }
}
