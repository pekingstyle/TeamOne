package cn.teamone.insight.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 效能综合度量报表 DTO（提供交付周期、完成率、缺陷分布及速率多维汇总）。
 *
 * @param totalItems 工作项总数
 * @param completedItems 已完成工作项数量
 * @param completionRate 整体交付完成率（0.0 ~ 1.0）
 * @param avgCycleTimeDays 平均交付周期（从创建到完成的天数）
 * @param defectCount 缺陷总数
 * @param defectResolutionRate 缺陷解决率（0.0 ~ 1.0）
 * @param totalStoryPoints 故事点总额
 * @param completedStoryPoints 已完成故事点总额
 * @param defectSeverityDistribution 缺陷严重度分布统计（致命/严重/一般/轻微）
 * @param sprintVelocities 历史各迭代速率统计列表
 */
public record EfficiencyReportDto(
        int totalItems,
        int completedItems,
        double completionRate,
        double avgCycleTimeDays,
        int defectCount,
        double defectResolutionRate,
        BigDecimal totalStoryPoints,
        BigDecimal completedStoryPoints,
        Map<String, Integer> defectSeverityDistribution,
        List<SprintVelocityDto> sprintVelocities
) {
}
