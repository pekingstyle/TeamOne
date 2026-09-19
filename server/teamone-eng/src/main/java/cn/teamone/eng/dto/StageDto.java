package cn.teamone.eng.dto;

import java.util.List;

/**
 * 流水线阶段轨道（Stage）数据传输对象。
 * <p>
 * 流水线整体由按顺序排列的多个阶段组成（例如：构建阶段 -> 测试门禁 -> 质量与安全扫描 -> 自动部署）。
 * 阶段本身的状态由其内部所包含的各项子任务（Job）执行结果汇总推导产生：
 * <ul>
 *   <li>任一任务失败且未被忽略，阶段状态置为 failed</li>
 *   <li>所有任务成功完成，阶段状态置为 passed</li>
 *   <li>阶段内有任务正在运行，阶段状态置为 running</li>
 *   <li>前置阶段失败导致本阶段不执行，阶段状态置为 skipped</li>
 * </ul>
 * </p>
 *
 * @param name        阶段名称（如 "构建阶段 (Build)"、"测试门禁 (Test Gate)"、"代码质量扫描 (Quality)"）
 * @param status      阶段综合状态（pending / running / passed / failed / skipped / canceled）
 * @param durationSec 阶段总执行耗时（秒）
 * @param jobs        阶段内部包含的任务执行列表
 *
 * @author Ivan Yang, 2026-09-13
 */
public record StageDto(
        String name,
        String status,
        int durationSec,
        List<JobDto> jobs
) {}

