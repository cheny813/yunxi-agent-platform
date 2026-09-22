package io.yunxi.platform.shared.codec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 编解码器的启动自检。
 *
 * <p>启动时跑一次「写入 → 读回 → 比对」的往返校验，把「写得进读不出」这类问题在启动期
 * 就暴露出来，而不是等到某个消费方拿到空数据再回头排查。</p>
 *
 * <p><b>为何要自检而非只靠测试</b>：测试覆盖的是写代码那一刻的类型组合，而实际部署里
 * 落盘的数据可能来自旧版本、来自别的实例、或经外部工具注入。往返契约一旦被破坏（例如
 * 有人给持久化对象引入了一个需要类型信息才能还原的嵌套类型），测试未必覆盖得到，
 * 但自检在每次启动时都会跑一遍当前环境下的真实编解码路径。</p>
 *
 * <p>自检不做连接、不碰存储：它只验证编解码器本身，故在任何部署形态下都可安全执行。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class CodecStartupSelfCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CodecStartupSelfCheck.class);

    private final RedisValueCodec codec;

    public CodecStartupSelfCheck(RedisValueCodec codec) {
        this.codec = codec;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> failures = new ArrayList<>();

        // 用例一：嵌套映射与列表的往返
        Object nested = nestedSample();
        byte[] encoded = codec.serialize(nested);
        if (encoded == null) {
            failures.add("序列化返回 null");
        } else {
            if (RedisValueCodec.containsTypeTag(encoded)) {
                failures.add("序列化结果含类型标识（应为纯 JSON 原生结构）");
            }
            Object decoded = codec.deserialize(encoded);
            if (!nested.equals(decoded)) {
                failures.add("嵌套结构往返不一致: 写入=" + nested + ", 读回=" + decoded);
            }
        }

        // 用例二：字符串通道的往返（画像与节流状态走这条通道）
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("userId", "self-check");
        sample.put("updatedAt", 1L);
        String json = codec.write(sample, "self-check");
        if (json == null) {
            failures.add("字符串通道序列化返回 null");
        } else if (json.contains("\"@class\"")) {
            failures.add("字符串通道序列化结果含类型标识");
        } else {
            Map<String, Object> back = codec.read(json, Map.class, "self-check");
            if (back == null || !"self-check".equals(back.get("userId"))) {
                failures.add("字符串通道往返不一致: " + json);
            }
        }

        // 用例三：单条损坏不影响其余元素的解析
        List<Object> mixed = new ArrayList<>();
        mixed.add(Map.of("kind", "MODEL_CALL", "snapshot", "CLOSED"));
        mixed.add("一条非映射的脏数据");
        List<ReasoningProbe> coercible = codec.convertList(mixed, ReasoningProbe.class, "self-check");
        if (coercible.size() != 1) {
            failures.add("逐条容错异常：期望 1 条可解析，实得 " + coercible.size());
        }

        if (failures.isEmpty()) {
            log.info("[codec] 编解码自检通过：无类型标识、往返一致、逐条容错生效");
            return;
        }
        for (String failure : failures) {
            log.error("[codec] 编解码自检失败: {}", failure);
        }
    }

    /** 构造一个含嵌套映射与列表的样本，覆盖负载的典型形状。 */
    private static Object nestedSample() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("toolCallId", "call-1");
        root.put("resultPhase", "end");
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", 123);
        usage.put("outputTokens", 45);
        usage.put("time", 1.5d);
        root.put("usage", usage);
        root.put("tasks", List.of(
                Map.of("taskId", "t1", "status", "pending"),
                Map.of("taskId", "t2", "status", "done")));
        return root;
    }

    /** 自检用的最小可转换结构。 */
    public static class ReasoningProbe {
        private String kind;
        private String snapshot;

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public String getSnapshot() {
            return snapshot;
        }

        public void setSnapshot(String snapshot) {
            this.snapshot = snapshot;
        }
    }
}
