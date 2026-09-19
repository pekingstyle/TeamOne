package cn.teamone.eng.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 开发者本地工作副本（Git Worktree）拓扑详情响应 DTO。
 * <p>
 * 供前端「仓库详情 -> 工作树」视图渲染工作副本清单、落后提示、未提交改动告警以及 SVG 关系拓扑图使用。
 * </p>
 *
 * @param id             工作副本上报记录主键 UUID
 * @param repoId         所属代码仓库 UUID
 * @param localPath      开发者本地工作路径
 * @param branchName     绑定的 Git 工作分支名
 * @param baseRef        分叉基准分支或标签
 * @param ownerUserId    归属开发者的用户 UUID（用于头像与团队颜色映射）
 * @param dirtyFileCount 未提交修改文件计数
 * @param aheadCount     领先基准提交数（+N）
 * @param behindCount    落后基准提交数（-N）
 * @param status         副本生命周期状态（active 活跃 / merged 已合入 / stale 停滞）
 * @param lastCommitSha  最新本地提交哈希
 * @param lastCommitAt   最新本地提交产生的时间
 * @param lastActiveAt   客户端最近一次活跃心跳上报时间（UTC）
 * @param createdAt      首次上报建档时间（UTC）
 *
 * @author Ivan Yang, 2026-09-13
 */
public record WorktreeReportResponse(
        UUID id,
        UUID repoId,
        String localPath,
        String branchName,
        String baseRef,
        UUID ownerUserId,
        int dirtyFileCount,
        int aheadCount,
        int behindCount,
        String status,
        String lastCommitSha,
        Instant lastCommitAt,
        Instant lastActiveAt,
        Instant createdAt
) {}

