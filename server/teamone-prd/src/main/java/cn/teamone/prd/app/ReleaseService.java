package cn.teamone.prd.app;

import cn.teamone.prd.domain.Release;
import cn.teamone.prd.domain.WorkItem;
import cn.teamone.prd.repo.BlockingDefectView;
import cn.teamone.prd.repo.ReleaseRepository;
import cn.teamone.prd.repo.UnfinishedWorkItemView;
import cn.teamone.prd.repo.WorkItemRepository;
import cn.teamone.platform.authz.PermissionService;
import cn.teamone.platform.audit.AuditService;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.infra.OutboxWriter;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 版本应用服务。publish 门禁事务照抄 05 §6.1：
 *
 * <pre>
 * BEGIN;
 *   SELECT * FROM prd.release WHERE id=:id FOR UPDATE;
 *   SELECT ... FROM prd.work_item WHERE blocked_release_id=:id
 *     AND severity IN ('致命','严重') AND status NOT IN ('已关闭','回归通过');
 *   IF found THEN ROLLBACK; RETURN 4230 + 阻塞清单; END IF;
 *   -- R-9 发布一致性：挂版本的未完结工作项（完结集=WorkItem.doneStatusesOf，与 insight isDone 同口径）
 *   SELECT type, count(*), keys FROM prd.work_item WHERE release_id=:id
 *     AND NOT (完结集) GROUP BY type;
 *   IF found THEN ROLLBACK; RETURN 4231 + 类型分组明细; END IF;
 *   UPDATE prd.release SET status='released', released_at=now();
 *   INSERT INTO infra.outbox_event(type, ...) VALUES('release.published', ...);
 * COMMIT;
 * </pre>
 *
 * <p>成功与被阻塞均写 AuditService.record(actor,"release.publish",...)——被阻塞路径（4230 缺陷
 * 门禁 / 4231 未完结工作项门禁）在事务回滚后以独立短事务落审计（审计不可随门禁回滚丢失）。
 * 已 released 再 publish 抛 {@link ErrorCode#PRD_4201} 状态机拒绝（不许重复事件）。</p>
 */
@Service
public class ReleaseService {

    private final ReleaseRepository releases;
    private final WorkItemRepository workItems;
    private final AppUserRepository users;
    private final PermissionService permissions;
    private final OutboxWriter outbox;
    private final AuditService audit;
    private final Refs refs;
    private final TransactionTemplate tx;

    public ReleaseService(ReleaseRepository releases, WorkItemRepository workItems,
                          AppUserRepository users, PermissionService permissions,
                          OutboxWriter outbox, AuditService audit, Refs refs,
                          PlatformTransactionManager txManager) {
        this.releases = releases;
        this.workItems = workItems;
        this.users = users;
        this.permissions = permissions;
        this.outbox = outbox;
        this.audit = audit;
        this.refs = refs;
        this.tx = new TransactionTemplate(txManager);
    }

    // ==================== 查询 ====================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return releases.findAll(org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String idOrKey) {
        return view(refs.release(idOrKey));
    }

    // ==================== publish 门禁事务 ====================

    public Map<String, Object> publish(String idOrKey, UUID actorId) {
        Release release = refs.release(idOrKey); // 404 先行
        // 动态资源鉴权：版本挂产品，四步授权链（dev1 无授权 → 403）
        permissions.require(actorId, "product", release.getProductId(), "edit");
        try {
            tx.executeWithoutResult(status -> doPublish(release.getId(), actorId));
        } catch (BusinessException e) {
            if (e.errorCode() == ErrorCode.PRD_4230 || e.errorCode() == ErrorCode.PRD_4231) {
                // 被阻塞：主事务已回滚，审计独立短事务落库（成功与被阻塞都记）
                audit.record(actorId, "release.publish", "release", release.getId().toString(),
                        Map.of("result", "blocked", "details", e.details()));
            }
            throw e;
        }
        return get(release.getId().toString());
    }

    /** 门禁事务体（TransactionTemplate 内执行，FOR UPDATE 串行化并发发布） */
    private void doPublish(UUID releaseId, UUID actorId) {
        Release release = releases.findForUpdate(releaseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "版本不存在: " + releaseId));
        if (Release.STATUS_RELEASED.equals(release.getStatus())) {
            // 状态机拒绝：released → released 非法，不许重复事件
            throw new BusinessException(ErrorCode.PRD_4201, "版本已发布，禁止重复发布: " + release.getKey());
        }
        List<BlockingDefectView> open = workItems.findOpenBlockingDefects(releaseId);
        if (!open.isEmpty()) {
            List<String> details = open.stream()
                    .map(v -> v.getKey() + " " + v.getSeverity() + " " + assigneeLabel(v.getAssignee()))
                    .toList();
            throw new BusinessException(ErrorCode.PRD_4230, "发布被致命/严重缺陷阻塞", details);
        }
        // R-9 发布一致性硬门禁：挂该版本的未完结工作项（release_id 关联，完结集见
        // WorkItem.doneStatusesOf，与 insight isDone 同口径）→ 422/T1-PRD-4231 按类型分组列明细
        List<UnfinishedWorkItemView> unfinished = workItems.findUnfinishedByReleaseGrouped(releaseId);
        if (!unfinished.isEmpty()) {
            throw new BusinessException(ErrorCode.PRD_4231, unfinishedMessage(unfinished), unfinishedDetails(unfinished));
        }
        release.setStatus(Release.STATUS_RELEASED);
        release.setReleasedAt(Instant.now());
        outbox.append("release", releaseId, "release.published",
                Map.of("releaseId", releaseId, "key", release.getKey(), "name", release.getName(),
                        "productId", release.getProductId()),
                actorId);
        audit.record(actorId, "release.publish", "release", releaseId.toString(),
                Map.of("result", "released", "key", release.getKey()));
    }

    // ==================== 内部 ====================

    /** 类型中文名（4231 明细分组展示用） */
    private static final Map<String, String> TYPE_ZH = Map.of(
            WorkItem.TYPE_TASK, "任务",
            WorkItem.TYPE_TEST_TASK, "测试任务",
            WorkItem.TYPE_DEFECT, "缺陷",
            WorkItem.TYPE_REQUIREMENT, "需求");

    /**
     * 4231 汇总报文（分组内联）：「未完结工作项：任务×2（T-103、T-104）、缺陷×1（D-87）」。
     * 每组 key 样例最多列 3 个，超出以「等」收尾。
     */
    private String unfinishedMessage(List<UnfinishedWorkItemView> groups) {
        String joined = groups.stream().map(g -> groupLabel(g, 3)).collect(Collectors.joining("、"));
        return "发布被门禁拦截——未完结工作项：" + joined;
    }

    /** 4231 明细行（每类型一行，样例最多 5 个 key）：如「任务×2：T-103、T-104」 */
    private List<String> unfinishedDetails(List<UnfinishedWorkItemView> groups) {
        return groups.stream().map(g -> groupLabel(g, 5)).toList();
    }

    /** 分组标签：「任务×2（T-103、T-104）」；key 样例超限截断加「等」 */
    private String groupLabel(UnfinishedWorkItemView g, int sampleLimit) {
        String zh = TYPE_ZH.getOrDefault(g.getType(), g.getType());
        String raw = g.getKeys() == null ? "" : g.getKeys();
        List<String> keys = raw.isBlank() ? List.of() : List.of(raw.split(","));
        StringBuilder sb = new StringBuilder(zh).append("×").append(g.getCount()).append("（");
        if (keys.isEmpty()) {
            return sb.append("）").toString();
        }
        sb.append(String.join("、", keys.subList(0, Math.min(sampleLimit, keys.size()))));
        if (keys.size() > sampleLimit) {
            sb.append(" 等");
        }
        return sb.append("）").toString();
    }

    private Map<String, Object> view(Release r) {
        Map<UUID, String> keys = r.getBlockedDefectIds().isEmpty() ? Map.of()
                : workItems.findAllById(r.getBlockedDefectIds()).stream()
                        .collect(Collectors.toMap(wi -> wi.getId(), wi -> wi.getKey()));
        return Views.of(r, keys);
    }

    private String assigneeLabel(UUID assigneeId) {
        if (assigneeId == null) {
            return "-";
        }
        return users.findById(assigneeId).map(AppUser::getUsername).orElse(assigneeId.toString());
    }
}
