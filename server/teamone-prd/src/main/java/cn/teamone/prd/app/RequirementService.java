package cn.teamone.prd.app;

import cn.teamone.prd.domain.RequirementReview;
import cn.teamone.prd.domain.ReviewRound;
import cn.teamone.prd.domain.WorkItem;
import cn.teamone.prd.domain.transition.TransitionTable;
import cn.teamone.prd.repo.RequirementReviewRepository;
import cn.teamone.prd.repo.ReviewRoundRepository;
import cn.teamone.prd.repo.WorkItemRepository;
import cn.teamone.platform.authz.PermissionService;
import cn.teamone.platform.domain.FileObject;
import cn.teamone.platform.infra.OutboxWriter;
import cn.teamone.platform.repo.FileObjectRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import cn.teamone.shared.api.PermissionDeniedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 需求评审流应用服务（05 §6.1 会签）。
 *
 * <p>submit：draft→pending_review，round+1 并为本轮 reviewerIds 逐个建 pending 行，
 * 同事务 outbox(requirement.submitted)；review：更新当前轮该评审人行，全员 approved→accepted、
 * 任一 rejected→draft（行保留记录），同事务 outbox(requirement.review_result)；
 * schedule/deliver/accept 见各方法；in_dev 手动走 transition 接口（转移表 accepted→in_dev）。</p>
 */
@Service
public class RequirementService {

    private final WorkItemRepository workItems;
    private final RequirementReviewRepository reviews;
    private final ReviewRoundRepository rounds;
    private final FileObjectRepository files;
    private final PermissionService permissions;
    private final OutboxWriter outbox;
    private final Refs refs;

    public RequirementService(WorkItemRepository workItems, RequirementReviewRepository reviews,
                              ReviewRoundRepository rounds, FileObjectRepository files,
                              PermissionService permissions, OutboxWriter outbox, Refs refs) {
        this.workItems = workItems;
        this.reviews = reviews;
        this.rounds = rounds;
        this.files = files;
        this.permissions = permissions;
        this.outbox = outbox;
        this.refs = refs;
    }

    // ==================== submit ====================

    @Transactional
    public Map<String, Object> submit(String idOrKey, List<String> reviewerIds, UUID actorId) {
        WorkItem wi = refs.workItem(idOrKey);
        assertRequirement(wi);
        permissions.require(actorId, "product", wi.getProductId(), "edit");

        if (!WorkItem.STATUS_REQ_DRAFT.equals(wi.getStatus())) {
            throw new BusinessException(ErrorCode.PRD_4201,
                    "仅 draft 可提交评审，当前 " + wi.getStatus(),
                    List.of("from=" + wi.getStatus(), "to=" + WorkItem.STATUS_REQ_PENDING_REVIEW));
        }
        if (reviewerIds == null || reviewerIds.isEmpty()) {
            throw new BusinessException(ErrorCode.PLT_4000, "reviewerIds 必填");
        }
        List<UUID> reviewers = reviewerIds.stream().map(refs::userId).toList();

        int round = currentRound(wi.getId()) + 1;
        for (UUID reviewerId : reviewers) {
            RequirementReview row = new RequirementReview();
            row.setRequirementId(wi.getId());
            row.setRound(round);
            row.setReviewerId(reviewerId);
            reviews.save(row);
        }
        // R-6：轮次行同步建（纪要/结论稀疏随行，concluded_at 评审中为 NULL；驳回重提 round+1 即新行）
        upsertRound(wi.getId(), round);
        wi.setStatus(WorkItem.STATUS_REQ_PENDING_REVIEW);

        // payload 补全（W3 权威目录 §5.1）：+title、+stakeholderUserIds（collab 自动建题拉人数据源）
        outbox.append("work_item", wi.getId(), "requirement.submitted",
                Map.of("requirementId", wi.getId(), "key", wi.getKey(),
                        "title", wi.getTitle(),
                        "round", round, "reviewerIds", reviewers,
                        "stakeholderUserIds", stakeholdersOf(wi, reviewers)),
                actorId);
        return Views.of(wi);
    }

    // ==================== review ====================

