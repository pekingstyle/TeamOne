package cn.teamone.insight.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 迭代燃尽图完整报表响应 DTO。
 *
 * @param sprintId 迭代唯一标识
 * @param sprintName 迭代名称
 * @param startDate 迭代开始日期
 * @param dueDate 迭代结束日期
 * @param totalPoints 迭代纳入的总故事点
 * @param timeline 每日燃尽数据序列
 */
public record BurndownReportDto(
        UUID sprintId,
        String sprintName,
        LocalDate startDate,
        LocalDate dueDate,
        BigDecimal totalPoints,
        List<BurndownPointDto> timeline
) {
}
