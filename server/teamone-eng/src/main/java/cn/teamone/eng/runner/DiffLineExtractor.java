package cn.teamone.eng.runner;

import cn.teamone.eng.infra.git.GitDiffLine;
import cn.teamone.eng.infra.git.GitDiffResult;
import cn.teamone.eng.infra.git.GitFileDiff;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * MR 变更行提取与 patch 覆盖率交集（M4-INC2 · R8 门禁真实化，docs/v2/11 §3.4）。
 *
 * <p><b>数据来源</b>：unified diff 的 hunk 数学（{@code @@} 起始行 + 上下文/增/删行推进
 * 新旧侧行号）已由 {@code GitCommandPort.parseUnifiedDiff} 完成——R8 纪律下 git 输出解析
 * 收敛在 {@code infra.git} 唯一出墙口；本类做纯投影：{@link GitDiffLine} 的 {@code add}
 * 行（{@code newNo} 即新文件侧行号，跨 hunk 为绝对行号）= 变更行。</p>
 *
 * <p><b>路径归一</b>：只取 {@code .java} 且路径含 {@code src/main/java/} 的文件（测试源
 * {@code src/test/java}、配置等不计），键 = {@code src/main/java/} 之后的部分（如
 * {@code cn/teamone/eng/runner/PipelineRunner.java}）——monorepo 前缀（server/…）被剥掉，
 * 与 {@link JacocoXmlParser} 的 sourcefile 键同构。重命名文件（git rename 检测，b 侧路径 +
 * 部分 add 行）天然按新路径计；纯重命名（相似度 100%，无内容 hunk）无 add 行、不产生条目。</p>
 *
 * <p><b>patch 覆盖率口径</b>（⑥l-A 规格细化 docs/v2/11 §3.4）：分母 = 变更行 ∩ jacoco
 * 可执行行（在行集内的才算「可执行变更行」），分子 = 其中已覆盖行；分母 0 → null
 * （无涉代码变更，门禁不因此挂）。删除行（旧侧）不参与——覆盖率衡量的是新代码。</p>
 *
 * @author Ivan Yang, 2026-09-20
 */
public final class DiffLineExtractor {

    /** Java 主源码目录标记（monorepo 任意层） */
    private static final String MAIN_JAVA_DIR = "src/main/java/";

    private DiffLineExtractor() {
    }

    /**
     * 提取 MR 变更行集（新文件侧 + 行）。
     *
     * @param diff GitPort.diff 的三路 diff（target...source，新侧=source 分支）
     * @return 归一化源路径 → 变更行号集（无 .java 主源码变更时为空表）
     */
    public static Map<String, Set<Integer>> changedJavaMainLines(GitDiffResult diff) {
        Map<String, Set<Integer>> changed = new LinkedHashMap<>();
        if (diff == null || diff.files() == null) {
            return changed;
        }
        for (GitFileDiff file : diff.files()) {
            if (file == null || file.path() == null || file.lines() == null) {
                continue;
            }
            String path = file.path().replace('\\', '/');
            if (!path.endsWith(".java")) {
                continue;
            }
            int idx = path.lastIndexOf(MAIN_JAVA_DIR);
            if (idx < 0) {
                continue; // 测试源/生成代码等非主源码不计入 patch 口径
            }
            String key = path.substring(idx + MAIN_JAVA_DIR.length());
            for (GitDiffLine line : file.lines()) {
                if ("add".equals(line.type()) && line.newNo() != null) {
                    changed.computeIfAbsent(key, k -> new TreeSet<>()).add(line.newNo());
                }
            }
        }
        return changed;
    }

    /**
     * patch 覆盖率 =（变更行 ∩ jacoco 覆盖行）/（变更行 ∩ jacoco 可执行行）。
     *
     * @param changedLines {@link #changedJavaMainLines} 的变更行集
     * @param jacoco       {@link JacocoXmlParser#parse} 的报告结果
     * @return 百分数；分母 0（变更行均不在 jacoco 行集内=无可执行代码变更）返回 null
     */
    public static Double patchCoveragePercent(Map<String, Set<Integer>> changedLines,
                                              JacocoXmlParser.Result jacoco) {
        if (changedLines == null || changedLines.isEmpty() || jacoco == null) {
            return null;
        }
        long covered = 0;
        long executable = 0;
        for (Map.Entry<String, Set<Integer>> entry : changedLines.entrySet()) {
            JacocoXmlParser.SourceLines lines = jacoco.sources().get(entry.getKey());
            if (lines == null) {
                continue; // 该文件无字节码行集（未编译/非本仓主源码）：不计分母
            }
            for (Integer nr : entry.getValue()) {
                if (nr == null || !lines.executableLines().contains(nr)) {
                    continue;
                }
                executable++;
                if (lines.coveredLines().contains(nr)) {
                    covered++;
                }
            }
        }
        if (executable == 0) {
            return null;
        }
        return covered * 100.0 / executable;
    }
}
