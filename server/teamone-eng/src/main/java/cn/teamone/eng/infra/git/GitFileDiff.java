package cn.teamone.eng.infra.git;

import java.util.List;

/**
 * 单文件 Diff 投影（对应前端 FileDiff）。
 *
 * @param path      文件相对路径
 * @param status    变更状态：modified / added / removed
 * @param additions 增加行数
 * @param deletions 删除行数
 * @param lines     逐行差异清单
 */
public record GitFileDiff(
        String path,
        String status,
        int additions,
        int deletions,
        List<GitDiffLine> lines
) {}
