package cn.teamone.eng.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;


/**
 * 流水线运行详情响应 DTO。
 * <p>
 * 返回单次流水线完整执行快照，包含代码仓信息、触发源、耗时、执行状态以及按顺序排列的多阶段（Stage）轨道与任务日志。
 * </p>
 *
 * @param id            流水线运行记录主键 UUID
 * @param repoId        所属代码仓库 UUID
 * @param repoName      所属代码仓库名称（如 "teamone"）
 * @param title         流水线运行标题（如 "#102 · main CI 构建与测试门禁"）
 * @param branch        执行目标分支（如 "main"）
 * @param commitSha     完整的 Git 提交 40 位哈希 SHA
 * @param commitShort   截断的前 7 位短哈希（如 "a7faecd"）
 * @param trigger       触发来源类型（push 推送 / manual 手动 / mr 合并请求 / schedule 定时）
 * @param mrId          关联的合并请求 UUID（若有）
 * @param status        流水线整体最终状态（pending 排队 / running 运行中 / passed 成功 / failed 失败 / skipped 跳过 / canceled 已取消）
 * @param triggerUserId 触发人用户 UUID（系统触发时为系统账号 UUID）
 * @param durationSec   流水线总执行耗时（秒）
 * @param stages        流水线包含的阶段轨道列表（Build, Test, Quality, Deploy 等）
 * @param startedAt     开始执行时间戳（UTC）
 * @param finishedAt    执行完成时间戳（UTC）
 * @param createdAt     创建时间戳（UTC）
 * @param buildSystem   构建体系识别结果（maven/npm/unknown；V22 · M4-INC1，列表契约字段，
 *                      历史/模拟 run 透出 unknown）
 * @param jobs          真实执行作业行（详情契约字段；列表形态与历史模拟 run 为 null/缺省，
 *                      前端按空数组缺省渲染）
 *
 * @author Ivan Yang, 2026-09-13
 */
public record PipelineRunResponse(
        UUID id,
        UUID repoId,
        String repoName,
        String title,
        String branch,
        String commitSha,
        String commitShort,
        String trigger,
        UUID mrId,
        String status,
        UUID triggerUserId,
        int durationSec,
        List<StageDto> stages,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        String buildSystem,
        List<PipelineJobDto> jobs
) {}

