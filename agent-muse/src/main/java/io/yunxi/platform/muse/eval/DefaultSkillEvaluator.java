package io.yunxi.platform.muse.eval;

import io.yunxi.platform.muse.config.EvolutionConfig;
import io.yunxi.platform.muse.model.EvalReport;
import io.yunxi.platform.muse.sandbox.SandboxRunner;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认评估器：在沙箱里执行 {@code yunxi.muse.evaluator.test-command}。
 *
 * <p>测试脚本约定放在技能目录下的 {@code yunxi.muse.test-dir}（默认 tests/）。
 * 退出码 0 视为通过；同时解析 stdout 输出中的 pytest 风格计数或纯 Java 测试输出
 * 得到通过率。</p>
 *
 * <p>评估模式兼容两种测试框架：
 * <ul>
 *   <li>纯 Java 自测（如 {@code SkillStructureTest.java}）：退出码 0→通过</li>
 *   <li>pytest：退出码 + stdout 逐用例解析 + 末行汇总</li>
 * </ul>
 * {@link EvalReport#fromRaw} 统一处理上述两种模式。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "yunxi.muse.enabled", havingValue = "true")
public class DefaultSkillEvaluator implements SkillEvaluator {

    private final SandboxRunner sandbox;
    private final EvolutionConfig config;

    public DefaultSkillEvaluator(SandboxRunner sandbox, EvolutionConfig config) {
        this.sandbox = sandbox;
        this.config = config;
    }

    @Override
    public EvalReport evaluate(String skillName, String skillDir) {
        List<String> command = Arrays.asList(config.getEvaluator().getTestCommand().split("\\s+"));
        long timeoutMs = config.getSandbox().getTimeout().toMillis();

        SandboxRunner.SandboxRunResult result = null;
        int attempt = 0;
        int maxRetries = config.getEvaluator().getMaxRetries();
        while (attempt <= maxRetries) {
            result = sandbox.run(command, skillDir, timeoutMs);
            if (!result.isTimedOut()) break;
            attempt++;
            log.warn("[muse] skill={} 评估超时，重试 {}/{}", skillName, attempt, maxRetries);
        }
        if (result == null) {
            return EvalReport.fromRaw(skillName, "", "sandbox unavailable", 1);
        }

        log.info("[muse] skill={} 评估完成: exit={}, timedOut={}",
                skillName, result.getExitCode(), result.isTimedOut());

        return EvalReport.fromRaw(skillName, result.getStdout(), result.getStderr(), result.getExitCode());
    }
}
