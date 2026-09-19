package cn.teamone.prd.api;

import cn.teamone.prd.app.Refs;
import cn.teamone.prd.app.RequirementService;
import cn.teamone.prd.app.TransitionService;
import cn.teamone.prd.app.Views;
import cn.teamone.prd.app.WorkItemService;
import cn.teamone.platform.infra.IdempotencyService;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 工作项端点（05 §3.3 /api/v1/work-items）。
 *
 * <p>鉴权：写端点在 service 内做动态资源鉴权（PermissionService.require product edit，
 * 总监已批准方式）；读端点要求登录（JWT 过滤器）。</p>
 */
@RestController
@RequestMapping("/api/v1/work-items")
public class WorkItemController {

    private final WorkItemService workItems;
    private final RequirementService requirements;
    private final TransitionService transitions;
    private final IdempotencyService idempotency;
    private final Refs refs;
    private final ObjectMapper om;

    public WorkItemController(WorkItemService workItems, RequirementService requirements,
                              TransitionService transitions, IdempotencyService idempotency,
                              Refs refs, ObjectMapper om) {
        this.workItems = workItems;
        this.requirements = requirements;
        this.transitions = transitions;
        this.idempotency = idempotency;
        this.refs = refs;
        this.om = om;
    }

    // ==================== CRUD ====================

    /** 创建（Idempotency-Key 支持同 Key 回放首个快照） */
    @PostMapping
    public ResponseEntity<Object> create(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID actor = Actor.require();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyService.StoredResponse> stored = idempotency.find(idempotencyKey);
            if (stored.isPresent()) {
                return ResponseEntity.status(stored.get().status())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(readTree(stored.get().bodyJson()));
            }
        }
        Map<String, Object> view = workItems.create(
                om.convertValue(body, WorkItemService.CreateSpec.class), actor, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** 列表：type/status/assignee/sprintId/q 过滤 + page/size 分页（page 从 1 起） */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String sprintId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Actor.require();
        return workItems.list(type, status, assignee,
                sprintId == null || sprintId.isBlank() ? null : refs.sprint(sprintId).getId(),
                q, page, size);
    }

    @GetMapping("/{idOrKey}")
    public Map<String, Object> get(@PathVariable String idOrKey) {
        Actor.require();
        return workItems.get(idOrKey);
    }

    /** 更新（If-Match: <version>，不匹配 409 且 details 带最新 version） */
    @PutMapping("/{idOrKey}")
    public Map<String, Object> update(@PathVariable String idOrKey,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return workItems.update(idOrKey,
                om.convertValue(body, WorkItemService.UpdateSpec.class), parseVersion(ifMatch),
                Actor.require());
    }

    // ==================== 状态机（唯一入口） ====================

    /** 状态流转（四类型通用；requirement 手动 in_dev 亦走此口） */
    @PostMapping("/{idOrKey}/transition")
    public Map<String, Object> transition(@PathVariable String idOrKey,
            @RequestBody Map<String, Object> body) {
        Object to = body.get("to");
        if (!(to instanceof String toStatus) || toStatus.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "to 必填");
        }
        UUID workItemId = refs.workItem(idOrKey).getId();
        return Views.of(transitions.transition(workItemId, toStatus, Actor.require()));
    }

    // ==================== 需求评审流 ====================

    @PostMapping("/{idOrKey}/submit")
    @SuppressWarnings("unchecked")
    public Map<String, Object> submit(@PathVariable String idOrKey,
            @RequestBody Map<String, Object> body) {
        Object raw = body.get("reviewerIds");
        if (!(raw instanceof List)) {
            throw new BusinessException(ErrorCode.PLT_4000, "reviewerIds 必填");
        }
        return requirements.submit(idOrKey, ((List<Object>) raw).stream()
                .map(String::valueOf).toList(), Actor.require());
    }

    @PostMapping("/{idOrKey}/review")
    public Map<String, Object> review(@PathVariable String idOrKey,
            @RequestBody Map<String, Object> body) {
        Object result = body.get("result");
        if (!(result instanceof String r) || r.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "result 必填");
        }
        Object comment = body.get("comment");
        return requirements.review(idOrKey, r, comment == null ? null : String.valueOf(comment),
                Actor.require());
    }

    @PostMapping("/{idOrKey}/schedule")
    public Map<String, Object> schedule(@PathVariable String idOrKey,
            @RequestBody Map<String, Object> body) {
        return requirements.schedule(idOrKey, str(body, "releaseId"), str(body, "sprintId"),
                body.get("storyPoints") == null ? null
                        : om.convertValue(body.get("storyPoints"), java.math.BigDecimal.class),
                Actor.require());
    }

    @PostMapping("/{idOrKey}/deliver")
    public Map<String, Object> deliver(@PathVariable String idOrKey) {
        return requirements.deliver(idOrKey, Actor.require());
    }

    @PostMapping("/{idOrKey}/accept")
    public Map<String, Object> accept(@PathVariable String idOrKey) {
        return requirements.accept(idOrKey, Actor.require());
    }

    @GetMapping("/{idOrKey}/reviews")
    public List<Map<String, Object>> reviews(@PathVariable String idOrKey) {
        Actor.require();
        return requirements.reviews(idOrKey);
    }

    // ==================== 评审轮次纪要（R-6/D2，B4 批） ====================

    /** 轮次列表：对存在评审记录的轮次自动补行；行含 round/conclusion/纪要引用/逐人记录 */
    @GetMapping("/{idOrKey}/review-rounds")
    public List<Map<String, Object>> reviewRounds(@PathVariable String idOrKey) {
        Actor.require();
        return requirements.reviewRounds(idOrKey);
    }

    /** 上传/更换某轮纪要：body {"fileId":"<platform.file id>","summary":"可选"}；权限=提交人或管理员 */
    @PostMapping("/{idOrKey}/review-rounds/{round}/minutes")
    public Map<String, Object> uploadMinutes(@PathVariable String idOrKey,
            @PathVariable int round, @RequestBody Map<String, Object> body) {
        return requirements.saveMinutes(idOrKey, round, str(body, "fileId"),
                strOrNull(body, "summary"), Actor.require());
    }

    /** 更新纪要：body {"fileId":"可选（不传保持不变）","summary":"可选"}；权限=提交人或管理员 */
    @PutMapping("/{idOrKey}/review-rounds/{round}/minutes")
    public Map<String, Object> updateMinutes(@PathVariable String idOrKey,
            @PathVariable int round, @RequestBody Map<String, Object> body) {
        return requirements.updateMinutes(idOrKey, round,
                body.get("fileId") == null ? null : str(body, "fileId"),
                body.get("summary") == null ? null : str(body, "summary"), Actor.require());
    }

    // ==================== 内部 ====================

    private Object readTree(String json) {
        try {
            return om.readValue(json, Object.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SRV_5000, "幂等快照损坏");
        }
    }

    private Integer parseVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(ifMatch.replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.PLT_4000, "If-Match 版本头非法: " + ifMatch);
        }
    }

    private static String str(Map<String, Object> body, String field) {
        Object v = body.get(field);
        return v == null ? null : String.valueOf(v);
    }

    /** 可空字符串（空串归一为 null——summary 缺省不覆盖已存值） */
    private static String strOrNull(Map<String, Object> body, String field) {
        String v = str(body, field);
        return v == null || v.isBlank() ? null : v;
    }
}
