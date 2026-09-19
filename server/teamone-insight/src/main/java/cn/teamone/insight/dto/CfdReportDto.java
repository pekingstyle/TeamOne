package cn.teamone.insight.dto;

import java.util.List;

/**
 * 累积流图完整报表响应 DTO。
 *
 * @param series 每日累积流状态序列
 */
public record CfdReportDto(
        List<CfdPointDto> series
) {
}
