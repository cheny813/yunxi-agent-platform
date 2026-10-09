package io.yunxi.platform.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 架构纪律门禁：把「横切关切 → 唯一权威」的映射做成机器可校验事实，作为 CI 的结构性防护。
 * 任何在别处开第二份真相源的提交都会让构建失败。
 *
 * <p>本测试把 docs/application-agent-architecture-upgrade.md 中「横切关切 → 唯一权威」的映射
 * 做成机器可校验事实（对应防护随默认 reactor 始终运行，不受集成测试跳过开关影响）：
 * 任何在别处开第二份真相源的提交都会让构建失败。</p>
 *
 * <p>覆盖的规则（A / C / 红线）：
 * <ul>
 *   <li>规则 A（执行单一入口）：全仓仅 {@code AgentInvoker} 可直接调用框架
 *       {@code HarnessAgent.streamEvents / call} 与 {@code ReActAgent.call}；其余类直达即失败。</li>
 *   <li>规则 C（中间件单一装配）：{@code SessionToolGroupActivationMiddleware}（会话级工具组激活，
 *       跨协议横切）仅允许在 {@code AgentConfigurer.configureMiddlewares} 经 {@code ObjectProvider} 装配，
 *       其余处 {@code new} 即失败。</li>
 *   <li>红线（无跨产品模块耦合）：各产品模块（execution / ingress / muse / text2sql / aistio）
 *       只能依赖 {@code agent-core}（及 spi / config 叶子），不得互相依赖——杜绝「同一横切关切两份实现」。</li>
 * </ul>
 *
 * <p>B（身份单一构造器）/ D（反桥接·双取消权威）/ E（SSE framing 同协议唯一）属架构纪律，
 * 其静态判定需比 ArchUnit 简单 API 更深的语义分析，留作人工 review 项，不在此门禁内机械强制，
 * 以免产生误杀导致整构建转红。</p>
 */
class ArchitectureAuthorityTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.yunxi.platform");

    /** 框架 Agent 调用入口的两种目标类型：HarnessAgent / ReActAgent。 */
    private static boolean isFrameworkAgentOwner(String owner) {
        return owner.equals("io.agentscope.harness.agent.HarnessAgent")
                || owner.equals("io.agentscope.core.agent.ReActAgent");
    }

    @Test
    void ruleA_executionSingleEntry() {
        // 全仓仅 AgentInvoker 作为调用方，方可直达框架 Agent 的 streamEvents / call。
        DescribedPredicate<JavaMethodCall> bypassesInvocationFacade =
                DescribedPredicate.describe(
                        "call streamEvents/call directly on a framework Agent from a class other than AgentInvoker",
                        call -> {
                            String owner = call.getTarget().getOwner().getName();
                            String name = call.getTarget().getName();
                            boolean invocation = name.equals("streamEvents") || name.equals("call");
                            if (!isFrameworkAgentOwner(owner) || !invocation) {
                                return false;
                            }
                            boolean callerIsFacade =
                                    call.getOrigin().getOwner().getSimpleName().equals("AgentInvoker");
                            return !callerIsFacade;
                        });

        ArchRule rule = ArchRuleDefinition.noClasses()
                .should().callMethodWhere(bypassesInvocationFacade);
        rule.check(CLASSES);
    }

    @Test
    void ruleC_sessionToolGroupMiddlewareSingleAssembly() {
        // SessionToolGroupActivationMiddleware 仅允许在 AgentConfigurer 中装配，其余处实例化即失败。
        DescribedPredicate<JavaConstructorCall> instantiatesOutsideConfigurer =
                DescribedPredicate.describe(
                        "instantiate SessionToolGroupActivationMiddleware outside AgentConfigurer",
                        call -> {
                            boolean targetIsSessionToolGroup = call.getTarget().getOwner().getName()
                                    .equals("io.yunxi.platform.agent.middleware.SessionToolGroupActivationMiddleware");
                            boolean callerIsConfigurer =
                                    call.getOrigin().getOwner().getSimpleName().equals("AgentConfigurer");
                            return targetIsSessionToolGroup && !callerIsConfigurer;
                        });

        ArchRule rule = ArchRuleDefinition.noClasses()
                .should().callConstructorWhere(instantiatesOutsideConfigurer);
        rule.check(CLASSES);
    }

    /**
     * 红线（无跨产品模块耦合）的两个可机械化校验的不变量（其余见架构纪律）：
     * <ol>
     *   <li>agent-aistio（控制面集成）单向依赖 agent-core，绝不反向/横向依赖其它产品模块
     *       （execution / ingress / muse / text2sql）—— 这是 R-I 收敛的落点，也是「同一横切关切全仓单一实现位置」的模块级表达。</li>
     *   <li>任何产品模块不得依赖 agent-aistio：控制面集成必须是叶子消费者，
     *       若被反向依赖即出现双份真相源（控制面既被 yunxi 执行、又回灌 yunxi 业务）。</li>
     * </ol>
     * 注：agent-ingress → agent-execution 属正常分层（web 入口编排执行引擎），不在红线禁止之列。
     */
    @Test
    void redline_aistioSingleDirectionDependency() {
        // (1) aistio 不得依赖其它产品模块（仅可依赖 agent-core / spi / config 叶子）。
        ArchRule aistioOnlyCore = ArchRuleDefinition.noClasses()
                .that().resideInAPackage("io.yunxi.platform.aistio..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.yunxi.platform.execution..",
                        "io.yunxi.platform.ingress..",
                        "io.yunxi.platform.muse..",
                        "io.yunxi.platform.agent.text2sql..");
        aistioOnlyCore.check(CLASSES);

        // (2) 任何产品模块不得依赖 aistio（控制面集成必须是叶子消费者）。
        ArchRule noModuleDependsOnAistio = ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage("io.yunxi.platform.aistio..")
                .should().dependOnClassesThat().resideInAPackage("io.yunxi.platform.aistio..");
        noModuleDependsOnAistio.check(CLASSES);
    }

    /**
     * 开源发布门禁：扫描全部可发布源码与配置（排除 docs/、CHANGELOG.md 及构建/缓存目录），
     * 禁止出现指向内部规格的私有引用（如章节号、内部代号等）。
     * 该门禁随默认 reactor 始终运行，任何新增的内部引用都会让构建失败，从机制上避免无法开源的描述重复出现。
     */
    @Test
    void openSourceReferenceHygiene() throws IOException {
        Path repoRoot = findRepoRoot();
        Pattern forbidden = Pattern.compile("§\\d|升级" + "方案|母" + "本|主" + "设计|RC" + "3|骑" + "墙|设计" + "文档|设计" + " §");
        Set<String> skipDirs = Set.of("docs", "target", "tmp", ".git", "node_modules",
                "build-out", ".idea", ".vscode", ".agentscope", ".codebuddy");
        Set<String> allowedExt = Set.of(".java", ".xml", ".yml", ".yaml", ".properties", ".json",
                ".ps1", ".bat", ".sh", ".sql", ".ts", ".js", ".html", ".css", ".md");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(repoRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(file -> {
                        String name = file.getFileName().toString();
                        if (name.equals("CHANGELOG.md")) {
                            return false;
                        }
                        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
                        if (!allowedExt.contains(ext)) {
                            return false;
                        }
                        String rel = file.toString().replace(repoRoot.toString(), "");
                        for (String seg : rel.split("[/\\\\]")) {
                            if (skipDirs.contains(seg)) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .forEach(file -> {
                        try {
                            List<String> lines = Files.readAllLines(file);
                            for (int i = 0; i < lines.size(); i++) {
                                if (forbidden.matcher(lines.get(i)).find()) {
                                    violations.add(file + ":" + (i + 1) + ": " + lines.get(i).trim());
                                }
                            }
                        } catch (IOException ignored) {
                            // 跳过不可读文件
                        }
                    });
        }
        if (!violations.isEmpty()) {
            Assertions.fail("发现开源违禁的内部引用（需清除后才能开源）：\n" + String.join("\n", violations));
        }
    }

    private static Path findRepoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未找到仓库根目录");
    }
}
