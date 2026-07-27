package io.yunxi.platform.muse.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Data;

/**
 * 技能评估报告。由 {@code SkillEvaluator} 在沙箱中执行技能附带的测试脚本后产出。
 *
 * <p>MUSE 自进化闭环的第一环：用这份报告驱动「迭代修补」。</p>
 */
@Data
public class EvalReport {
    /** 被评估的技能名 */
    private String skillName;
    /** 评估时间戳 */
    private Instant evaluatedAt = Instant.now();
    /** 用例总数 */
    private int total;
    /** 通过数 */
    private int passed;
    /** 失败数 */
    private int failed;
    /** 通过率 0~1 */
    private double passRate;
    /** 各用例明细 */
    private List<TestCaseResult> cases = new ArrayList<>();
    /** 人类可读摘要（失败原因聚合） */
    private String summary;

    public boolean isAllPassed() {
        return total > 0 && failed == 0;
    }

    /** 由原始测试输出解析出失败/通过信息（增强版：兼顾 pytest -q 的逐用例与末行汇总）。 */
    public static EvalReport fromRaw(
            String skillName, String stdout, String stderr, int exitCode) {
        EvalReport report = new EvalReport();
        report.setSkillName(skillName);
        String out = stdout == null ? "" : stdout;
        String err = stderr == null ? "" : stderr;

        // 1) 逐用例：收集 "FAILED path::name" / "PASSED path::name" / "ERROR path::name"
        int perCasePass = 0, perCaseFail = 0;
        Pattern casePat = Pattern.compile(
                "(FAILED|ERROR|PASSED)\\s+([\\w./\\-]+::[\\w\\-]+|tests/[\\w./\\-]+\\.py(?:::\\w+)?)");
        Matcher cm = casePat.matcher(out);
        while (cm.find()) {
            String kind = cm.group(1);
            boolean failedKind = "ERROR".equals(kind) || "FAILED".equals(kind);
            report.getCases().add(TestCaseResult.builder()
                    .name(cm.group(2))
                    .passed(!failedKind)
                    .build());
            if (failedKind) perCaseFail++; else perCasePass++;
        }

        // 2) 末行 pytest 汇总："3 passed, 1 failed, 2 warnings in 0.12s" 或 "2 failed in 0.10s"
        String tail = lastNonBlankLine(out);
        int summaryPass = countIn(tail, " passed");
        int summaryFail = countIn(tail, " failed");
        int summaryError = countIn(tail, " error");

        int passed, failed;
        if (summaryPass > 0 || summaryFail > 0 || summaryError > 0) {
            passed = summaryPass;
            failed = summaryFail + summaryError;
        } else if (!report.getCases().isEmpty()) {
            passed = perCasePass;
            failed = perCaseFail;
        } else {
            // 退化处理：非零退出即视为整体失败
            failed = exitCode == 0 ? 0 : 1;
            passed = exitCode == 0 ? 1 : 0;
        }
        report.setPassed(passed);
        report.setFailed(failed);
        report.setTotal(passed + failed);
        report.setPassRate(report.getTotal() == 0 ? 0d : (double) passed / report.getTotal());

        // 若上面没解析出逐用例，补一条整体用例便于反馈给 LLM
        if (report.getCases().isEmpty()) {
            report.getCases().add(TestCaseResult.builder()
                    .name(skillName + "#overall")
                    .passed(exitCode == 0)
                    .stdout(truncate(out, 4000))
                    .stderr(truncate(err, 4000))
                    .build());
        }

        report.setSummary(exitCode == 0
                ? String.format("%d/%d 通过", passed, report.getTotal())
                : String.format("%d/%d 通过；失败摘要: %s", passed, report.getTotal(),
                        truncate(err.isEmpty() ? out : err, 800)));
        return report;
    }

    private static String lastNonBlankLine(String s) {
        if (s == null || s.isBlank()) return "";
        String[] lines = s.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) return lines[i];
        }
        return "";
    }

    private static int countIn(String line, String key) {
        int idx = line.indexOf(key);
        if (idx < 0) return 0;
        StringBuilder num = new StringBuilder();
        int i = idx - 1;
        while (i >= 0 && Character.isDigit(line.charAt(i))) {
            num.insert(0, line.charAt(i));
            i--;
        }
        return num.length() == 0 ? 0 : Integer.parseInt(num.toString());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }
}