    @Transactional
    public Map<String, Object> review(String idOrKey, String result, String comment, UUID actorId) {
        WorkItem wi = refs.workItem(idOrKey);
        assertRequirement(wi);
        if (!WorkItem.STATUS_REQ_PENDING_REVIEW.equals(wi.getStatus())) {
            throw new BusinessException(ErrorCode.PRD_4201,
                    "仅 pending_review 可评审，当前 " + wi.getStatus());
        }
        if (!RequirementReview.RESULT_APPROVED.equals(result)
                && !RequirementReview.RESULT_REJECTED.equals(result)) {
            throw new BusinessException(ErrorCode.PLT_4000, "result 非法: " + result);
        }
        int round = currentRound(wi.getId());
        RequirementReview row = reviews
                .findByRequirementIdAndRoundAndReviewerId(wi.getId(), round, actorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4030, "非本轮评审人"));
        if (!RequirementReview.RESULT_PENDING.equals(row.getResult())) {
            throw new BusinessException(ErrorCode.PLT_4000, "本轮已评审过");
        }
        row.setResult(result);
        row.setComment(comment);
        row.setDecidedAt(Instant.now());

        // 会签判定：任一 rejected → draft（行保留记录）；全员 approved（无 pending）→ accepted
        List<RequirementReview> rows = reviews.findByRequirementIdAndRound(wi.getId(), round);
        boolean anyRejected = rows.stream()
                .anyMatch(r -> RequirementReview.RESULT_REJECTED.equals(r.getResult()));
        boolean allDecided = rows.stream()
                .noneMatch(r -> RequirementReview.RESULT_PENDING.equals(r.getResult()));
        if (anyRejected) {
            wi.setStatus(WorkItem.STATUS_REQ_DRAFT);
        } else if (allDecided) {
            wi.setStatus(WorkItem.STATUS_REQ_ACCEPTED);
        }
        // R-6：轮次出结论（任一驳回回 draft / 全员通过受理）→ 回写 concluded_at（纪要轮次卡展示用）
        if (anyRejected || allDecided) {
            ReviewRound rr = upsertRound(wi.getId(), round);
            if (rr.getConcludedAt() == null) {
                rr.setConcludedAt(Instant.now());
                rounds.save(rr);
            }
        }

