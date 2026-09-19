package cn.teamone.eng.dto;

import java.util.List;

/**
 * 流水线任务（Job）数据传输对象。
 * <p>
 * 一个阶段（Stage）可包含多个并行或串行执行的任务（Job）。
 * 任务封装具体的构建、单测、覆盖率计算、静态检查等动作及其控制台输出日志。
 * </p>
 *
 * @param id          任务唯一标识（如 "job-build-01"）
 * @param name        任务展示名称（例如 "Maven Compile & Package"、"JUnit 5 & ArchUnit Suite"）
 * @param status      任务执行状态（取值范围：pending 等待、running 执行中、passed 成功、failed 失败、skipped 跳过、canceled 取消）
 * @param durationSec 任务执行耗时（秒）
 * @param logs        控制台实时与归档文本日志行列表
 *
 * @author Ivan Yang, 2026-09-13
 */
public record JobDto(
        String id,
        String name,
        String status,
        int durationSec,
        List<String> logs
) {}

