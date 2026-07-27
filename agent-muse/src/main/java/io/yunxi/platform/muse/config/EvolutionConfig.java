package io.yunxi.platform.muse.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * MUSE 自进化引擎配置。
 *
 * <p>仅描述 AgentScope 不覆盖的自进化闭环参数（沙箱、评估、迭代、合并/剪枝、内建技能落地），
 * 不重复 AgentScope 已有的模型/技能仓库/压缩配置。</p>
 *
 * <p>示例（application.yml）：
 * <pre>
 * yunxi:
 *   muse:
 *     enabled: true
 *     model: qwen-max          # 复用 yunxi ModelFactory 已注册的模型名
 *     builtin-export-dir: .agentscope/workspace/skills   # 落地进 AgentScope 运行时技能目录（自动加载）
 *     sandbox:
 *       mode: docker           # local | docker
 *       image: muse-sandbox:latest
 *       timeout: 60s
 *       network-disabled: true
 *     evaluator:
 *       test-command: "java tests/SkillStructureTest.java"
 *       max-retries: 2
 *     refine:
 *       max-iterations: 3
 *     pruner:
 *       similarity-threshold: 0.85
 *       min-usage: 1
 *       max-skills: 200
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "yunxi.muse")
public class EvolutionConfig {

    /** 是否启用 MUSE 自进化能力（默认关闭，需显式开启） */
    private boolean enabled = false;

    /** 用于「评估反馈->修补技能」的 LLM 模型名（走 yunxi ModelFactory） */
    private String model = "qwen-max";

    /** 模型供应商（dashscope/openai/anthropic/...），缺省回退全局配置 */
    private String provider = "dashscope";

    /** 技能 markdown 正文里约定的测试脚本相对路径（默认 tests/） */
    private String testDir = "tests";

    /** 内建技能落地目录：启动时把 builtin-skills 资源复制到此处（供评估器跑测试、可被技能仓库加载） */
    private String builtinExportDir = ".agentscope/workspace/skills";

    /** 启动是否将内建技能落地到 builtinExportDir 并注册进技能仓库 */
    private boolean builtinCopyOnStart = true;

    @NestedConfigurationProperty
    private SandboxConfig sandbox = new SandboxConfig();

    @NestedConfigurationProperty
    private EvaluatorConfig evaluator = new EvaluatorConfig();

    @NestedConfigurationProperty
    private RefineConfig refine = new RefineConfig();

    @NestedConfigurationProperty
    private PrunerConfig pruner = new PrunerConfig();

    @Data
    public static class SandboxConfig {
        /** local=本机子进程（仅超时隔离，Windows 下弱隔离）；docker=容器沙箱（推荐） */
        private String mode = "local";
        /** docker 模式镜像 */
        private String image = "muse-sandbox:latest";
        /** 单次执行超时 */
        private Duration timeout = Duration.ofSeconds(60);
        /** docker 模式是否禁网 */
        private boolean networkDisabled = true;
        /** docker 内存上限（如 256m / 1g） */
        private String memory = "256m";
        /** docker CPU 配额 */
        private String cpus = "1.0";
    }

    @Data
    public static class EvaluatorConfig {
        /** 在技能目录内执行的测试命令 */
        private String testCommand = "java tests/SkillStructureTest.java";
        /** 失败重试次数 */
        private int maxRetries = 2;
    }

    @Data
    public static class RefineConfig {
        /** 单技能最大迭代修补次数 */
        private int maxIterations = 3;
        /** 迭代收敛（连续两次评估无改进）即停止 */
        private boolean stopOnNoProgress = true;
    }

    @Data
    public static class PrunerConfig {
        /** 余弦相似度 >= 此值视为重复技能，触发合并 */
        private double similarityThreshold = 0.85;
        /** 使用次数低于此值的低质技能进入剪枝候选 */
        private int minUsage = 1;
        /** 技能库上限，超出触发剪枝 */
        private int maxSkills = 200;
    }
}
