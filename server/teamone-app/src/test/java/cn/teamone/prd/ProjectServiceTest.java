package cn.teamone.prd;

import cn.teamone.prd.app.ProjectService;
import cn.teamone.prd.app.Refs;
import cn.teamone.prd.domain.Product;
import cn.teamone.prd.domain.Project;
import cn.teamone.prd.repo.ProductRepository;
import cn.teamone.prd.repo.ProjectRepository;
import cn.teamone.prd.repo.WorkItemRepository;
import cn.teamone.platform.authz.PermissionService;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 项目交付应用服务单测（⑥o · docs/v2/15 §8 V-22）：建项目重名 422 + 双轮度量分子分母。
 *
 * @author Ivan Yang, 2026-09-28
 */
class ProjectServiceTest {

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final WorkItemRepository workItems = mock(WorkItemRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final Refs refs = mock(Refs.class);

    private final ProjectService service = new ProjectService(projects, workItems, products, permissions, refs);

    @Test
    void create_duplicateNameRejectedWith422() {
        UUID productId = UUID.randomUUID();
        Product product = product(productId, "TeamOne 协同平台");
        when(refs.product("p1")).thenReturn(product);
        when(projects.existsByName("A 集团协同平台实施")).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(
                new ProjectService.ProjectSpec("A 集团协同平台实施", "A 集团", null, "p1",
                        null, null, null),
                UUID.randomUUID()));
        assertEquals(ErrorCode.PRD_4257, ex.errorCode(), "重名必须打 T1-PRD-4257");
        assertEquals(422, ex.errorCode().httpStatus());
        assertEquals("项目名已存在", ex.getMessage());
    }

    @Test
    void list_metricsNumeratorAndDenominator() {
        UUID productId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        Project project = project(projectId, "A 集团协同平台实施", productId);
        // 手工数数（V-22 口径③）：产品池 3 需求，其中 1 条客制（挂本项目），该 1 条已回流
        when(projects.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of(project));
        when(products.findAllById(any())).thenReturn(List.of(product(productId, "TeamOne 协同平台")));
        when(workItems.countByProductId(productId)).thenReturn(3L);
        when(workItems.countBySourceProjectIdAndOrigin(projectId, "project_custom")).thenReturn(1L);
        when(workItems.countBySourceProjectIdAndOriginAndPromotedToIdIsNotNull(
                projectId, "project_custom")).thenReturn(1L);

        List<Map<String, Object>> rows = service.list(null, null);

        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("TeamOne 协同平台", row.get("productName"));
        assertEquals(1L, row.get("customTotal"), "客制化率分子：项目客制工作项数");
        assertEquals(1L, row.get("promotedTotal"), "回流率分子：已回流客制数");
        assertEquals(1.0 / 3.0, (double) row.get("customRate"), 1e-9,
                "客制化率分母：产品全部工作项数（3）");
        assertEquals(1.0, (double) row.get("promoteRate"), 1e-9, "回流率分母：项目客制数（1）");
    }

    @Test
    void list_zeroCustomMetricsGuardDivisionByZero() {
        UUID productId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        Project project = project(projectId, "B 集团实施", productId);
        when(projects.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of(project));
        when(products.findAllById(any())).thenReturn(List.of(product(productId, "TeamOne 协同平台")));
        when(workItems.countByProductId(productId)).thenReturn(0L);
        when(workItems.countBySourceProjectIdAndOrigin(eq(projectId), eq("project_custom"))).thenReturn(0L);
        when(workItems.countBySourceProjectIdAndOriginAndPromotedToIdIsNotNull(
                eq(projectId), eq("project_custom"))).thenReturn(0L);

        Map<String, Object> row = service.list(null, null).get(0);
        assertEquals(0.0, row.get("customRate"), "分母 0 时 rate=0（防除零）");
        assertEquals(0.0, row.get("promoteRate"), "分母 0 时 rate=0（防除零）");
    }

    private Project project(UUID id, String name, UUID productId) {
        Project p = new Project();
        ReflectionTestUtils.setField(p, "id", id);
        p.setName(name);
        p.setStatus(Project.STATUS_DELIVERING);
        p.setProductId(productId);
        return p;
    }

    private Product product(UUID id, String name) {
        Product p = new Product();
        ReflectionTestUtils.setField(p, "id", id);
        p.setKey("p1");
        p.setName(name);
        return p;
    }
}
