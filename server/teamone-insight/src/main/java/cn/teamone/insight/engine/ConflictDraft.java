package cn.teamone.insight.engine;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 冲突判决草稿（引擎输出 → 批算服务落 conflict_snapshot）。
 *
 * @param kind            CF-1..CF-6（快照 kind 列，V7 CHECK）
 * @param severity        red | yellow（原型 ConflictItem.severity）
 * @param subjectType     user | task | release（原型 subjectType；sprint/product 未用）
 * @param subjectId       主体 id（当事人/任务/版本）
 * @param userId          当事人（通知路由与增量删除定位；CF-3 为 null）
 * @param detail          明细文案（对齐原型措辞；人名以 userId/business key 表达）
 * @param relatedTaskIds  关联工作项（原型 relatedTaskIds）
 * @param windowStart     冲突窗口起点（ISO yyyy-MM-dd 序列化；单日窗口 start==end；拿不到为 null）
 * @param windowEnd       冲突窗口终点（与 windowStart 成对出现；null=整个 window 缺省）
 * @param events          参与冲突的工作项（key/title/start/due；尽力而为，拿不到为空表）
 * @param fp              指纹（kind+主体+维度键；红色新增判定与幂等删除定位）
 */
public record ConflictDraft(String kind, String severity, String subjectType, UUID subjectId,
                            UUID userId, String detail, List<UUID> relatedTaskIds,
                            LocalDate windowStart, LocalDate windowEnd, List<Event> events, String fp) {

    /**
     * events 元素（契约键名固定 key/title/start/due）：key 恒有；title/start/due
     * 尽力而为——工作项原值缺省即 null，绝不编造。
     */
    public record Event(String key, String title, LocalDate start, LocalDate due) {
    }

    public ConflictDraft {
        relatedTaskIds = relatedTaskIds == null ? List.of() : List.copyOf(relatedTaskIds);
        events = events == null ? List.of() : List.copyOf(events);
    }
}
