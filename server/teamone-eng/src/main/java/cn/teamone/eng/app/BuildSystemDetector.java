package cn.teamone.eng.app;

import cn.teamone.eng.infra.git.GitPort;
import cn.teamone.eng.infra.git.GitTreeItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * 构建体系识别器（docs/v2/11 §2 识别矩阵最小版 · M4-INC1）。
 *
 * <p>只做特征文件存在性扫描（不引入语言解析器）：经 {@link GitPort#tree} 列默认分支
 * 仓库根目录与一级子目录（浅层），命中规则：
 * <ul>
 *   <li>根或一级子目录 {@code pom.xml} → maven（workdir=manifest 所在层，根为 ""）</li>
 *   <li>否则根或一级子目录 {@code package.json} → npm（workdir 同上，如 web）</li>
 *   <li>{@code Dockerfile} 仅标记不计构建（§2.1 叠加层，最小版不落任何结果）</li>
 *   <li>都不中 → unknown（trigger 时 run 直接 failed，不建作业）</li>
 * </ul>
 * 同级/跨层并存时 Maven 优先（§2.2 B1 构建类型歧义：Maven 优先，细分告警归后续批次）。
 * 识别失败（空仓/分支不存在/异常）一律 unknown，绝不冒充 Maven（§2.2 B7 精神）。</p>
 *
 * <p>触发时机：trigger（真实模式）时识别并落 run 行（build_system/workdir 列）；
 * push 增量重识别与 repo_build_profile 持久化归后续批次（§2.4）。</p>
 *
 * @author Ivan Yang, 2026-09-19
 */
@Component
public class BuildSystemDetector {

    private static final Logger log = LoggerFactory.getLogger(BuildSystemDetector.class);

    private final GitPort gitPort;

    public BuildSystemDetector(GitPort gitPort) {
        this.gitPort = gitPort;
    }

    /**
     * 识别结果投影。
     *
     * @param buildSystem maven / npm / unknown
     * @param workdir     构建层相对仓库根路径（""=根；如 server、web；unknown 时为 ""）
     */
    public record Result(String buildSystem, String workdir) {

        public static final Result MAVEN_ROOT = new Result("maven", "");
        public static final Result UNKNOWN = new Result("unknown", "");

        public boolean isUnknown() {
            return "unknown".equals(buildSystem);
        }
    }

    /**
     * 对指定仓库与 ref 执行特征文件扫描。
     *
     * @param repoKey 仓库键（如 teamone/teamone.git）
     * @param ref     目标 ref（分支/sha；trigger 用 run 分支）
     * @return 识别结果（异常不抛出，一律降级 unknown）
     */
    public Result detect(String repoKey, String ref) {
        try {
            List<GitTreeItem> root = gitPort.tree(repoKey, ref, "");
            return scan(root, sub -> {
                try {
                    return gitPort.tree(repoKey, ref, sub);
                } catch (Exception e) {
                    // 单个子目录列举失败不拖垮整体识别（浅层扫描尽力而为）
                    log.debug("[pipeline] 子目录列举失败 {}: {}", sub, e.getMessage());
                    return List.<GitTreeItem>of();
                }
            });
        } catch (Exception e) {
            log.warn("[pipeline] 构建体系识别失败（降级 unknown）: {}", e.getMessage());
            return Result.UNKNOWN;
        }
    }

    /**
     * 纯扫描逻辑（静态可单测）：根目录清单 + 一级子目录清单提供函数。
     *
     * <p>次序：根 pom → 一级子目录 pom（B1 Maven 优先，目录序即 tree() 的字母序）→
     * 根 package.json → 一级子目录 package.json（首个命中）→ unknown。</p>
     */
    static Result scan(List<GitTreeItem> root, Function<String, List<GitTreeItem>> subdirTree) {
        if (root == null || root.isEmpty()) {
            return Result.UNKNOWN; // 空仓/空分支
        }
        if (hasFile(root, "pom.xml")) {
            return Result.MAVEN_ROOT;
        }
        boolean rootPackage = hasFile(root, "package.json");
        String npmDir = rootPackage ? "" : null;
        for (GitTreeItem item : root) {
            if (!item.isDirectory()) {
                continue;
            }
            // 命令模板以空白分列（无 shell）——目录名含空白会使 argv 错位，跳过防诚实失败变诡异失败（QA NICE-5）
            if (item.name() == null || item.name().isBlank() || item.name().matches(".*\s.*")) {
                continue;
            }
            List<GitTreeItem> sub = subdirTree.apply(item.name());
            if (hasFile(sub, "pom.xml")) {
                return new Result("maven", item.name());
            }
            if (npmDir == null && hasFile(sub, "package.json")) {
                npmDir = item.name(); // 记住首个 npm 命中，但继续找 maven（跨层 Maven 优先）
            }
        }
        if (npmDir != null) {
            return new Result("npm", npmDir);
        }
        return Result.UNKNOWN;
    }

    private static boolean hasFile(List<GitTreeItem> items, String fileName) {
        if (items == null) {
            return false;
        }
        for (GitTreeItem item : items) {
            if (!item.isDirectory() && fileName.equals(item.name())) {
                return true;
            }
        }
        return false;
    }
}
