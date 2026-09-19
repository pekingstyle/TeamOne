/**
 * 概览域（insight）：冲突批算器（CF-1~CF-6）+ 冲突快照读侧（M2-INC-1 W2 首次落地）。
 *
 * <p>模块纪律（架构设计 §1.4 / ArchUnit R6）：纯消费者——只依赖 platform(SPI)+shared，
 * 零 prd/collab/eng/app 编译期依赖；跨域联动只消费 Outbox 事件（workitem.updated →
 * 当事人增量批算），产出只写 prd.conflict_snapshot（INC-1 红线②：冲突真相唯一落点）。</p>
 *
 * <p><b>只读映射 + 快照写声明（红线①，包级唯一声明处）</b>：</p>
 * <ul>
 *   <li>domain 包 Insight* 实体为<b>跨 schema 只读映射</b>（prd.work_item / prd.release /
 *       prd.sprint / prd.work_item_link / platform.calendar / platform.app_user），
 *       逐实体标注列白名单（只映射批算所需列，Hibernate {@code @Immutable} 兜底），
 *       与 prd/platform 权威实体的漂移防线=「schema 变更走迁移评审 checklist」（08 §5 风险表）；</li>
 *   <li><b>快照写</b>：prd.conflict_snapshot 为本域产出表（V7 建于 prd schema，05 §2.3），
 *       insight 持有写实体 {@link cn.teamone.insight.domain.ConflictSnapshot}；</li>
 *   <li><b>写实体例外（总监裁决）</b>：collab.notification 允许 insight 建写实体
 *       {@link cn.teamone.insight.domain.InsightNotification}——红色冲突站内通知落库
 *       （conflict.detected 消费端），同样仅映射写所需列。</li>
 * </ul>
 */
package cn.teamone.insight;
