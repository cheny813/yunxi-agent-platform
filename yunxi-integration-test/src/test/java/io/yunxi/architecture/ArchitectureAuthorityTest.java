package io.yunxi.architecture;

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
 * 开源发布门禁：禁止可发布源码与配置中出现指向内部规格的私有引用。
 */
class ArchitectureAuthorityTest {

    @Test
    void openSourceReferenceHygiene() throws IOException {
        Path repoRoot = findRepoRoot();
        // 标记词刻意拆开拼接，避免门禁源码自身出现连续标记词而被自己命中（自伤）。
        Pattern forbidden = Pattern.compile(
                "§\\d|设计\\s*§|§\\s"
                + "|升级" + "方案|母" + "本|主" + "设计|骑" + "墙|设计" + "文档"
                + "|(?<![0-9.\\-])RC" + "3"
                + "|Phas" + "e\\s*\\d"
                + "|\\.(java|py|ts|go|rs|xml|kt):\\d+"
                + "|D:\\\\wor" + "k|/wor" + "k/|C:\\\\Use" + "rs"
                + "|(?<![A-Za-z0-9\\u4E00-\\u9FFF])[DBT]\\d(?![A-Za-z0-9])"
                + "|(?<![A-Za-z0-9\\u4E00-\\u9FFF])T-\\d+"
                // 多数字任务编号（单数字段标签已由上方 [DBT]\d 覆盖）
                + "|(?<![A-Za-z0-9\\u4E00-\\u9FFF])T\\d{2,}(\\.\\d+)?"
                // 内部规格章节号（私有文档章节，对外无意义）
                + "|(?<![A-Za-z0-9\\u4E00-\\u9FFF])E\\d+\\.\\d+"
                // 历史注记与残留阶段标记（已/过时 拆开拼接，避免本文件被自己命中）
                + "|记录已" + "过时|此前.{0,15}已" + "过时|RED\\s*阶段|GREEN\\s*阶段"
                // 拦截指向内部文档名的引用：引导词集合为 {见/详见/参见/参照}，
                // 收尾词集合为 {设计/规格/方案} 且须为词尾（排除"设计模式"等复合词误报）；
                // 指向框架/本仓代码的合法引用（见 {@link X}/见下方启动断言）不命中。
                + "|(见|详见|参见|参照)\\s*[^）)\\r\\n]{0,25}(设计|规格|方案)(?=[。；，、）；)）\\s]|$)"
                // 第 8 类：交付注释不得叙述自身的写作过程、自证或训诫读者；
                // 高置信度长短语，逐字拆开拼接以免本文件被自己命中。
                + "|已直" + "读核实|已直" + "读框架|源码" + "核实后确认|经源码" + "核实"
                + "|重跑" + "核实|反证" + "搜索|出现" + "背景：|为何" + "不自用"
                + "|属合法" + "产品平面资产|底座" + "收敛|贡献者" + "须知"
                + "|请" + "勿因为|切" + "勿因此|复刻" + " LLM|反复调用" + "被拒绝工具"
                + "|不是" + "功能缺口|并非" + "未接线|误判为" + "接线缺失"
                + "|属于" + "冗余|撤销" + "自建|并非" + "重复实现|须另" + "行复核");
        Set<String> skipDirs = Set.of("legacy", "docs", "target", "tmp", ".git", "node_modules",
                "build-out", "openspec", ".idea", ".vscode", ".agentscope", ".codebuddy", ".repowise",
                "builtin-skills");
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
                        boolean isPublicGuide = rel.replace('\\', '/').contains("/docs/guide/");
                        for (String seg : rel.split("[/\\\\]")) {
                            if (skipDirs.contains(seg)) {
                                if (seg.equals("docs") && isPublicGuide) {
                                    continue;
                                }
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
