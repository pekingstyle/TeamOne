package cn.teamone.eng.infra.git;

import java.util.List;

/**
 * 分支对比（Compare）聚合结果（U5）。
 *
 * @param baseSha    共同祖先 Commit SHA（merge-base）
 * @param targetSha  目标分支最新 Commit SHA
 * @param sourceSha  源分支最新 Commit SHA
 * @param commits    源分支相对于目标分支新增的提交列表
 * @param diff       三路 Diff 变更结果
 * @param mergeCheck 可合并性判定结果
 */
public record GitCompareResult(
        String baseSha,
        String targetSha,
        String sourceSha,
        List<GitCommit> commits,
        GitDiffResult diff,
        GitMergeCheckResult mergeCheck
) {}
