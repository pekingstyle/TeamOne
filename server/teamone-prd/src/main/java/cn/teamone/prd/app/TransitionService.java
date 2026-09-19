package cn.teamone.prd.app;

import cn.teamone.prd.domain.WorkItem;
import cn.teamone.prd.domain.transition.TransitionTable;
import cn.teamone.prd.repo.WorkItemRepository;
import cn.teamone.platform.authz.PermissionService;
import cn.teamone.platform.infra.OutboxWriter;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 状态机应用服务（05 §6.1）：transition 接口唯一入口。
 *
 * <p>同一事务内完成：查实体 → 转移表校验（非法抛 {@link ErrorCode#PRD_4201}，422）
 * → 应用状态 → L1 副作用（defect 挂阻塞版本时 {@link GateService#recalcGate}）
 * → 写 Outbox（workitem.status_changed，L2 广播）。</p>
 */
@Service
public class TransitionService {

    private final WorkItemRepository workItems;
    private final GateService gateService;
    private final PermissionService permissions;
    private final OutboxWriter outbox;

    public TransitionService(WorkItemRepository workItems, GateService gateService,
                             PermissionService permissions, OutboxWriter outbox) {
        this.workItems = workItems;
        this.gateService = gateService;
        this.permissions = permissions;
        this.outbox = outbox;
    }

    /**
     * 状态流转（动态资源鉴权：product edit）。
     *
     * @param workItemId 工作项 id
     * @param toStatus   目标状态（与 CHECK 字节级一致）
     * @param actorId    操作人
     */
    @Transactional
    public WorkItem transition(UUID workItemId, String toStatus, UUID actorId) {
        WorkItem wi = workItems.findById(workItemId).orElseThrow(() ->
                new BusinessException(ErrorCode.PLT_4040, "工作项不存在: " + workItemId));
        permissions.require(actorId, "product", wi.getProductId(), "edit");

        TransitionTable.check(wi.getType(), wi.getStatus(), toStatus);
        String from = wi.getStatus();
        wi.setStatus(toStatus);

        // L1 副作用：defect 阻塞版本 → 同事务重算门禁（recalcGate 内部先 flush，原生清点见真相）
        if (WorkItem.TYPE_DEFECT.equals(wi.getType()) && wi.getBlockedReleaseId() != null) {
            gateService.recalcGate(wi.getBlockedReleaseId(), actorId);
        }

        // payload 补全（W3 权威目录 §5.1）：+productId、defect 时 +blockedReleaseId
        // （collab 按 payload.type=defect 且 to=已关闭 做延迟归档；Map.of 不容 null，改 LinkedHashMap）
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", wi.getId());
        payload.put("key", wi.getKey());
        payload.put("type", wi.getType());
        payload.put("from", from);
        payload.put("to", toStatus);
        payload.put("productId", wi.getProductId());
        if (WorkItem.TYPE_DEFECT.equals(wi.getType())) {
            payload.put("blockedReleaseId", wi.getBlockedReleaseId());
        }
        payload.put("actorId", actorId);
        outbox.append("work_item", wi.getId(), "workitem.status_changed", payload, actorId);
        return wi;
    }
}
