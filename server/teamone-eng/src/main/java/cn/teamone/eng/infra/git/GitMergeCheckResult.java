package cn.teamone.eng.infra.git;

import java.util.List;

/**
 * 可合并性与冲突检测结果（U7）。
 *
 * @param canMerge       是否可干净合入（无冲突）
 * @param rebaseRequired 是否落后目标分支需要 rebase
 * @param conflictFiles  冲突文件相对路径列表
 */
public record GitMergeCheckResult(
        boolean canMerge,
        boolean rebaseRequired,
        List<String> conflictFiles
) {
    public static GitMergeCheckResult clean() {
        return new GitMergeCheckResult(true, false, List.of());
    }
}
