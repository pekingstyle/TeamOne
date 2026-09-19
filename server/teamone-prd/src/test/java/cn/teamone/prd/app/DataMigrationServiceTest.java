package cn.teamone.prd.app;

import cn.teamone.platform.audit.AuditService;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.prd.domain.Product;
import cn.teamone.prd.domain.Sprint;
import cn.teamone.prd.domain.WorkItem;
import cn.teamone.prd.dto.MigrationDto.ImportResultDto;
import cn.teamone.prd.dto.MigrationDto.ValidationReportDto;
import cn.teamone.prd.repo.ProductRepository;
import cn.teamone.prd.repo.SprintRepository;
import cn.teamone.prd.repo.WorkItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 数据迁移与质量门禁应用服务单元测试（P0 企业治理底座 · 覆盖模板生成、外键完整性校验、枚举规约与导入落库）。
 */
class DataMigrationServiceTest {

    private ProductRepository productRepo;
    private SprintRepository sprintRepo;
    private AppUserRepository userRepo;
    private WorkItemRepository workItemRepo;
    private KeySequenceService sequences;
    private AuditService audit;
    private DataMigrationService service;

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID SPRINT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OPERATOR_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        productRepo = mock(ProductRepository.class);
        sprintRepo = mock(SprintRepository.class);
        userRepo = mock(AppUserRepository.class);
        workItemRepo = mock(WorkItemRepository.class);
        sequences = mock(KeySequenceService.class);
        audit = mock(AuditService.class);

        service = new DataMigrationService(
                productRepo, sprintRepo, userRepo, workItemRepo, sequences, audit
        );

        Product product = mock(Product.class);
        when(product.getId()).thenReturn(PRODUCT_ID);
        when(product.getName()).thenReturn("TeamOne Core");
        when(product.getKey()).thenReturn("TEAMONE");

        when(productRepo.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        Sprint sprint = mock(Sprint.class);
        when(sprint.getId()).thenReturn(SPRINT_ID);
        when(sprint.getProductId()).thenReturn(PRODUCT_ID);
        when(sprint.getName()).thenReturn("Sprint 1");
        when(sprintRepo.findByProductIdOrderByStartDateAsc(PRODUCT_ID)).thenReturn(List.of(sprint));

        AppUser admin = new AppUser();
        admin.setUsername("admin");
        admin.setStatus(AppUser.Status.ACTIVE);
        when(userRepo.findByUsername("admin")).thenReturn(Optional.of(admin));

        when(sequences.nextKey(any())).thenReturn("T-101");
        when(workItemRepo.save(any(WorkItem.class))).thenAnswer(inv -> {
            WorkItem wi = inv.getArgument(0);
            return wi;
        });
    }

    /**
     * 测试标准模板生成：必须包含 UTF-8 BOM，具备标准表头与示例行。
     */
    @Test
    void testGenerateCsvTemplate() {
        byte[] bytes = service.generateCsvTemplate();
        assertNotNull(bytes);
        assertTrue(bytes.length > 50);

        String content = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(content.contains("工作项类型*"));
        assertTrue(content.contains("经办人用户名"));
        assertTrue(content.contains("所属迭代"));
    }

    /**
     * 正向测试：标准合规数据导入预检通过，0 错误行，canImport=true。
     */
    @Test
    void testValidateValidCsv() {
        String csv = "工作项类型*,标题*,优先级*,初始状态,经办人用户名,所属迭代,故事点,预估工时,缺陷严重度,描述\n" +
                "需求,统一单点登录,P1,草稿,admin,Sprint 1,5.0,20,,\"对接内部 CAS 认证\"\n" +
                "任务,编写单测用例,P2,待处理,admin,Sprint 1,2.0,8,,\"覆盖主流程分支\"\n";

        ValidationReportDto report = service.validateWorkItemImport(PRODUCT_ID, csv, "test.csv");

        assertEquals(2, report.totalRows());
        assertEquals(2, report.validRows());
        assertEquals(0, report.errorRows());
        assertTrue(report.canImport());
        assertEquals(0, report.errors().size());
        assertEquals(2, report.previewRows().size());
    }

