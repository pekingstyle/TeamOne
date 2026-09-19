package cn.teamone.insight.engine;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 版本视图（CF-3 相邻发布挤压 / CF-6 发布日越级）。
 *
 * @param id             版本 id
 * @param key            业务键（v2.4.0）
 * @param name           名称（详情文案）
 * @param productId      所属产品（CF-3 同产品分组）
 * @param planDate       计划发布日（自然日口径，CF-3 gap 起点兼 CF-6 容器）
 * @param codeFreezeDate 代码冻结日（CF-3 gap 终点）
 */
public record ReleaseView(UUID id, String key, String name, UUID productId,
                          LocalDate planDate, LocalDate codeFreezeDate) {
}
