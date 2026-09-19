package cn.teamone.eng.dto;

/**
 * 开发者本地工作副本（Git Worktree）客户端上报请求 DTO。
 * <p>
 * 由本地轻量守护脚本或开发者工作站上的 CLI/IDE 插件定期（或 Git post-commit / checkout hook 触发）向服务端上报。
 * 平台借此构建团队本地工作拓扑视图，辅助提早感知并发冲突与未提交工作进度。
 * </p>
 *
 * @param localPath      开发者宿主机上的绝对或工作区路径（如 "~/Workspace/TeamOne-feature"）
 * @param branchName     该 Worktree 关联的工作分支名称（如 "feature/login-oauth"）
 * @param baseRef        分叉基准分支或标签（默认为 "main"）
 * @param dirtyFileCount 工作区中已修改/未暂存/未提交的文件计数（0 表示工作树干净）
 * @param aheadCount     本地分支领先基准主干的提交数（即本地新增但尚未推送或合入的提交数）
 * @param behindCount    本地分支落后基准主干的提交数（若 >0 提示开发者建议 rebase）
 * @param lastCommitSha  本地 HEAD 指向的最新 Git 提交 SHA
 *
 * @author Ivan Yang, 2026-09-13
 */
public record WorktreeReportRequest(
        String localPath,
        String branchName,
        String baseRef,
        int dirtyFileCount,
        int aheadCount,
        int behindCount,
        String lastCommitSha
) {}

