package cn.teamone.prd.app;

import cn.teamone.prd.domain.Product;
import cn.teamone.prd.domain.Release;
import cn.teamone.prd.repo.BlockingDefectView;
import cn.teamone.prd.repo.ProductRepository;
import cn.teamone.prd.repo.ReleaseRepository;
import cn.teamone.prd.repo.WorkItemRepository;
import cn.teamone.platform.infra.OutboxWriter;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 发布门禁重算（05 §6.1）：blocked / blocked_defect_ids / status 联动。
 *
 * <p><b>红线条 1</b>：本方法是 release.blocked 与 blockedDefectIds 的<b>唯一写入口</b>；
 * 真相在 {@link WorkItemRepository#findOpenBlockingDefects} 查询，数组只是投影。
 * 在调用方事务内执行（REQUIRED），与状态变更/Outbox 同生共死。</p>
 *
 * <pre>
 * n = countOpenBlockingDefects(releaseId)
 * release.blocked = n>0; blockedDefectIds = 清单 ids
 * n&gt;0 且 status∈(planned,coding,code_freeze) → blocked
 *   （M-N1：code_freeze 冻结后新挂致命/严重缺陷是门禁主场景——若不回 blocked，
 *     blocked=true + status=code_freeze 直接违反 ck_release_blocked_status → 500 回滚）
 * n==0 且 status==blocked → code_freeze
 * blocked 翻转 → outbox(defect.blocked_changed{releaseId, blocked, count})
 * </pre>
 */
@Service
public class GateService {

    private final ReleaseRepository releases;
    private final WorkItemRepository workItems;
    private final ProductRepository products;
    private final OutboxWriter outbox;
    private final EntityManager em;

    public GateService(ReleaseRepository releases, WorkItemRepository workItems,
                       ProductRepository products, OutboxWriter outbox, EntityManager em) {
        this.releases = releases;
        this.workItems = workItems;
        this.products = products;
        this.outbox = outbox;
        this.em = em;
    }

    /**
     * 重算指定版本的门禁状态。必须在业务事务内调用（创建/编辑/转移三处复用）；
     * released 版本不再重算（已定版，禁止回改）。
     */
    @Transactional // REQUIRED：加入调用方事务
    public void recalcGate(UUID releaseId, UUID actorId) {
        // 原生清点查询绕过持久化上下文——先把本事务的实体变更落到库，查询才见真相
        em.flush();

        Release release = releases.findForUpdate(releaseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "版本不存在: " + releaseId));
        if (Release.STATUS_RELEASED.equals(release.getStatus())) {
            return; // 已发布版本门禁封板
        }

        List<BlockingDefectView> open = workItems.findOpenBlockingDefects(releaseId);
        boolean blocked = !open.isEmpty();
        boolean wasBlocked = release.isBlocked();

        release.setBlocked(blocked);
        release.setBlockedDefectIds(open.stream().map(BlockingDefectView::getId).toList());
        String status = release.getStatus();
        if (blocked && (Release.STATUS_PLANNED.equals(status) || Release.STATUS_CODING.equals(status)
                || Release.STATUS_CODE_FREEZE.equals(status))) {
            // M-N1：planned/coding/code_freeze 三态均可进入 blocked（CHECK 只允许 blocked 标志
            // 配 coding/blocked 两态，code_freeze 挂新致命缺陷必须回 blocked，否则 500 回滚）
            release.setStatus(Release.STATUS_BLOCKED);
        } else if (!blocked && Release.STATUS_BLOCKED.equals(status)) {
            release.setStatus(Release.STATUS_CODE_FREEZE);
        }

        if (blocked != wasBlocked) {
            // payload 补全（W3 权威目录 §5.1）：+productId、+productOwnerId
            // （collab 通知发布经理用；Map.of 不容 null，改 LinkedHashMap）
            UUID productOwnerId = products.findById(release.getProductId())
                    .map(Product::getOwnerId).orElse(null);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("releaseId", releaseId);
            payload.put("blocked", blocked);
            payload.put("count", open.size());
            payload.put("productId", release.getProductId());
            payload.put("productOwnerId", productOwnerId);
            outbox.append("release", releaseId, "defect.blocked_changed", payload, actorId);
        }
    }
}
