package cn.teamone.prd;

import cn.teamone.prd.app.ProjectService;
import cn.teamone.prd.app.Refs;
import cn.teamone.prd.domain.Component;
import cn.teamone.prd.domain.Project;
import cn.teamone.prd.domain.WorkItem;
import cn.teamone.prd.repo.ProductRepository;
import cn.teamone.prd.repo.ProjectRepository;
import cn.teamone.prd.repo.WorkItemRepository;
import cn.teamone.platform.authz.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 升级冲突预警单测（⑥p · docs/v2/15 §9）：客制组件集 ∩ 产品演进组件集，
 * 红=in_dev 撞线；未挂组件的客制需求不参与计算。
 *
 * @author Ivan Yang, 2026-09-28
 */
class ProjectUpgradeWarningTest {

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final WorkItemRepository workItems = mock(WorkItemRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final Refs refs = mock(Refs.class);

    private final ProjectService service = new ProjectService(projects, workItems, products, permissions, refs);

    @Test
    void upgradeWarnings_intersectionWithSeverityAndKeys() {
        UUID productId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID compA = UUID.randomUUID(); // 客制 ∧ 产品 in_dev → red
        UUID compB = UUID.randomUUID(); // 客制 ∧ 产品 delivered → yellow
        UUID compC = UUID.randomUUID(); // 仅客制 → 不预警

        Project p = project(projectId, productId);
        p.setStartDate(LocalDate.now().minusDays(30));
        when(refs.project(projectId.toString())).thenReturn(p);
        when(refs.component(compA.toString())).thenReturn(component(compA, "流水线引擎"));
        when(refs.component(compB.toString())).thenReturn(component(compB, "协同服务"));

        // 客制集：compA（REQ-C1）、compB（REQ-C2）、compC（REQ-C3 不预警）、无组件一条（不参与）
        when(workItems.findBySourceProjectIdAndOriginOrderByCreatedAtDesc(projectId, "project_custom"))
                .thenReturn(List.of(
                        req("REQ-C1", "project_custom", "in_dev", compA),
                        req("REQ-C2", "project_custom", "draft", compB),
                        req("REQ-C3", "project_custom", "draft", compC),
                        req("REQ-C4", "project_custom", "draft", null)));
        // 演进集（⑥q 投影）：compA in_dev（红）、compB delivered（黄）
        when(workItems.findEvolutionSince(any(UUID.class), any(Instant.class)))
                .thenReturn(List.of(
                        evo(compA, "REQ-P1", "in_dev"),
                        evo(compB, "REQ-P2", "delivered")));

        Map<String, Object> res = service.upgradeWarnings(projectId.toString());

        assertEquals(1, res.get("redCount"));
        assertEquals(1, res.get("yellowCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) res.get("items");
        assertEquals(2, items.size(), "compC 仅客制不预警、无组件不参与");
        Map<String, Object> first = items.get(0);
        assertEquals("流水线引擎", first.get("componentName"));
        assertEquals("red", first.get("severity"), "产品 in_dev 撞线 → red");
        assertEquals(List.of("REQ-C1"), first.get("customReqKeys"));
        assertEquals(List.of("REQ-P1"), first.get("productReqKeys"));
        assertEquals("yellow", items.get(1).get("severity"), "产品 delivered 已动过 → yellow");
    }

    @Test
    void upgradeWarnings_noOverlapEmpty() {
        UUID projectId = UUID.randomUUID();
        Project p = project(projectId, UUID.randomUUID());
        when(refs.project(projectId.toString())).thenReturn(p);
        when(workItems.findBySourceProjectIdAndOriginOrderByCreatedAtDesc(projectId, "project_custom"))
                .thenReturn(List.of(req("REQ-C1", "project_custom", "in_dev", UUID.randomUUID())));
        when(workItems.findEvolutionSince(any(UUID.class), any(Instant.class)))
                .thenReturn(List.of());

        Map<String, Object> res = service.upgradeWarnings(projectId.toString());
        assertEquals(0, res.get("redCount"));
        assertEquals(0, res.get("yellowCount"));
        assertEquals(List.of(), res.get("items"));
        assertNotNull(res.get("baselineDate"), "基线日期恒输出（startDate 缺省回退 createdAt）");
    }

    private Project project(UUID id, UUID productId) {
        Project p = new Project();
        ReflectionTestUtils.setField(p, "id", id);
        p.setName("A 集团协同平台实施");
        p.setStatus(Project.STATUS_DELIVERING);
        p.setProductId(productId);
        return p;
    }

    private Component component(UUID id, String name) {
        Component c = new Component();
        ReflectionTestUtils.setField(c, "id", id);
        c.setName(name);
        return c;
    }

    private WorkItemRepository.WorkItemEvolutionView evo(UUID componentId, String key, String status) {
        return new WorkItemRepository.WorkItemEvolutionView() {
            @Override public UUID getComponentId() { return componentId; }
            @Override public String getReqKey() { return key; }
            @Override public String getStatus() { return status; }
        };
    }

    private WorkItem req(String key, String origin, String status, UUID componentId) {
        WorkItem w = new WorkItem();
        w.setKey(key);
        w.setType("requirement");
        w.setOrigin(origin);
        w.setStatus(status);
        w.setComponentId(componentId);
        return w;
    }
}
