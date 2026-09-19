package cn.teamone.insight.dto;

import java.time.LocalDate;

/**
 * 迭代燃尽图单个时间点数据。
 *
 * @param dayIndex 迭代第几天（从 1 开始计）
 * @param date 统计对应的日期
 * @param idealPoints 理想线性剩余故事点
 * @param actualPoints 实际剩余故事点
 */
public record BurndownPointDto(
        int dayIndex,
        LocalDate date,
        double idealPoints,
        double actualPoints
) {
}