        outbox.append("work_item", wi.getId(), "requirement.review_result",
                Map.of("requirementId", wi.getId(), "key", wi.getKey(), "round", round,
                        "reviewerId", actorId, "result", result, "status", wi.getStatus()),
                actorId);
        return Views.of(wi);
    }

    // ==================== schedule / deliver / accept ====================

    /** accepted 态绑定 release/sprint/storyPoints（状态保持 accepted） */
    @Transactional
    public Map<String, Object> schedule(String idOrKey, String releaseId, String sprintId,
                                        BigDecimal storyPoints, UUID actorId) {
        WorkItem wi = refs.workItem(idOrKey);
        assertRequirement(wi);
        permissions.require(actorId, "product", wi.getProductId(), "edit");
        if (!WorkItem.STATUS_REQ_ACCEPTED.equals(wi.getStatus())) {
            throw new BusinessException(ErrorCode.PRD_4201, "仅 accepted 可排期，当前 " + wi.getStatus());
        }
        if (releaseId != null) {
            wi.setReleaseId(refs.release(releaseId).getId());
        }
        if (sprintId != null) {
            wi.setSprintId(refs.sprint(sprintId).getId());
        }
        if (storyPoints != null) {
            wi.setStoryPoints(storyPoints);
        }
        return Views.of(wi);
    }

    /** in_dev → delivered（转移表校验，非法 4201） */
    @Transactional
    public Map<String, Object> deliver(String idOrKey, UUID actorId) {
        return actionTransition(idOrKey, WorkItem.STATUS_REQ_DELIVERED, actorId);
    }

    /** delivered → closed */
    @Transactional
    public Map<String, Object> accept(String idOrKey, UUID actorId) {
        return actionTransition(idOrKey, WorkItem.STATUS_REQ_CLOSED, actorId);
    }

    // ==================== 查询 ====================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> reviews(String idOrKey) {
        WorkItem wi = refs.workItem(idOrKey);
        assertRequirement(wi);
        return reviews.findByRequirementIdOrderByRoundAscIdAsc(wi.getId())
                .stream().map(Views::of).toList();
    }

    /**
     * 评审轮次列表（R-6）：对存在评审记录的轮次自动补 review_round 行（含 V18 前的历史轮次），
     * 行结构 {round, conclusion, concludedAt, minutesFileId, summary, createdAt, reviews:[逐人记录]}。
    /**
     * 轮次行 upsert（QA 复审 MUST-FIX 并发兜底）：查无则插，撞 V18 UNIQUE(requirement_id, round)
     * 即重查返回——submit/review/reviewRounds 三处并发到达不再 500。
     */
    private ReviewRound upsertRound(UUID requirementId, int round) {
        ReviewRound existing = rounds.findByRequirementIdAndRound(requirementId, round).orElse(null);
        if (existing != null) {
            return existing;
        }
        try {
            ReviewRound n = new ReviewRound();
            n.setRequirementId(requirementId);
            n.setRound(round);
            return rounds.saveAndFlush(n);
        } catch (DataIntegrityViolationException e) {
            return rounds.findByRequirementIdAndRound(requirementId, round).orElseThrow(() -> e);
        }
    }

    /**
     * conclusion = round 内会签聚合：任一 rejected→rejected / 无 pending→approved / 其余 pending。
     */
    @Transactional
    public List<Map<String, Object>> reviewRounds(String idOrKey) {
        WorkItem wi = refs.workItem(idOrKey);
        assertRequirement(wi);
        // 逐人记录按轮分组（TreeMap 保证 round 升序）
        Map<Integer, List<RequirementReview>> byRound = new TreeMap<>();
        for (RequirementReview row : reviews.findByRequirementIdOrderByRoundAscIdAsc(wi.getId())) {
            byRound.computeIfAbsent(row.getRound(), k -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> res = new ArrayList<>();
        for (Map.Entry<Integer, List<RequirementReview>> e : byRound.entrySet()) {
            int round = e.getKey();
            // 稀疏随行补齐：该轮有评审记录但无 review_round 行时自动补（历史轮次兼容）
            ReviewRound rr = upsertRound(wi.getId(), round);
            List<RequirementReview> rows = e.getValue();
            boolean anyRejected = rows.stream()
                    .anyMatch(r -> RequirementReview.RESULT_REJECTED.equals(r.getResult()));
            boolean allDecided = rows.stream()
                    .noneMatch(r -> RequirementReview.RESULT_PENDING.equals(r.getResult()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("round", round);
            m.put("conclusion", anyRejected ? "rejected" : allDecided ? "approved" : "pending");
            m.put("concludedAt", rr.getConcludedAt());
            m.put("minutesFileId", rr.getMinutesFileId());
            m.put("summary", rr.getSummary());
            m.put("createdAt", rr.getCreatedAt());
            m.put("reviews", rows.stream().map(Views::of).toList());
            res.add(m);
        }
        return res;
    }

    // ==================== 评审纪要（R-6/D2：纪要可选，权限=提交人或管理员） ====================

    /** 上传/更换某轮纪要（fileId 走 platform.file 两步制，须 ready；summary 可选随传） */
    @Transactional
    public Map<String, Object> saveMinutes(String idOrKey, int round, String fileId,
                                           String summary, UUID actorId) {
        WorkItem wi = refs.workItem(idOrKey);
        assertRequirement(wi);
        assertMinutesManageable(wi, actorId);
        ReviewRound rr = requireRound(wi.getId(), round);
        rr.setMinutesFileId(parseReadyFileId(fileId));
        if (summary != null) {
            rr.setSummary(summary.isBlank() ? null : summary);
        }
        return roundView(rr, round);
    }

    /** 仅更新纪要摘要/换文件（PUT；fileId 不传=保持不变） */
    @Transactional
    public Map<String, Object> updateMinutes(String idOrKey, int round, String fileId,
                                             String summary, UUID actorId) {
        WorkItem wi = refs.workItem(idOrKey);
        assertRequirement(wi);
        assertMinutesManageable(wi, actorId);
        ReviewRound rr = requireRound(wi.getId(), round);
        if (fileId != null) {
            rr.setMinutesFileId(parseReadyFileId(fileId));
        }
        if (summary != null) {
            rr.setSummary(summary.isBlank() ? null : summary);
        }
        return roundView(rr, round);
    }

    // ==================== 内部 ====================

    /** 需求动作流转（deliver/accept）：转移表校验 + 同事务 outbox(requirement.status_changed) */
    private Map<String, Object> actionTransition(String idOrKey, String toStatus, UUID actorId) {
        WorkItem wi = refs.workItem(idOrKey);
        assertRequirement(wi);
        permissions.require(actorId, "product", wi.getProductId(), "edit");
        TransitionTable.check(wi.getType(), wi.getStatus(), toStatus);
        String from = wi.getStatus();
        wi.setStatus(toStatus);
        // payload 补全（W3 权威目录 §5.1）：+productId（collab target_closed 延迟归档定位对象用）
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", wi.getId());
        payload.put("key", wi.getKey());
        payload.put("type", wi.getType());
        payload.put("from", from);
        payload.put("to", toStatus);
        payload.put("productId", wi.getProductId());
        payload.put("actorId", actorId);
        outbox.append("work_item", wi.getId(), "requirement.status_changed", payload, actorId);
        return Views.of(wi);
    }

    private int currentRound(UUID requirementId) {
        Optional<RequirementReview> latest =
                reviews.findFirstByRequirementIdOrderByRoundDesc(requirementId);
        return latest.map(RequirementReview::getRound).orElse(0);
    }

    /** 定位轮次行：必须已有该轮（submit 已建行/GET 已补行），无则 404 */
    private ReviewRound requireRound(UUID requirementId, int round) {
        return rounds.findByRequirementIdAndRound(requirementId, round)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040,
                        "评审轮次不存在: round=" + round));
    }

    /** 纪要文件校验：platform.file 两步制行必须存在且 ready（对齐 INC-2 红线 4：未 ready 不得被引用） */
    private UUID parseReadyFileId(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "fileId 必填（先 POST /files 两步制上传）");
        }
        UUID id;
        try {
            id = UUID.fromString(fileId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PLT_4000, "fileId 非法: " + fileId);
        }
        var file = files.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "文件不存在: " + fileId));
        if (!FileObject.STATUS_READY.equals(file.getStatus())) {
            throw new BusinessException(ErrorCode.PLT_4000, "文件未完成上传（status!="
                    + FileObject.STATUS_READY + "）: " + fileId);
        }
        return id;
    }

    /** 纪要管理权限：需求提交人（reporter）或平台管理员（OWNER/ADMIN 短路，platform:manage 同族） */
    private void assertMinutesManageable(WorkItem wi, UUID actorId) {
        boolean reporter = actorId.equals(wi.getReporterId());
        boolean admin = permissions.check(actorId, "platform",
                PermissionService.PLATFORM_RESOURCE_ID, "platform:manage");
        if (!reporter && !admin) {
            throw new PermissionDeniedException(ErrorCode.PLT_4030,
                    "仅提交人或管理员可管理评审纪要", List.of("workItem=" + wi.getKey()));
        }
    }

    /** 轮次行 REST 投影（纪要写端点返回；逐人记录另经 GET review-rounds 获取） */
    private Map<String, Object> roundView(ReviewRound rr, int round) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("round", round);
        m.put("minutesFileId", rr.getMinutesFileId());
        m.put("summary", rr.getSummary());
        m.put("concludedAt", rr.getConcludedAt());
        return m;
    }

    /**
     * 需求干系人并集（去重保序；05 §6.2 stakeholder_rule 的 prd 侧数据源）：
     * 需求 owner(reporter)+本轮评审人+产品 owner+目标 owner（product/goal owner 取 prd 自己的实体）。
     */
    private List<UUID> stakeholdersOf(WorkItem wi, List<UUID> reviewers) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        if (wi.getReporterId() != null) {
            ids.add(wi.getReporterId());
        }
        ids.addAll(reviewers);
        if (wi.getProductId() != null) {
            UUID productOwner = refs.product(wi.getProductId().toString()).getOwnerId();
            if (productOwner != null) {
                ids.add(productOwner);
            }
        }
        if (wi.getGoalId() != null) {
            UUID goalOwner = refs.goal(wi.getGoalId().toString()).getOwnerId();
            if (goalOwner != null) {
                ids.add(goalOwner);
            }
        }
        return List.copyOf(ids);
    }

    private void assertRequirement(WorkItem wi) {
        if (!WorkItem.TYPE_REQUIREMENT.equals(wi.getType())) {
            throw new BusinessException(ErrorCode.PLT_4000, "非需求工作项: " + wi.getKey());
        }
    }
}
