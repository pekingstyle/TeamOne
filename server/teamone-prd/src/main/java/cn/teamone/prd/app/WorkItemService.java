package cn.teamone.prd.app;

import cn.teamone.prd.domain.Component;
import cn.teamone.prd.domain.Release;
import cn.teamone.prd.domain.RoadmapItem;
import cn.teamone.prd.domain.WorkItem;
import cn.teamone.prd.domain.WorkItemLink;
import cn.teamone.prd.repo.ChildCountView;
import cn.teamone.prd.repo.ProductRepository;
import cn.teamone.prd.repo.ProjectRepository;
import cn.teamone.prd.repo.WorkItemLinkRepository;
import cn.teamone.prd.repo.WorkItemRepository;
import cn.teamone.platform.authz.PermissionService;
import cn.teamone.platform.infra.IdempotencyService;
import cn.teamone.platform.infra.OutboxWriter;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 工作项应用服务（05 §3.3 / §6.1）：CRUD + 发号 + 物化路径 + 门禁挂钩。
 *
 * <p>创建：type=defect 必填 severity（CHECK 双保险）→ KeySequenceService 发号填 key →
 * path 简化拼接 /{goalId或productId}/{id}/（parentId 存在时接父路径）→
 * defect 带 blockedReleaseId 时同事务 recalcGate；Idempotency-Key 快照与业务写同一事务。</p>
 *
 * <p>更新：If-Match 乐观锁（不匹配抛 {@link ErrorCode#PLT_4091}，409，details 带最新 version）；
 * 改 severity/blockedReleaseId → recalcGate（旧/新版本都重算）。</p>
 *
 * <p>R-5 AC③ work_item 直连口径（docs/v2/12，总监已批准）——工作项与目标两条路径：
 * 条目路径（roadmap_item_id ∈ 目标条目集合，goal_id 由条目派生）与直挂目标路径
 * （goal_id 直挂、roadmap_item_id 为空）；create/update 写 roadmap_item_id 时自动
 * 派生 goal_id = 条目.goal_id，保证双路径不漂移。</p>
 *
 * <p>⑥o 客制化双轨（docs/v2/15）：create/update 透传 origin/sourceProjectId
 * （origin=project_custom 时 sourceProjectId 必填且存在）；promoteToProduct 回流——
 * 复制为同产品 origin=product 新需求 + 回写 promoted_to_id + 建 link(promoted_from)，
 * 同事务原子完成。</p>
 */
@Service
public class WorkItemService {

    private static final Set<String> TYPES = Set.of(
            WorkItem.TYPE_REQUIREMENT, WorkItem.TYPE_TASK, WorkItem.TYPE_TEST_TASK, WorkItem.TYPE_DEFECT);
    private static final Set<String> SEVERITIES = Set.of(
            WorkItem.SEVERITY_FATAL, WorkItem.SEVERITY_CRITICAL,
            WorkItem.SEVERITY_MAJOR, WorkItem.SEVERITY_MINOR);

    /** 需求来源合法值（⑥o 客制化双轨，与 ck_work_item origin CHECK 字节级一致） */
    private static final Set<String> ORIGINS = Set.of(
            WorkItem.ORIGIN_PRODUCT, WorkItem.ORIGIN_PROJECT_CUSTOM);

    /** 发号类型映射：work_item.type → prd.key_sequence.type */
    private static final Map<String, String> SEQUENCE_TYPES = Map.of(
            WorkItem.TYPE_DEFECT, "DEFECT",
            WorkItem.TYPE_TASK, "TASK",
            WorkItem.TYPE_TEST_TASK, "TEST_TASK",
            WorkItem.TYPE_REQUIREMENT, "REQUIREMENT");

    private static final Map<String, String> INITIAL_STATUS = Map.of(
            WorkItem.TYPE_TASK, WorkItem.STATUS_TODO,
            WorkItem.TYPE_TEST_TASK, WorkItem.STATUS_PENDING,
            WorkItem.TYPE_DEFECT, WorkItem.STATUS_DEFECT_NEW,
            WorkItem.TYPE_REQUIREMENT, WorkItem.STATUS_REQ_DRAFT);

    /** 创建请求（controller 由请求体反序列化；引用字段接受 uuid 或业务键） */
    public record CreateSpec(String type, String title, String description, String priority,
                             String assigneeId, String reporterId, String productId, String componentId,
                             String parentId, String goalId, String sprintId, String releaseId,
                             String roadmapItemId,
                             BigDecimal storyPoints, BigDecimal estimateHours,
                             LocalDate startDate, LocalDate dueDate,
                             List<String> labels, String severity, String blockedReleaseId,
                             String requirementId,
                             String origin, String sourceProjectId) {}

    /** 更新请求（null=不变更；blockedReleaseId 空串=解除阻塞） */
    public record UpdateSpec(String title, String description, String priority, String assigneeId,
                             String componentId, String sprintId, String releaseId, String roadmapItemId,
                             BigDecimal storyPoints, BigDecimal estimateHours,
                             LocalDate startDate, LocalDate dueDate,
                             List<String> labels, String severity, String blockedReleaseId,
                             String origin, String sourceProjectId) {}

    private final WorkItemRepository workItems;
    private final KeySequenceService sequences;
    private final GateService gateService;
    private final PermissionService permissions;
    private final IdempotencyService idempotency;
    private final OutboxWriter outbox;
    private final Refs refs;
    private final EntityManager em;
    private final WorkItemLinkRepository links;
    private final ProjectRepository projects;

    public WorkItemService(WorkItemRepository workItems, KeySequenceService sequences,
                           GateService gateService, PermissionService permissions,
                           IdempotencyService idempotency, OutboxWriter outbox, Refs refs,
                           EntityManager em, WorkItemLinkRepository links, ProjectRepository projects) {
        this.workItems = workItems;
        this.sequences = sequences;
        this.gateService = gateService;
        this.permissions = permissions;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.refs = refs;
        this.em = em;
        this.links = links;
        this.projects = projects;
    }

    // ==================== 创建 ====================

    @Transactional
    public Map<String, Object> create(CreateSpec spec, UUID actorId, String idempotencyKey) {
        validateTypeAndSeverity(spec.type(), spec.severity());

        WorkItem wi = new WorkItem();
        wi.setType(spec.type());
        wi.setTitle(requireText(spec.title(), "title 必填"));
        wi.setDescription(spec.description());
        wi.setPriority(spec.priority());
        wi.setStatus(INITIAL_STATUS.get(spec.type()));
        wi.setKey(sequences.nextKey(SEQUENCE_TYPES.get(spec.type())));
        wi.setAssigneeId(refs.userIdOrNull(spec.assigneeId()));
        wi.setReporterId(spec.reporterId() != null ? refs.userId(spec.reporterId()) : actorId);

        UUID productId = spec.productId() != null ? refs.product(spec.productId()).getId() : null;
        if (spec.componentId() != null) {
            Component component = refs.component(spec.componentId());
            wi.setComponentId(component.getId());
            if (productId == null) {
                productId = component.getProductId();
            }
        }
        if (productId != null) {
            wi.setProductId(productId);
        }
        if (spec.parentId() != null) {
            WorkItem parent = refs.workItem(spec.parentId());
            wi.setParentId(parent.getId());
            if (wi.getProductId() == null) {
                wi.setProductId(parent.getProductId());
            }
        }
        if (spec.goalId() != null) {
            wi.setGoalId(refs.goal(spec.goalId()).getId());
        }
        if (spec.sprintId() != null) {
            wi.setSprintId(refs.sprint(spec.sprintId()).getId());
        }
        if (spec.releaseId() != null) {
            wi.setReleaseId(refs.release(spec.releaseId()).getId());
        }
        if (spec.roadmapItemId() != null) {
            // R-5 AC③ 数据收敛（双路径一致）：挂条目即以条目的 goal_id 派生工作项 goal_id
            // （条目无 goal 则置空）。条目路径权威——显式 goalId 与条目归属矛盾时以条目为准，
            // 避免历史断链（roadmap_item_id 无人赋值/双路径漂移）重演
            RoadmapItem item = refs.roadmapItem(spec.roadmapItemId());
            wi.setRoadmapItemId(item.getId());
            wi.setGoalId(item.getGoalId());
        }
        wi.setStoryPoints(spec.storyPoints());
        wi.setEstimateHours(spec.estimateHours());
        wi.setStartDate(spec.startDate());
        wi.setDueDate(spec.dueDate());
        wi.setLabels(spec.labels() == null ? "[]" : spec.labels().toString());
        wi.setRequirementId(refs.workItemIdOrNull(spec.requirementId()));
        // ⑥o 客制化双轨：来源透传（origin=project_custom 时 sourceProjectId 必填且存在）
        applyOrigin(wi, spec.origin(), spec.sourceProjectId());

        // L1 前置：defect 专用列（severity 必填已校验）；blockedRelease 可补出 productId
        if (WorkItem.TYPE_DEFECT.equals(spec.type())) {
            wi.setSeverity(spec.severity());
            if (spec.blockedReleaseId() != null) {
                Release blocked = refs.release(spec.blockedReleaseId());
                wi.setBlockedReleaseId(blocked.getId());
                if (wi.getProductId() == null) {
                    wi.setProductId(blocked.getProductId());
                }
            }
        }

        // path 根：goal 优先，退化 productId（M1 简化：/{goalId或productId}/{id}/）
        UUID pathRoot = wi.getGoalId() != null ? wi.getGoalId() : wi.getProductId();
        if (pathRoot == null) {
            throw new BusinessException(ErrorCode.PLT_4000, "path 根缺失：productId 或 goalId 必填其一");
        }

        // path 含 id：先以占位落库拿 id，再补真实路径（同事务一次 UPDATE）
        wi.setPath("/");
        workItems.saveAndFlush(wi);
        wi.setPath(wi.getParentId() != null
                ? refs.workItem(spec.parentId()).getPath() + wi.getId() + "/"
                : "/" + pathRoot + "/" + wi.getId() + "/");

        // L1：defect 挂阻塞版本 → 创建后同事务重算门禁
        if (wi.getBlockedReleaseId() != null) {
            gateService.recalcGate(wi.getBlockedReleaseId(), actorId);
        }

        // L2 广播（W3 新增，权威目录 §5.1）：workitem.created（defect 必发、其他类型也发；
        // collab 按 type=defect 且 severity=致命 自动建题+拉人+系统消息）
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("id", wi.getId());
        created.put("key", wi.getKey());
        created.put("type", wi.getType());
        created.put("title", wi.getTitle());
        created.put("severity", wi.getSeverity());
        created.put("blockedReleaseId", wi.getBlockedReleaseId());
        created.put("reporterId", wi.getReporterId());
        created.put("assigneeId", wi.getAssigneeId());
        if (WorkItem.TYPE_DEFECT.equals(wi.getType())) {
            created.put("stakeholderUserIds", defectStakeholders(wi));
        }
        outbox.append("work_item", wi.getId(), "workitem.created", created, actorId);

        Map<String, Object> view = Views.of(wi);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            // B1：原生 INSERT ON CONFLICT DO NOTHING，首写原子胜出；与业务写同一事务
            idempotency.record(idempotencyKey, 201, view);
        }
        return view;
    }

    // ==================== 查询 ====================

    @Transactional(readOnly = true)
    public Map<String, Object> get(String idOrKey) {
        return Views.of(refs.workItem(idOrKey));
    }

    /** 列表：type/status/assignee(uuid 或 username)/sprintId/q(key 或 title 模糊)/origin/sourceProjectId 过滤，page 从 1 起 */
    @Transactional(readOnly = true)
    public Map<String, Object> list(String type, String status, String assignee, UUID sprintId,
                                    String q, String origin, UUID sourceProjectId,
                                    int page, int size) {
        UUID assigneeId = refs.userIdOrNull(assignee);
        int safeSize = Math.min(Math.max(size, 1), 200);
        PageRequest pageable = PageRequest.of(Math.max(page - 1, 0), safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<WorkItem> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (type != null && !type.isBlank()) {
                ps.add(cb.equal(root.get("type"), type));
            }
            if (status != null && !status.isBlank()) {
                ps.add(cb.equal(root.get("status"), status));
            }
            if (assigneeId != null) {
                ps.add(cb.equal(root.get("assigneeId"), assigneeId));
            }
            if (sprintId != null) {
                ps.add(cb.equal(root.get("sprintId"), sprintId));
            }
            if (origin != null && !origin.isBlank()) {
                ps.add(cb.equal(root.get("origin"), origin));
            }
            if (sourceProjectId != null) {
                ps.add(cb.equal(root.get("sourceProjectId"), sourceProjectId));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.toLowerCase() + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("key")), like),
                        cb.like(cb.lower(root.get("title")), like)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<WorkItem> result = workItems.findAll(spec, pageable);
        List<WorkItem> rows = result.getContent();
        Map<UUID, ChildCountView> childStats = requirementChildStats(rows);
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (WorkItem wi : rows) {
            Map<String, Object> view = Views.of(wi);
            if (WorkItem.TYPE_REQUIREMENT.equals(wi.getType())) {
                ChildCountView stat = childStats.get(wi.getId());
                view.put("taskCount", stat == null ? 0L : stat.getTaskCount());
                view.put("taskDoneCount", stat == null ? 0L : stat.getTaskDoneCount());
            }
            items.add(view);
        }
        return Map.of("total", result.getTotalElements(), "page", page, "size", safeSize, "items", items);
    }

    // ==================== 更新 ====================

    @Transactional
    public Map<String, Object> update(String idOrKey, UpdateSpec spec, Integer ifMatch, UUID actorId) {
        WorkItem wi = refs.workItem(idOrKey);
        permissions.require(actorId, "product", wi.getProductId(), "edit");

        if (ifMatch == null) {
            throw new BusinessException(ErrorCode.PLT_4000, "缺少 If-Match 版本头");
        }
        if (ifMatch != wi.getVersion()) {
            // 409，响应体 details 带最新 version
            throw new BusinessException(ErrorCode.PLT_4091, "版本冲突，请刷新后重试",
                    List.of("currentVersion=" + wi.getVersion()));
        }

        if (spec.title() != null) {
            wi.setTitle(spec.title());
        }
        if (spec.description() != null) {
            wi.setDescription(spec.description());
        }
        if (spec.priority() != null) {
            wi.setPriority(spec.priority());
        }
        if (spec.assigneeId() != null) {
            wi.setAssigneeId(refs.userId(spec.assigneeId()));
        }
        if (spec.componentId() != null) {
            wi.setComponentId(refs.component(spec.componentId()).getId());
        }
        if (spec.sprintId() != null) {
            wi.setSprintId(refs.sprint(spec.sprintId()).getId());
        }
        if (spec.releaseId() != null) {
            wi.setReleaseId(refs.release(spec.releaseId()).getId());
        }
        if (spec.roadmapItemId() != null) {
            // R-5 AC③ 数据收敛（与 create 同规则）：改挂条目即重派生 goal_id（条目无 goal 则置空）；
            // 直挂 goal_id 的存量值不动（UpdateSpec 无 goalId 字段，直挂路径只经 create/专用接口）
            RoadmapItem item = refs.roadmapItem(spec.roadmapItemId());
            wi.setRoadmapItemId(item.getId());
            wi.setGoalId(item.getGoalId());
        }
        if (spec.storyPoints() != null) {
            wi.setStoryPoints(spec.storyPoints());
        }
        if (spec.estimateHours() != null) {
            wi.setEstimateHours(spec.estimateHours());
        }
        if (spec.startDate() != null) {
            wi.setStartDate(spec.startDate());
        }
        if (spec.dueDate() != null) {
            wi.setDueDate(spec.dueDate());
        }
        if (spec.labels() != null) {
            wi.setLabels(spec.labels().toString());
        }
        // ⑥o 客制化双轨：来源可改（origin/sourceProjectId 任一显式出现即重算；同 create 校验口径）
        if (spec.origin() != null || spec.sourceProjectId() != null) {
            applyOrigin(wi, spec.origin(), spec.sourceProjectId());
        }

        boolean gateNeeded = false;
        UUID oldBlocked = wi.getBlockedReleaseId();
        if (spec.severity() != null) {
            requireDefect(wi, "severity 仅 defect 可设");
            if (!SEVERITIES.contains(spec.severity())) {
                throw new BusinessException(ErrorCode.PLT_4000, "severity 非法: " + spec.severity());
            }
            wi.setSeverity(spec.severity());
            gateNeeded = true;
        }
        if (spec.blockedReleaseId() != null) {
            requireDefect(wi, "blockedReleaseId 仅 defect 可设");
            wi.setBlockedReleaseId(spec.blockedReleaseId().isBlank()
                    ? null // 空串=解除阻塞
                    : refs.release(spec.blockedReleaseId()).getId());
            gateNeeded = true;
        }

        if (gateNeeded) {
            if (oldBlocked != null) {
                gateService.recalcGate(oldBlocked, actorId);
            }
            UUID nowBlocked = wi.getBlockedReleaseId();
            if (nowBlocked != null && !nowBlocked.equals(oldBlocked)) {
                gateService.recalcGate(nowBlocked, actorId);
            }
        }

        // L2 广播（M2-INC-1 W2 增订，权威目录 §5.1）：workitem.updated——PUT 成功路径补发，
        // insight 按 assigneeId 触发该当事人冲突增量批算（02 FR-v2-04「变更增量」，每日全量兜底）
        Map<String, Object> updated = new LinkedHashMap<>();
        updated.put("id", wi.getId());
        updated.put("key", wi.getKey());
        updated.put("assigneeId", wi.getAssigneeId());
        updated.put("startDate", wi.getStartDate());
        updated.put("dueDate", wi.getDueDate());
        updated.put("actorId", actorId);
        outbox.append("work_item", wi.getId(), "workitem.updated", updated, actorId);

        return Views.of(wi);
    }

    // ==================== ⑥o 客制化回流（promote-to-product，docs/v2/15 §3.4） ====================

    /**
     * 客制化需求回流产品：同产品复制一条 origin=product 新需求（title/description/
     * storyPoints/优先级承接，status=draft 进 backlog），回写原需求 promoted_to_id，
     * 建 link(relation=promoted_from)——同事务原子完成，返回新需求视图。
     *
     * <p>前置校验（违反 → 422 {@link ErrorCode#PRD_4256}）：type=requirement、
     * origin=project_custom、promoted_to_id 为空（重复回流幂等防重）。
     * 注：文档口径 status=todo，但 requirement 状态 CHECK（ck_work_item_type_status）
     * 只允许 draft 起——进 backlog 语义落地为 draft（待评审受理）。</p>
     *
     * <p>并发护栏：{@code @Version} 乐观锁——两并发回流同一需求，后落库方对 source 的
     * UPDATE 命中 0 行而整体回滚（含复制与建链），不会产生重复产品需求或悬空链；
     * 败者抛乐观锁异常（与全仓异常映射现状一致）。</p>
     */
    @Transactional
    public Map<String, Object> promoteToProduct(String idOrKey, UUID actorId) {
        WorkItem source = refs.workItem(idOrKey);
        if (!WorkItem.TYPE_REQUIREMENT.equals(source.getType())) {
            throw new BusinessException(ErrorCode.PRD_4256, "仅 requirement 可回流产品");
        }
        if (!WorkItem.ORIGIN_PROJECT_CUSTOM.equals(source.getOrigin())) {
            throw new BusinessException(ErrorCode.PRD_4256, "仅客制化需求（origin=project_custom）可回流产品");
        }
        if (source.getPromotedToId() != null) {
            throw new BusinessException(ErrorCode.PRD_4256);
        }
        if (source.getProductId() == null) {
            throw new BusinessException(ErrorCode.PLT_4000, "需求未挂产品，无法回流");
        }
        permissions.require(actorId, "product", source.getProductId(), "edit");

        WorkItem target = new WorkItem();
        target.setType(WorkItem.TYPE_REQUIREMENT);
        target.setTitle(source.getTitle());
        target.setDescription(source.getDescription());
        target.setStatus(WorkItem.STATUS_REQ_DRAFT);
        target.setPriority(source.getPriority());
        target.setStoryPoints(source.getStoryPoints());
        target.setReporterId(actorId);
        target.setProductId(source.getProductId());
        target.setLabels(source.getLabels());
        // QA ②随批闭环：承接所属组件——回流对齐场景真实可现（复制体进 E 集，预警项可辨识）
        target.setComponentId(source.getComponentId());
        target.setOrigin(WorkItem.ORIGIN_PRODUCT); // 回流目标恒为产品标准需求
        target.setKey(sequences.nextKey(SEQUENCE_TYPES.get(WorkItem.TYPE_REQUIREMENT)));

        // path 根：goal 优先，退化 productId（与 create 同规则；回流目标不承接条目/目标挂载）
        UUID pathRoot = target.getGoalId() != null ? target.getGoalId() : target.getProductId();
        target.setPath("/");
        workItems.saveAndFlush(target); // 先以占位落库拿 id，再补真实路径（同事务一次 UPDATE）
        target.setPath("/" + pathRoot + "/" + target.getId() + "/");

        // 回写回流目标（非空=已回流，幂等防重）
        source.setPromotedToId(target.getId());

        // 回流可追溯链：from=原客制需求，to=新产品需求（V23 relation CHECK 扩值）
        WorkItemLink link = new WorkItemLink();
        link.setFromItemId(source.getId());
        link.setToItemId(target.getId());
        link.setRelation(WorkItemLink.RELATION_PROMOTED_FROM);
        links.save(link);

        // L2 广播：回流产生的新产品需求同 workitem.created（与 create 同构，附回流来源）
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("id", target.getId());
        created.put("key", target.getKey());
        created.put("type", target.getType());
        created.put("title", target.getTitle());
        created.put("promotedFromId", source.getId());
        created.put("reporterId", target.getReporterId());
        outbox.append("work_item", target.getId(), "workitem.created", created, actorId);

        return Views.of(target);
    }

    // ==================== 内部 ====================

    /**
     * 列表 requirement 行的拆解任务统计（一次 IN 聚合防 N+1，列表 size≤200）：
     * children 按 parent_id、type ∈ task/test_task/defect，done 口径与
     * {@link WorkItem#doneStatusesOf} 同源；无 requirement 行时零查询直返空表。
     */
    private Map<UUID, ChildCountView> requirementChildStats(List<WorkItem> rows) {
        List<UUID> requirementIds = rows.stream()
                .filter(wi -> WorkItem.TYPE_REQUIREMENT.equals(wi.getType()))
                .map(WorkItem::getId)
                .toList();
        if (requirementIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ChildCountView> byParent = new HashMap<>();
        for (ChildCountView v : workItems.childStatsByParentIds(requirementIds)) {
            byParent.put(v.getParentId(), v);
        }
        return byParent;
    }

    private void validateTypeAndSeverity(String type, String severity) {
        if (type == null || !TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.PLT_4000, "type 非法: " + type);
        }
        if (WorkItem.TYPE_DEFECT.equals(type)) {
            if (severity == null || !SEVERITIES.contains(severity)) {
                throw new BusinessException(ErrorCode.PLT_4000, "defect 必填合法 severity: " + severity);
            }
        } else if (severity != null) {
            throw new BusinessException(ErrorCode.PLT_4000, "非 defect 类型不允许 severity");
        }
    }

    private void requireDefect(WorkItem wi, String message) {
        if (!WorkItem.TYPE_DEFECT.equals(wi.getType())) {
            throw new BusinessException(ErrorCode.PLT_4000, message);
        }
    }

    /**
     * 来源归属统一收口（⑥o 客制化双轨，create/update 共用）：
     * origin 缺省 product（存量实体已有值时保持不变）；origin=project_custom 时
     * sourceProjectId 必填且存在（否则 400）；origin=product 时忽略 sourceProjectId 置 null。
     */
    private void applyOrigin(WorkItem wi, String originSpec, String sourceSpec) {
        String origin = (originSpec == null || originSpec.isBlank())
                ? (wi.getOrigin() == null ? WorkItem.ORIGIN_PRODUCT : wi.getOrigin())
                : originSpec;
        if (!ORIGINS.contains(origin)) {
            throw new BusinessException(ErrorCode.PLT_4000, "origin 非法: " + origin);
        }
        if (WorkItem.ORIGIN_PROJECT_CUSTOM.equals(origin)) {
            // 未显式传 sourceProjectId 时保持既有来源（update 语义），create 时既有为 null → 400 兜底
            String source = (sourceSpec == null || sourceSpec.isBlank())
                    ? (wi.getSourceProjectId() == null ? null : wi.getSourceProjectId().toString())
                    : sourceSpec;
            wi.setSourceProjectId(resolveSourceProject(source));
        } else {
            // ⑥p QA 挂账清偿：product 来源显式传 sourceProjectId → 400（原先静默丢弃无提示）
            if (sourceSpec != null && !sourceSpec.isBlank()) {
                throw new BusinessException(ErrorCode.PLT_4000, "产品来源需求不可设来源项目（sourceProjectId 仅客制化来源可用）");
            }
            wi.setSourceProjectId(null);
        }
        wi.setOrigin(origin);
    }

    /** 来源项目解析（uuid 且必须存在；缺省/不存在 → 400） */
    private UUID resolveSourceProject(String sourceProjectId) {
        if (sourceProjectId == null || sourceProjectId.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "origin=project_custom 时 sourceProjectId 必填");
        }
        UUID id;
        try {
            id = UUID.fromString(sourceProjectId.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PLT_4000, "sourceProjectId 非法: " + sourceProjectId);
        }
        if (!projects.existsById(id)) {
            throw new BusinessException(ErrorCode.PLT_4000, "来源项目不存在: " + sourceProjectId);
        }
        return id;
    }

    private String requireText(String v, String message) {
        if (v == null || v.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, message);
        }
        return v;
    }

    /** defect 建题干系人（05 §6.2 stakeholder_rule）：reporter+assignee+产品 owner（去重保序） */
    private List<UUID> defectStakeholders(WorkItem wi) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        if (wi.getReporterId() != null) {
            ids.add(wi.getReporterId());
        }
        if (wi.getAssigneeId() != null) {
            ids.add(wi.getAssigneeId());
        }
        if (wi.getProductId() != null) {
            UUID productOwner = refs.product(wi.getProductId().toString()).getOwnerId();
            if (productOwner != null) {
                ids.add(productOwner);
            }
        }
        return List.copyOf(ids);
    }
}
