package cn.teamone.insight.dto;

import java.time.LocalDate;

/**
 * 累积流图 (CFD) 每日工作项状态累计快照数据。
 *
 * @param date 统计对应的日期
 * @param todo 待办状态项累计总数
 * @param inProgress 进行中状态项累计总数
 * @param done 已完成状态项累计总数
 */
public record CfdPointDto(
        LocalDate date,
        int todo,
        int inProgress,
        int done
) {
}
