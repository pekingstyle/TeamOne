package cn.teamone.prd.app;

import cn.teamone.prd.domain.Sprint;
import cn.teamone.prd.domain.WorkItem;
import cn.teamone.prd.repo.SprintRepository;
import cn.teamone.prd.repo.WorkItemRepository;
import cn.teamone.platform.authz.PermissionService;
import cn.teamone.platform.infra.OutboxWriter;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 迭代应用服务（M2-INC-1 W1：05 §3.3 prd 段 GET/POST /sprints + POST /sprints/{id}/complete）。
 *
 * <p>complete（05 §6.1 迭代完成，红线④/⑤）：</p>
 * <pre>
 * BEGIN
 *   未完成工作项滚动：sprint_id 改为下一迭代（同 product + 同 release，start_date 严格
 *   晚于本迭代且最近者；无则置 null），status 保持不动；
 *   sprint.completed_at = now()（V7 加列，NULL=进行中）；
 *   outbox.append("sprint", id, "sprint.completed", payload, actor) —— 与滚动同事务；
 * COMMIT
 * </pre>
 *
 * <p>已完成迭代重复 complete 抛 {@link ErrorCode#PRD_4201}（状态机拒绝，不许重复事件）。
 * 容量落历史与 sprint_ended 话题归档接线属 W2（08 §5 W2 计划）。</p>
 */
@Service
public class SprintService {

    /** 迭代创建请求（productId 必填；releaseId 可选，接受 uuid 或业务键） */
    public record CreateSpec(String name, String productId, String releaseId, Integer capacityHours,
                             LocalDate startDate, LocalDate dueDate) {}

    /** 「已完成」工作项状态全集（滚动排除项）：task=done / requirement=closed /
     *  defect=回归通过、已关闭 / test_task=passed（比 rollup 口径多 passed——测试通过即算迭代内完结） */
    private static final List<String> FINISHED_STATUSES = List.of(
            WorkItem.STATUS_DONE, WorkItem.STATUS_REQ_CLOSED,
            WorkItem.STATUS_DEFECT_REGRESSION_PASSED, WorkItem.STATUS_DEFECT_CLOSED,
            WorkItem.STATUS_PASSED);

    private final SprintRepository sprints;
    private final WorkItemRepository workItems;
    private final PermissionService permissions;
    private final OutboxWriter outbox;
    private final Refs refs;

    public SprintService(SprintRepository sprints, WorkItemRepository workItems,
                         PermissionService permissions, OutboxWriter outbox, Refs refs) {
        this.sprints = sprints;
        this.workItems = workItems;
        this.permissions = permissions;
        this.outbox = outbox;
        this.refs = refs;
    }

    // ==================== 写（四步链鉴权在 service 内） ====================

    @Transactional
    public Map<String, Object> create(CreateSpec spec, UUID actorId) {
        UUID productId = refs.product(requireText(spec.productId(), "productId 必填")).getId();
        permissions.require(actorId, "product", productId, "edit");
        Sprint s = new Sprint();
        s.setName(requireText(spec.name(), "name 必填"));
        s.setProductId(productId);
        s.setReleaseId(spec.releaseId() != null ? refs.release(spec.releaseId()).getId() : null);
        s.setCapacityHours(spec.capacityHours());
        s.setStartDate(spec.startDate());
        s.setDueDate(spec.dueDate());
        return Views.of(sprints.save(s));
    }

    /**
     * 迭代完成（滚动 + 标记 + outbox 同事务，红线④⑤）。
     *
     * @return 完成后的迭代视图（另带 movedCount = 滚动工作项数、nextSprintId = 滚入迭代）
     */
    @Transactional
    public Map<String, Object> complete(String id, UUID actorId) {
        Sprint sprint = refs.sprint(id);
        permissions.require(actorId, "product", sprint.getProductId(), "edit");
        if (sprint.getCompletedAt() != null) {
            throw new BusinessException(ErrorCode.PRD_4201, "迭代已完成，禁止重复完成: " + sprint.getName());
        }

        UUID nextSprintId = nextSprintOf(sprint);
        List<WorkItem> unfinished = workItems.findBySprintIdAndStatusNotIn(sprint.getId(), FINISHED_STATUSES);
        for (WorkItem wi : unfinished) {
            wi.setSprintId(nextSprintId); // 无下一迭代置 null；status 保持不动（红线⑤）
        }
        workItems.saveAll(unfinished);

        sprint.setCompletedAt(Instant.now());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sprintId", sprint.getId());
        payload.put("name", sprint.getName());
        payload.put("productId", sprint.getProductId());
        payload.put("releaseId", sprint.getReleaseId());
        payload.put("nextSprintId", nextSprintId);
        payload.put("movedCount", unfinished.size());
        payload.put("completedAt", sprint.getCompletedAt());
        outbox.append("sprint", sprint.getId(), "sprint.completed", payload, actorId);

        Map<String, Object> view = Views.of(sprint);
        view.put("movedCount", unfinished.size());
        view.put("nextSprintId", nextSprintId);
        return view;
    }

    // ==================== 读（登录态） ====================

    /** 列表（可选 productId 过滤，uuid 或业务键；按 start_date 升序） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String productId) {
        List<Sprint> rows = productId != null && !productId.isBlank()
                ? sprints.findByProductIdOrderByStartDateAsc(refs.product(productId).getId())
                : sprints.findAll(Sort.by(Sort.Direction.ASC, "startDate"));
        return rows.stream().map(Views::of).toList();
    }

    // ==================== 内部 ====================

    /**
     * 下一迭代 = 同 product + 同 release（含双方皆 null）+ start_date 严格晚于本迭代且最近的
     * 一个；本迭代无 start_date 或无候选 → null（未完成项置 null 并保持 status）。
     */
    private UUID nextSprintOf(Sprint current) {
        LocalDate cur = current.getStartDate();
        if (cur == null) {
            return null;
        }
        return sprints.findByProductIdOrderByStartDateAsc(current.getProductId()).stream()
                .filter(s -> !s.getId().equals(current.getId()))
                .filter(s -> Objects.equals(s.getReleaseId(), current.getReleaseId()))
                .filter(s -> s.getStartDate() != null && s.getStartDate().isAfter(cur))
                .min(java.util.Comparator.comparing(Sprint::getStartDate))
                .map(Sprint::getId)
                .orElse(null);
    }

    private String requireText(String v, String message) {
        if (v == null || v.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, message);
        }
        return v;
    }
}
