package cn.teamone.eng.infra.git;

import java.util.List;

/**
 * 差异变更集汇总（对应前端 MR 变更集与统计）。
 *
 * @param totalAdditions 总增加行数
 * @param totalDeletions 总删除行数
 * @param files          变更文件列表
 */
public record GitDiffResult(
        int totalAdditions,
        int totalDeletions,
        List<GitFileDiff> files
) {
    public static GitDiffResult empty() {
        return new GitDiffResult(0, 0, List.of());
    }
}
