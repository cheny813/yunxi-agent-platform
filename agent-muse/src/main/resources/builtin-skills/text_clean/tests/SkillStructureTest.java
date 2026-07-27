import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * MUSE 纯 Java 技能自测（无需 Python / 构建工具，只要有 JDK 即可运行）。
 *
 * <p>约定：在「技能目录」（user.dir，即 SKILL.md 所在目录）内执行：
 * <pre>    java tests/SkillStructureTest.java</pre>
 * 程序读取 SKILL.md，断言其包含必要章节（## 用法 / ## 示例），
 * 并包含同目录 expected.txt 中逐行列出的关键词。全部满足退出 0；否则向 stderr 打印 FAIL 并退出 1。</p>
 *
 * <p>这是 MUSE 自进化闭环的「纯 Java 自测」落地：技能作者用 Java 描述验收，
 * 无需团队安装 Python 环境即可驱动评估与迭代修补。</p>
 */
public class SkillStructureTest {

    public static void main(String[] args) {
        Path skillDir = Path.of(System.getProperty("user.dir", "."));
        Path skillMd = skillDir.resolve("SKILL.md");
        List<String> failed = new ArrayList<>();

        if (!Files.exists(skillMd)) {
            fail("找不到 SKILL.md: " + skillMd.toAbsolutePath());
        }
        try {
            String md = Files.readString(skillMd);
            require(md, "## 用法", failed);
            require(md, "## 示例", failed);
            Path expected = skillDir.resolve("tests").resolve("expected.txt");
            if (Files.exists(expected)) {
                for (String raw : Files.readAllLines(expected)) {
                    String kw = raw.strip();
                    if (!kw.isEmpty() && !kw.startsWith("#")) {
                        require(md, kw, failed);
                    }
                }
            }
        } catch (IOException e) {
            fail("读取 SKILL.md 失败: " + e.getMessage());
        }

        if (!failed.isEmpty()) {
            StringBuilder sb = new StringBuilder("FAIL: 技能结构校验未通过:\n");
            for (String f : failed) {
                sb.append("  - ").append(f).append("\n");
            }
            System.err.print(sb);
            System.exit(1);
        }
        System.out.println("OK: 技能结构校验通过（章节完整且关键词齐全）");
    }

    static void require(String md, String needle, List<String> failed) {
        if (!md.contains(needle)) {
            failed.add("缺少必要内容: " + needle);
        }
    }

    static void fail(String msg) {
        System.err.println("FAIL: " + msg);
        System.exit(1);
    }
}