    /**
     * 质量门禁外键拦截：经办人用户名不存在时，拦截报错并输出精准行号与列名。
     */
    @Test
    void testValidateInvalidAssigneeThrowsError() {
        when(userRepo.findByUsername("nonexistent_user")).thenReturn(Optional.empty());

        String csv = "工作项类型*,标题*,优先级*,初始状态,经办人用户名,所属迭代,故事点,预估工时,缺陷严重度,描述\n" +
                "任务,神秘任务,P2,待处理,nonexistent_user,Sprint 1,2.0,8,,\"无经办人\"\n";

        ValidationReportDto report = service.validateWorkItemImport(PRODUCT_ID, csv, "test.csv");

        assertEquals(1, report.totalRows());
        assertEquals(0, report.validRows());
        assertEquals(1, report.errorRows());
        assertFalse(report.canImport());
        assertEquals(1, report.errors().size());
        assertEquals("经办人用户名", report.errors().get(0).columnName());
        assertTrue(report.errors().get(0).message().contains("在系统用户中不存在"));
    }

    /**
     * 质量门禁外键拦截：迭代不在当前产品中时，拦截报错。
     */
    @Test
    void testValidateInvalidSprintThrowsError() {
        String csv = "工作项类型*,标题*,优先级*,初始状态,经办人用户名,所属迭代,故事点,预估工时,缺陷严重度,描述\n" +
                "任务,越界任务,P2,待处理,admin,Sprint 999,2.0,8,,\"不存在的迭代\"\n";

        ValidationReportDto report = service.validateWorkItemImport(PRODUCT_ID, csv, "test.csv");

        assertEquals(1, report.totalRows());
        assertEquals(1, report.errorRows());
        assertFalse(report.canImport());
        assertTrue(report.errors().get(0).message().contains("在当前产品下不存在"));
    }

    /**
     * 质量门禁枚举拦截：优先级非法时（如写入 "Urgent"），拦截报错。
     */
    @Test
    void testValidateInvalidPriorityThrowsError() {
        String csv = "工作项类型*,标题*,优先级*,初始状态,经办人用户名,所属迭代,故事点,预估工时,缺陷严重度,描述\n" +
                "任务,加急任务,Urgent,待处理,admin,Sprint 1,2.0,8,,\"非法优先级\"\n";

        ValidationReportDto report = service.validateWorkItemImport(PRODUCT_ID, csv, "test.csv");

        assertEquals(1, report.errorRows());
        assertFalse(report.canImport());
        assertTrue(report.errors().get(0).message().contains("无效，仅支持 P0 / P1 / P2 / P3"));
    }

    /**
     * 正向导入测试：执行合规数据落库，生成工作项业务键并记录审计。
     */
    @Test
    void testExecuteImportSuccess() {
        String csv = "工作项类型*,标题*,优先级*,初始状态,经办人用户名,所属迭代,故事点,预估工时,缺陷严重度,描述\n" +
                "任务,任务 A,P1,待处理,admin,Sprint 1,3.0,10,,\"测试任务 A\"\n";

        ValidationReportDto report = service.validateWorkItemImport(PRODUCT_ID, csv, "test.csv");
        assertTrue(report.canImport());

        ImportResultDto result = service.executeWorkItemImport(PRODUCT_ID, report.previewRows(), false, OPERATOR_ID);

        assertEquals(1, result.importedCount());
        assertEquals(0, result.skippedCount());
        assertEquals(1, result.createdKeys().size());
        assertEquals("T-101", result.createdKeys().get(0));

        verify(workItemRepo, atLeastOnce()).save(any(WorkItem.class));
        verify(audit).record(eq(OPERATOR_ID), eq("data_migration.import"), eq("work_item"), eq(PRODUCT_ID.toString()), any());
    }
}
