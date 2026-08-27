package io.yunxi.platform.intent.reload;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.yunxi.platform.intent.config.IntentProperties;
import io.yunxi.platform.intent.domain.ReloadResult;

/**
 * 意图 YAML 文件轮询器（可选触发源，默认关闭）。
 *
 * <p>配置 {@code yunxi.intent.reload.poll-seconds > 0} 时装配：按周期检测
 * {@code file:} 前缀源文件的 mtime 变化，变化则经 {@link IntentReloader} 触发该域
 * 热更新（统一审计与监听器广播）。classpath 资源（jar 内）无稳定 mtime，不做
 * 轮询；外部化部署（{@code file:/data/yunxi/intent/*.yml}）是唯一适用场景。</p>
 *
 * <p>默认 {@code poll-seconds=-1} 关闭，仅 actuator 端点触发，避免无谓 IO。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
@ConditionalOnExpression("${yunxi.intent.reload.poll-seconds:-1} > 0")
public class IntentFilePoller {

    private static final Logger log = LoggerFactory.getLogger(IntentFilePoller.class);

    private final IntentReloader reloader;
    private final long pollSeconds;
    private final Map<String, Long> lastMtimes = new ConcurrentHashMap<>();

    public IntentFilePoller(IntentReloader reloader, IntentProperties properties) {
        this.reloader = reloader;
        this.pollSeconds = properties.getReload().getPollSeconds();
    }

    /** 每 poll-seconds 秒轮询一次（fixedDelay 串行，避免重叠） */
    @Scheduled(fixedDelayString = "${yunxi.intent.reload.poll-seconds:-1}000")
    public void poll() {
        if (pollSeconds <= 0) {
            return;
        }
        for (Map.Entry<String, List<String>> e : reloader.sourceLocations().entrySet()) {
            String domain = e.getKey();
            long changed = lastMtimeOf(e.getValue());
            if (changed < 0) {
                continue;
            }
            Long prev = lastMtimes.put(domain, changed);
            if (prev != null && changed != prev) {
                log.info("[INTENT] 文件变更触发热更新 domain={}", domain);
                ReloadResult r = reloader.reload(domain, false);
                log.info("[INTENT] 轮询热更新完成 domain={} success={} version={} reason={}",
                        domain, r.success(), r.version(), r.reason());
            }
        }
    }

    /** 检测 file: 前缀源文件的最大 mtime（毫秒）；无 file: 源或不可读返回 -1 */
    private long lastMtimeOf(List<String> locations) {
        long max = -1;
        for (String loc : locations) {
            if (loc == null || !loc.startsWith("file:")) {
                continue;
            }
            try {
                Path p = Path.of(loc.substring("file:".length()));
                if (Files.isRegularFile(p)) {
                    max = Math.max(max, Files.getLastModifiedTime(p).toMillis());
                }
            } catch (Exception ex) {
                log.debug("[INTENT] 轮询检测失败: {} ({})", loc, ex.getMessage());
            }
        }
        return max;
    }
}
