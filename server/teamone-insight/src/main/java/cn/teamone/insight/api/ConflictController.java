package cn.teamone.insight.api;

import cn.teamone.insight.app.ConflictBatchService;
import cn.teamone.insight.app.ConflictQueryService;
import cn.teamone.platform.audit.AuditService;
import cn.teamone.shared.auth.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 冲突端点（M2-INC-1 W2，insight 域；05 §3.3 GET /conflicts?kind= 登记行的落地，
 * heatmap/recompute 为 W2 方向审查新增路径——落 insight.api，不复用 prd 控制器）。
 *
 * <p>鉴权：读端点登录态（JWT 过滤器 + /api/** authenticated 已覆盖，SecurityConfig 零改动）；
 * POST /recompute 平台级 platform:manage（@RequirePerm 四步链）+ 审计。</p>
 */
@RestController
@RequestMapping("/api/v1/conflicts")
public class ConflictController {

    private final ConflictQueryService queries;
    private final ConflictBatchService batch;
    private final AuditService audit;

    public ConflictController(ConflictQueryService queries, ConflictBatchService batch,
                              AuditService audit) {
        this.queries = queries;
        this.batch = batch;
        this.audit = audit;
    }

    /** 快照只读查询（登录态；INC-1 红线②：不实时计算） */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String kind,
            @RequestParam(required = false) String since) {
        Actor.require();
        return queries.snapshots(kind, parseSince(since));
    }

    /** 负载热力图原料矩阵（登录态；红线②：无任何 CF 判决字段） */
    @GetMapping("/heatmap")
    public Map<String, Object> heatmap(@RequestParam(defaultValue = "90") int days) {
        Actor.require();
        return queries.heatmap(days);
    }

    /** 手动全量重算（admin：平台级 platform:manage + 审计 conflict.recompute；同步执行） */
    @PostMapping("/recompute")
    @RequirePerm(resourceType = "platform", action = "platform:manage")
    public Map<String, Object> recompute() {
        UUID actor = Actor.require();
        Map<String, Object> summary = batch.recomputeAll();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("total", summary.get("total"));
        detail.put("red", summary.get("red"));
        detail.put("redNew", summary.get("redNew"));
        audit.record(actor, "conflict.recompute", "platform", null, detail);
        return summary;
    }

    // ==================== 内部 ====================

    /** since 兼容 ISO instant 与 date（date 按当日 0 点 UTC 起算；非法值宽容忽略，读端点不 400） */
    private Instant parseSince(String since) {
        if (since == null || since.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(since);
        } catch (DateTimeParseException ignored) {
            // fall through：date 口径
        }
        try {
            return LocalDate.parse(since).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
