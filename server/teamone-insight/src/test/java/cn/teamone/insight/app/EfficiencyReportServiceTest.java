package cn.teamone.insight.app;

import cn.teamone.insight.domain.InsightSprint;
import cn.teamone.insight.domain.InsightWorkItem;
import cn.teamone.insight.dto.BurndownReportDto;
import cn.teamone.insight.dto.CfdReportDto;
import cn.teamone.insight.dto.EfficiencyReportDto;
import cn.teamone.insight.repo.InsightSprintRepository;
import cn.teamone.insight.repo.InsightWorkItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 效能统计报表服务单元测试（V-19 交付指标、燃尽与累积流图核验）。
 */
@ExtendWith(MockitoExtension.class)
class EfficiencyReportServiceTest {

    @Mock
    private InsightWorkItemRepository workItemRepo;

    @Mock
    private InsightSprintRepository sprintRepo;

    private EfficiencyReportService service;

    private final UUID productId = UUID.randomUUID();
    private final UUID sprintId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new EfficiencyReportService(workItemRepo, sprintRepo);
    }

    @Test
    @DisplayName("效能综合报表计算：交付数、周期、缺陷解决率与故事点核验")
    void testGetEfficiencyReport() {
        Instant now = Instant.now();
        Instant twoDaysAgo = now.minusSeconds(172800); // 48h = 2d

        // 构造工作项：1 个已完成 task (2d), 1 个进行中 task, 1 个已解决 defect, 1 个未解决 defect
        InsightWorkItem task1 = InsightWorkItem.of(UUID.randomUUID(), "T-1", "task", "done",
                productId, sprintId, BigDecimal.valueOf(5), null, twoDaysAgo, now);
        InsightWorkItem task2 = InsightWorkItem.of(UUID.randomUUID(), "T-2", "task", "in_progress",
                productId, sprintId, BigDecimal.valueOf(3), null, twoDaysAgo, now);
        InsightWorkItem defect1 = InsightWorkItem.of(UUID.randomUUID(), "D-1", "defect", "已修复",
                productId, sprintId, BigDecimal.valueOf(2), "严重", twoDaysAgo, now);
        InsightWorkItem defect2 = InsightWorkItem.of(UUID.randomUUID(), "D-2", "defect", "新建",
                productId, sprintId, BigDecimal.valueOf(1), "致命", twoDaysAgo, now);

        List<InsightWorkItem> items = List.of(task1, task2, defect1, defect2);
        when(workItemRepo.findByProductIdAndSprintId(productId, sprintId)).thenReturn(items);

        InsightSprint sprint = InsightSprint.of(sprintId, "Sprint 1", productId, LocalDate.now().minusDays(7), LocalDate.now().plusDays(7));
        when(sprintRepo.findByProductId(productId)).thenReturn(List.of(sprint));
        when(workItemRepo.findBySprintId(sprintId)).thenReturn(items);

        EfficiencyReportDto report = service.getEfficiencyReport(productId, sprintId);

        assertThat(report.totalItems()).isEqualTo(4);
        assertThat(report.completedItems()).isEqualTo(2); // task1(done) + defect1(已修复)
        assertThat(report.completionRate()).isEqualTo(0.5);
        assertThat(report.avgCycleTimeDays()).isGreaterThanOrEqualTo(1.9);
        assertThat(report.defectCount()).isEqualTo(2);
        assertThat(report.defectResolutionRate()).isEqualTo(0.5);
        assertThat(report.totalStoryPoints()).isEqualByComparingTo("11");
        assertThat(report.completedStoryPoints()).isEqualByComparingTo("7");
        assertThat(report.defectSeverityDistribution()).containsEntry("严重", 1).containsEntry("致命", 1);
        assertThat(report.sprintVelocities()).hasSize(1);
        assertThat(report.sprintVelocities().get(0).completedPoints()).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("燃尽图计算：验证理想线性递减与实际剩余故事点")
    void testGetBurndownReport() {
        LocalDate start = LocalDate.now().minusDays(5);
        LocalDate due = LocalDate.now().plusDays(5);
        InsightSprint sprint = InsightSprint.of(sprintId, "Sprint Beta", productId, start, due);

        Instant completedAt = start.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant();
        InsightWorkItem w1 = InsightWorkItem.of(UUID.randomUUID(), "T-10", "task", "done",
                productId, sprintId, BigDecimal.valueOf(10), null, start.atStartOfDay(ZoneOffset.UTC).toInstant(), completedAt);
        InsightWorkItem w2 = InsightWorkItem.of(UUID.randomUUID(), "T-11", "task", "todo",
                productId, sprintId, BigDecimal.valueOf(20), null, start.atStartOfDay(ZoneOffset.UTC).toInstant(), null);

        when(sprintRepo.findById(sprintId)).thenReturn(Optional.of(sprint));
        when(workItemRepo.findBySprintId(sprintId)).thenReturn(List.of(w1, w2));

        BurndownReportDto report = service.getBurndownReport(sprintId);

        assertThat(report.sprintId()).isEqualTo(sprintId);
        assertThat(report.sprintName()).isEqualTo("Sprint Beta");
        assertThat(report.totalPoints()).isEqualByComparingTo("30");
        assertThat(report.timeline()).isNotEmpty();

        // 第 1 天：两项均未完成，剩余点数应为 30
        assertThat(report.timeline().get(0).actualPoints()).isEqualTo(30.0);
        assertThat(report.timeline().get(0).idealPoints()).isEqualTo(30.0);

        // 最后一天的理想点数应为 0
        int lastIdx = report.timeline().size() - 1;
        assertThat(report.timeline().get(lastIdx).idealPoints()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("累积流图 CFD 计算：生成指定自然日跨度的 todo/wip/done 阶梯分布")
    void testGetCfdReport() {
        LocalDate today = LocalDate.now();
        Instant fiveDaysAgo = today.minusDays(5).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant twoDaysAgo = today.minusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant();

        InsightWorkItem wDone = InsightWorkItem.of(UUID.randomUUID(), "T-1", "task", "done",
                productId, null, BigDecimal.valueOf(1), null, fiveDaysAgo, twoDaysAgo);
        InsightWorkItem wWip = InsightWorkItem.of(UUID.randomUUID(), "T-2", "task", "in_progress",
                productId, null, BigDecimal.valueOf(1), null, fiveDaysAgo, null);
        InsightWorkItem wTodo = InsightWorkItem.of(UUID.randomUUID(), "T-3", "task", "todo",
                productId, null, BigDecimal.valueOf(1), null, fiveDaysAgo, null);

        when(workItemRepo.findByProductId(productId)).thenReturn(List.of(wDone, wWip, wTodo));

        CfdReportDto cfd = service.getCfdReport(productId, null, 14);

        assertThat(cfd.series()).hasSize(15); // 14 + 1
        var latest = cfd.series().get(cfd.series().size() - 1);
        assertThat(latest.done()).isEqualTo(1);
        assertThat(latest.inProgress()).isEqualTo(1);
        assertThat(latest.todo()).isEqualTo(1);
    }

    @Test
    @DisplayName("不同类型工作项的完成态判定")
    void testIsDone() {
        // Task
        InsightWorkItem taskDone = InsightWorkItem.of(UUID.randomUUID(), "T-1", "task", "done", null, null, null, null, null, null);
        InsightWorkItem taskTodo = InsightWorkItem.of(UUID.randomUUID(), "T-2", "task", "todo", null, null, null, null, null, null);
        assertThat(service.isDone(taskDone)).isTrue();
        assertThat(service.isDone(taskTodo)).isFalse();

        // Defect
        InsightWorkItem defectFixed = InsightWorkItem.of(UUID.randomUUID(), "D-1", "defect", "已修复", null, null, null, null, null, null);
        InsightWorkItem defectClosed = InsightWorkItem.of(UUID.randomUUID(), "D-2", "defect", "已关闭", null, null, null, null, null, null);
        InsightWorkItem defectNew = InsightWorkItem.of(UUID.randomUUID(), "D-3", "defect", "新建", null, null, null, null, null, null);
        assertThat(service.isDone(defectFixed)).isTrue();
        assertThat(service.isDone(defectClosed)).isTrue();
        assertThat(service.isDone(defectNew)).isFalse();

        // Requirement
        InsightWorkItem reqDelivered = InsightWorkItem.of(UUID.randomUUID(), "REQ-1", "requirement", "delivered", null, null, null, null, null, null);
        InsightWorkItem reqDev = InsightWorkItem.of(UUID.randomUUID(), "REQ-2", "requirement", "in_dev", null, null, null, null, null, null);
        assertThat(service.isDone(reqDelivered)).isTrue();
        assertThat(service.isDone(reqDev)).isFalse();
    }
}
