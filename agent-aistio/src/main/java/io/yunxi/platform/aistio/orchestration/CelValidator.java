package io.yunxi.platform.aistio.orchestration;

import org.projectnessie.cel.Env;
import org.projectnessie.cel.Issues;

/**
 * CEL 表达式本地校验：使用 CEL 规范 Java 移植引擎做语法解析（parse），
 * 与 aistio 服务端 cel-go 的解析口径对齐。
 * <p>仅做语法级解析、不做完整类型检查——条件表达式常引用上游变量（如 input/data），
 * 本环境无法获得服务端的变量声明环境，强行类型检查会产生误拒。语义级校验交由服务端导入期兜底。</p>
 */
public final class CelValidator {

    /** 表达式最大长度，与服务端约束一致 */
    public static final int MAX_LENGTH = 4096;

    private CelValidator() {
    }

    /**
     * 判断表达式是否可解析（语法正确且长度合规）。
     *
     * @param expr CEL 表达式，可为空
     * @return 可解析返回 true
     */
    public static boolean isCompilable(String expr) {
        if (expr == null || expr.length() > MAX_LENGTH) {
            return false;
        }
        try {
            Env.AstIssuesTuple tuple = Env.newEnv().parse(expr);
            Issues issues = tuple.getIssues();
            return issues == null || !issues.hasIssues();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
