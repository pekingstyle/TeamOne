package cn.teamone.eng.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 流水线作业 DTO（GET /api/v1/pipelines/{id} 契约字段 · M4-INC1 与前端批严格一致）。
 *
 * <p>契约：{"id","stage","name","status","exitCode","startedAt","finishedAt","logTail","errorMsg"}——errorMsg=失败原因（超时/工具缺失/环境故障），日志抽屉空时兜底展示（QA 复审 MUST-FIX）；
 * status 取值 pending|running|success|failed|skipped（作业级状态机，与 run 级 passed 叙事区分）；
 * logTail 为进程合流输出尾部 ~120 行文本，可为 null（历史模拟 run 恒无作业行，前端缺省安全）。</p>
 *
 * @param id         作业 UUID
 * @param stage      阶段（build/test）
 * @param name       展示名（"构建"/"单测"）
 * @param status     作业状态（pending/running/success/failed/skipped）
 * @param exitCode   进程退出码（超时/未启动为 null）
 * @param startedAt  认领开始时间
 * @param finishedAt 收口时间
 * @param logTail    合流输出尾部 ~120 行（可 null）
 *
 * @author Ivan Yang, 2026-09-19
 */
public record PipelineJobDto(
        UUID id,
        String stage,
        String name,
        String status,
        Integer exitCode,
        Instant startedAt,
        Instant finishedAt,
        String logTail,
        String errorMsg
) {}
