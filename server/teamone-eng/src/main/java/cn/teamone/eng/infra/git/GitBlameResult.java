package cn.teamone.eng.infra.git;

import java.util.List;

/**
 * Git 逐文件 Blame 计算结果快照（S-1'）。
 * <p>
 * 由于 Git Commit 本身具有内容寻址不可变性（Content-Addressable），
 * 针对固定 commitSha 与 filePath 的 Blame 结果在全生命周期内严格不可变，
 * 为两级缓存（L1 内存 + L2 Valkey/Redis）与异步预热提供坚实的数学确定性基础。
 * </p>
 *
 * @param repoKey     仓库路径标识（如 teamone/teamone.git）
 * @param commitSha   计算针对的基准 Commit SHA
 * @param filePath    文件相对路径
 * @param lines       按行排序的 Blame 明细清单
 * @param totalLines  总行数
 * @param durationMs  计算执行耗时（毫秒）
 * @param fromCache   是否命中缓存（L1/L2）
 * @author Ivan Yang, 2026-09-13
 */
public record GitBlameResult(
        String repoKey,
        String commitSha,
        String filePath,
        List<GitBlameLine> lines,
        int totalLines,
        long durationMs,
        boolean fromCache
) {
    /**
     * 空结果工厂方法。
     */
    public static GitBlameResult empty(String repoKey, String commitSha, String filePath) {
        return new GitBlameResult(repoKey, commitSha, filePath, List.of(), 0, 0, false);
    }
}
