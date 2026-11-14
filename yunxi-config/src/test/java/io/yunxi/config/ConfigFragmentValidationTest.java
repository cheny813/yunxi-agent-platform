package io.yunxi.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置片段存在性校验：config/imports.yml 是片段清单唯一真相源，本测试确保其中列出的每个片段
 * 都真实存在于 classpath，防止改名 / 误删后“写了对但没加载”的静默黑洞。
 *
 * <p>新增或重命名片段时，必须同步更新 imports.yml 与本测试覆盖的清单。</p>
 */
class ConfigFragmentValidationTest {

    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("classpath:config/[A-Za-z0-9_./-]+\\.yml");

    @Test
    void allImportedFragmentsExist() throws Exception {
        ClassPathResource imports = new ClassPathResource("config/imports.yml");
        assertTrue(imports.exists(), "config/imports.yml must exist on classpath");

        String content = new String(imports.getInputStream().readAllBytes());
        Matcher matcher = IMPORT_PATTERN.matcher(content);

        List<String> missing = new ArrayList<>();
        while (matcher.find()) {
            // 去掉 "classpath:" 前缀得到资源路径（optional: 前缀位于 classpath: 之前，不会被本正则捕获）。
            String path = matcher.group().substring("classpath:".length());
            if (!new ClassPathResource(path).exists()) {
                missing.add(path);
            }
        }

        assertTrue(missing.isEmpty(),
                "Missing config fragments referenced by config/imports.yml: " + missing);
    }
}
