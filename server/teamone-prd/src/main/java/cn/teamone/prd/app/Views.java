package cn.teamone.prd.app;

import cn.teamone.prd.domain.Component;
import cn.teamone.prd.domain.Product;
import cn.teamone.prd.domain.Release;
import cn.teamone.prd.domain.RequirementReview;
import cn.teamone.prd.domain.RoadmapItem;
import cn.teamone.prd.domain.Sprint;
import cn.teamone.prd.domain.StrategicGoal;
import cn.teamone.prd.domain.WorkItem;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 实体视图装配（M1 手工 Map；字段级契约由 springdoc 反映）。
 * 时间统一 ISO-8601；null 字段由 Jackson non_null 全局策略剔除。
 */
public final class Views {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private Views() {
    }

    public static Map<String, Object> of(WorkItem wi) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", wi.getId());
        m.put("key", wi.getKey());
        m.put("type", wi.getType());
        m.put("title", wi.getTitle());
        m.put("description", wi.getDescription());
        m.put("status", wi.getStatus());
        m.put("priority", wi.getPriority());
        m.put("assigneeId", wi.getAssigneeId());
        m.put("reporterId", wi.getReporterId());
        m.put("productId", wi.getProductId());
        m.put("componentId", wi.getComponentId());
        m.put("parentId", wi.getParentId());
        m.put("path", wi.getPath());
        m.put("sprintId", wi.getSprintId());
        m.put("releaseId", wi.getReleaseId());
        m.put("roadmapItemId", wi.getRoadmapItemId());
        m.put("goalId", wi.getGoalId());
        m.put("requirementId", wi.getRequirementId());
        m.put("foundInId", wi.getFoundInId());
        m.put("relatedId", wi.getRelatedId());
        m.put("blockedReleaseId", wi.getBlockedReleaseId());
        m.put("storyPoints", wi.getStoryPoints());
        m.put("estimateHours", wi.getEstimateHours());
        m.put("startDate", wi.getStartDate() == null ? null : wi.getStartDate().format(DATE));
        m.put("dueDate", wi.getDueDate() == null ? null : wi.getDueDate().format(DATE));
        m.put("labels", wi.getLabels());
        m.put("severity", wi.getSeverity());
        m.put("version", wi.getVersion());
        m.put("createdAt", wi.getCreatedAt());
        m.put("updatedAt", wi.getUpdatedAt());
        return m;
    }

    /** blockedDefectKeys：冗余 id 数组的业务键投影（V-2 矩阵以 D-88 直读） */
    public static Map<String, Object> of(Release r, Map<UUID, String> defectKeys) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.getId());
        m.put("key", r.getKey());
        m.put("name", r.getName());
        m.put("productId", r.getProductId());
        m.put("roadmapItemId", r.getRoadmapItemId());
        m.put("status", r.getStatus());
        m.put("planDate", r.getPlanDate() == null ? null : r.getPlanDate().format(DATE));
        m.put("codeFreezeDate", r.getCodeFreezeDate() == null ? null : r.getCodeFreezeDate().format(DATE));
        m.put("blocked", r.isBlocked());
        m.put("blockedDefectIds", new ArrayList<>(r.getBlockedDefectIds()));
        List<String> keys = new ArrayList<>();
        for (UUID id : r.getBlockedDefectIds()) {
            keys.add(defectKeys.getOrDefault(id, id.toString()));
        }
        m.put("blockedDefectKeys", keys);
        m.put("envProgress", r.getEnvProgress());
        m.put("baselineId", r.getBaselineId());
        m.put("releasedAt", r.getReleasedAt());
        m.put("version", r.getVersion());
        m.put("createdAt", r.getCreatedAt());
        m.put("updatedAt", r.getUpdatedAt());
        return m;
    }

    public static Map<String, Object> of(RequirementReview rr) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", rr.getId());
        m.put("requirementId", rr.getRequirementId());
        m.put("round", rr.getRound());
        m.put("reviewerId", rr.getReviewerId());
        m.put("result", rr.getResult());
        m.put("comment", rr.getComment());
        m.put("decidedAt", rr.getDecidedAt());
        m.put("version", rr.getVersion());
        m.put("createdAt", rr.getCreatedAt());
        return m;
    }

    // ==================== 层级实体视图（M2-INC-1 W1：05 §2.3 层级链） ====================

    /** 战略目标（roadmap_item 无业务 key 列，rollup 行不含 key 字段——见 GoalService） */
    public static Map<String, Object> of(StrategicGoal g) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", g.getId());
        m.put("name", g.getName());
        m.put("description", g.getDescription());
        m.put("ownerId", g.getOwnerId());
        m.put("parentId", g.getParentId());
        m.put("version", g.getVersion());
        m.put("createdAt", g.getCreatedAt());
        m.put("updatedAt", g.getUpdatedAt());
        return m;
    }

    public static Map<String, Object> of(Product p) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", p.getId());
        m.put("key", p.getKey());
        m.put("name", p.getName());
        m.put("description", p.getDescription());
        m.put("goalId", p.getGoalId());
        m.put("ownerId", p.getOwnerId());
        m.put("version", p.getVersion());
        m.put("createdAt", p.getCreatedAt());
        m.put("updatedAt", p.getUpdatedAt());
        return m;
    }

    public static Map<String, Object> of(Component c) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", c.getId());
        m.put("key", c.getKey());
        m.put("name", c.getName());
        m.put("description", c.getDescription());
        m.put("productId", c.getProductId());
        m.put("ownerId", c.getOwnerId());
        m.put("version", c.getVersion());
        m.put("createdAt", c.getCreatedAt());
        m.put("updatedAt", c.getUpdatedAt());
        return m;
    }

    public static Map<String, Object> of(RoadmapItem r) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("description", r.getDescription());
        m.put("productId", r.getProductId());
        m.put("goalId", r.getGoalId());
        m.put("releaseId", r.getReleaseId());
        m.put("ownerId", r.getOwnerId());
        m.put("startDate", r.getStartDate() == null ? null : r.getStartDate().format(DATE));
        m.put("dueDate", r.getDueDate() == null ? null : r.getDueDate().format(DATE));
        m.put("version", r.getVersion());
        m.put("createdAt", r.getCreatedAt());
        m.put("updatedAt", r.getUpdatedAt());
        return m;
    }

    public static Map<String, Object> of(Sprint s) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("productId", s.getProductId());
        m.put("releaseId", s.getReleaseId());
        m.put("capacityHours", s.getCapacityHours());
        m.put("startDate", s.getStartDate() == null ? null : s.getStartDate().format(DATE));
        m.put("dueDate", s.getDueDate() == null ? null : s.getDueDate().format(DATE));
        m.put("completedAt", s.getCompletedAt());
        m.put("version", s.getVersion());
        m.put("createdAt", s.getCreatedAt());
        m.put("updatedAt", s.getUpdatedAt());
        return m;
    }
}
