package cn.teamone.prd;

import cn.teamone.prd.app.GateService;
import cn.teamone.prd.app.KeySequenceService;
import cn.teamone.prd.app.Refs;
import cn.teamone.prd.app.WorkItemService;
import cn.teamone.prd.domain.WorkItem;
import cn.teamone.prd.domain.WorkItemLink;
import cn.teamone.prd.repo.ProjectRepository;
import cn.teamone.prd.repo.WorkItemLinkRepository;
import cn.teamone.prd.repo.WorkItemRepository;
import cn.teamone.platform.authz.PermissionService;
import cn.teamone.platform.infra.IdempotencyService;
import cn.teamone.platform.infra.OutboxWriter;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 客制化需求回流单测（⑥o · docs/v2/15 §8 V-22）：前置校验 422 × 2 + 成功链
 * （新需求 origin=product、原需求 promoted_to_id 回写、link(promoted_from) 建链）。
 *
 * @author Ivan Yang, 2026-09-28
 */
class WorkItemPromoteToProductTest {

    private final WorkItemRepository workItems = mock(WorkItemRepository.class);
    private final KeySequenceService sequences = mock(KeySequenceService.class);
    private final GateService gateService = mock(GateService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final Refs refs = mock(Refs.class);
    private final EntityManager em = mock(EntityManager.class);
    private final WorkItemLinkRepository links = mock(WorkItemLinkRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);

    private final WorkItemService service = new WorkItemService(
            workItems, sequences, gateService, permissions, idempotency, outbox, refs, em, links, projects);

    private final UUID productId = UUID.randomUUID();
    private final UUID sourceProjectId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    @Test
    void promote_nonCustomOriginRejectedWith422() {
        WorkItem source = requirement(UUID.randomUUID(), WorkItem.ORIGIN_PRODUCT, null);
        when(refs.workItem("REQ-7")).thenReturn(source);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.promoteToProduct("REQ-7", actor));
        assertEquals(ErrorCode.PRD_4256, ex.errorCode(), "非客制需求回流必须打 T1-PRD-4256（422）");
        assertEquals(422, ex.errorCode().httpStatus());
    }

    @Test
    void promote_alreadyPromotedRejectedWith422() {
        WorkItem source = requirement(UUID.randomUUID(), WorkItem.ORIGIN_PROJECT_CUSTOM,
                UUID.randomUUID());
        when(refs.workItem("REQ-7")).thenReturn(source);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.promoteToProduct("REQ-7", actor));
        assertEquals(ErrorCode.PRD_4256, ex.errorCode(), "重复回流必须打 T1-PRD-4256（幂等防重）");
        assertEquals("该客制化需求已回流", ex.getMessage());
    }

    @Test
    void promote_successChainCreatesProductRequirementWritesBackAndLinks() {
        UUID sourceId = UUID.randomUUID();
        WorkItem source = requirement(sourceId, WorkItem.ORIGIN_PROJECT_CUSTOM, null);
        when(refs.workItem("REQ-7")).thenReturn(source);
        when(sequences.nextKey("REQUIREMENT")).thenReturn("REQ-9");
        UUID targetId = UUID.randomUUID();
        when(workItems.saveAndFlush(any(WorkItem.class))).thenAnswer(inv -> {
            WorkItem saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", targetId); // JPA 落库发 id 的 mock 等价
            return saved;
        });

        Map<String, Object> view = service.promoteToProduct("REQ-7", actor);

        // 新需求：同产品 origin=product，title/description/storyPoints/优先级承接，draft 进 backlog
        assertEquals(targetId, view.get("id"));
        assertEquals("REQ-9", view.get("key"));
        assertEquals(WorkItem.TYPE_REQUIREMENT, view.get("type"));
        assertEquals(WorkItem.ORIGIN_PRODUCT, view.get("origin"), "回流目标恒为产品标准需求");
        assertNull(view.get("sourceProjectId"), "产品需求不带来源项目");
        assertNull(view.get("promotedToId"));
        assertEquals(source.getTitle(), view.get("title"));
        assertEquals(source.getDescription(), view.get("description"));
        assertEquals(source.getPriority(), view.get("priority"));
        assertEquals(0, source.getStoryPoints().compareTo((BigDecimal) view.get("storyPoints")));
        assertEquals(productId, view.get("productId"));
        assertEquals(WorkItem.STATUS_REQ_DRAFT, view.get("status"),
                "requirement 状态 CHECK 只允许 draft 起（文档口径 todo 的可落地等价）");

        // 原需求回写 promoted_to_id（非空=已回流）
        assertEquals(targetId, source.getPromotedToId());

        // 回流可追溯链：from=原客制需求，to=新产品需求，relation=promoted_from
        ArgumentCaptor<WorkItemLink> linkCaptor = ArgumentCaptor.forClass(WorkItemLink.class);
        verify(links).save(linkCaptor.capture());
        WorkItemLink link = linkCaptor.getValue();
        assertEquals(sourceId, link.getFromItemId());
        assertEquals(targetId, link.getToItemId());
        assertEquals(WorkItemLink.RELATION_PROMOTED_FROM, link.getRelation());

        // L2 广播：回流产生的新产品需求发 workitem.created（与 create 同构）
        verify(outbox).append(eq("work_item"), eq(targetId), eq("workitem.created"), any(), eq(actor));
    }

    private WorkItem requirement(UUID id, String origin, UUID promotedToId) {
        WorkItem wi = new WorkItem();
        ReflectionTestUtils.setField(wi, "id", id);
        wi.setKey("REQ-7");
        wi.setType(WorkItem.TYPE_REQUIREMENT);
        wi.setTitle("客户 A 单点登录客制");
        wi.setDescription("对接客户 A 的 LDAP 账号体系");
        wi.setStatus(WorkItem.STATUS_REQ_ACCEPTED);
        wi.setPriority(WorkItem.PRIORITY_P1);
        wi.setStoryPoints(new BigDecimal("3.5"));
        wi.setReporterId(UUID.randomUUID());
        wi.setProductId(productId);
        wi.setOrigin(origin);
        wi.setSourceProjectId(sourceProjectId);
        wi.setPromotedToId(promotedToId);
        wi.setLabels("[\"custom\"]");
        return wi;
    }
}
