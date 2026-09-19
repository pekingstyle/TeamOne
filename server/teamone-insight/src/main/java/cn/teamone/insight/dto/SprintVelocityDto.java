package cn.teamone.insight.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 迭代速率 DTO（单个迭代的计划与完成故事点统计）。
 *
 * @param sprintId 迭代唯一标识
 * @param sprintName 迭代名称
 * @param completedPoints 迭代已完成工作项故事点之和
 * @param totalPoints 迭代全部工作项故事点之和
 */
public record SprintVelocityDto(
        UUID sprintId,
        String sprintName,
        BigDecimal completedPoints,
        BigDecimal totalPoints
) {
}
