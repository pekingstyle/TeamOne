package cn.teamone.app.health;

import cn.teamone.eng.infra.git.GitPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 自研 Git 裸仓存储与可用性健康指标检查器（V-17）。
 * <p>
 * 实时监控服务端 Bare 仓库根目录（{@code teamone.git.root}）的挂载状态、读写权限与磁盘容量，
 * 并在未配置或磁盘空间耗尽时向 Actuator / Prometheus 发出告警信号。
 * </p>
 *
 * @author Ivan Yang, 2026-09-13
 */
@Component
public class GitStorageHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(GitStorageHealthIndicator.class);

    /**
     * 磁盘最低可用空间警戒阈值：200 MB
     */
    private static final long MIN_USABLE_BYTES = 200L * 1024 * 1024;

    private final String gitRoot;
    private final GitPort gitPort;

    public GitStorageHealthIndicator(
            @Value("${teamone.git.root:}") String gitRoot,
            @Autowired(required = false) GitPort gitPort) {
        this.gitRoot = gitRoot;
        this.gitPort = gitPort;
    }

    @Override
    public Health health() {
        if (gitRoot == null || gitRoot.isBlank()) {
            return Health.up()
                    .withDetail("status", "UNCONFIGURED")
                    .withDetail("message", "teamone.git.root 未显式配置，运行于内存/轻量模式")
                    .build();
        }

        File rootDir = new File(gitRoot);
        if (!rootDir.exists()) {
            return Health.down()
                    .withDetail("gitRoot", gitRoot)
                    .withDetail("error", "Git 根目录不存在")
                    .build();
        }

        if (!rootDir.isDirectory()) {
            return Health.down()
                    .withDetail("gitRoot", gitRoot)
                    .withDetail("error", "Git 根路径不是目录")
                    .build();
        }

        if (!rootDir.canRead() || !rootDir.canWrite()) {
            return Health.down()
                    .withDetail("gitRoot", gitRoot)
                    .withDetail("error", "Git 根目录读写权限不足")
                    .build();
        }

        long totalSpace = rootDir.getTotalSpace();
        long usableSpace = rootDir.getUsableSpace();

        // 统计裸仓个数
        int repoCount = 0;
        try {
            File[] list = rootDir.listFiles();
            if (list != null) {
                for (File f : list) {
                    if (f.isDirectory() && (f.getName().endsWith(".git") || new File(f, "HEAD").exists())) {
                        repoCount++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[health] failed to scan bare repositories: {}", e.getMessage());
        }

        Map<String, Object> details = new HashMap<>();
        details.put("gitRoot", gitRoot);
        details.put("repoCount", repoCount);
        details.put("totalSpaceBytes", totalSpace);
        details.put("usableSpaceBytes", usableSpace);
        details.put("usableSpaceMb", usableSpace / (1024 * 1024));
        details.put("gitPortAvailable", gitPort != null);

        if (usableSpace < MIN_USABLE_BYTES) {
            return Health.down()
                    .withDetails(details)
                    .withDetail("warning", "Git 存储可用磁盘空间已不足 200MB，存在写丢风险")
                    .build();
        }

        return Health.up()
                .withDetails(details)
                .build();
    }
}
