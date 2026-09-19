package cn.teamone.insight.app;

import cn.teamone.insight.domain.InsightSprint;
import cn.teamone.insight.domain.InsightWorkItem;
import cn.teamone.insight.dto.BurndownPointDto;
import cn.teamone.insight.dto.BurndownReportDto;
import cn.teamone.insight.dto.CfdPointDto;
import cn.teamone.insight.dto.CfdReportDto;
import cn.teamone.insight.dto.EfficiencyReportDto;
import cn.teamone.insight.dto.SprintVelocityDto;
import cn.teamone.insight.repo.InsightSprintRepository;
import cn.teamone.insight.repo.InsightWorkItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 研发效能与报表统计核心服务（M3-INC-2 V-19 效能度量落地）。
 *
 * <p>职责划分：
 * 1. 效能综合指标度量：交付总数、完成率、平均交付周期 (Cycle/Lead Time)、缺陷解决率及历史速率汇总；
 * 2. 迭代燃尽图计算：基于工作项点数变化提供理想线性衰减与实际剩余故事点对比；
 * 3. 累积流图 (CFD) 时序推导：推导 todo / in_progress / done 的时序累积态势；
 * 4. 严格遵守跨 schema 只读纪律（红线①）与 ArchUnit R6 消费者解耦纪律。</p>
 */
@Service
@Transactional(readOnly = true)
public class EfficiencyReportService {

    private final InsightWorkItemRepository workItemRepo;
    private final InsightSprintRepository sprintRepo;

    /**
     * 构造效能服务，注入只读仓库依赖
     *
     * @param workItemRepo 工作项只读仓库
     * @param sprintRepo 迭代只读仓库
     */
    public EfficiencyReportService(InsightWorkItemRepository workItemRepo,
                                  InsightSprintRepository sprintRepo) {
        this.workItemRepo = workItemRepo;
        this.sprintRepo = sprintRepo;
    }

    /**
     * 获取效能综合报表（支持按产品与迭代筛选）
     *
     * @param productId 产品可选过滤
     * @param sprintId 迭代可选过滤
     * @return 效能综合度量 DTO
     */
    public EfficiencyReportDto getEfficiencyReport(UUID productId, UUID sprintId) {
        List<InsightWorkItem> items = loadFilteredItems(productId, sprintId);
        int totalItems = items.size();

        // 统计已完成工作项
        List<InsightWorkItem> doneItems = items.stream()
                .filter(this::isDone)
                .toList();
        int completedItems = doneItems.size();
        double completionRate = totalItems > 0 ? (double) completedItems / totalItems : 0.0;

        // 计算平均交付周期（从 created_at 到 updated_at）
        double avgCycleTimeDays = 0.0;
        List<Double> cycleTimes = doneItems.stream()
                .filter(w -> w.getCreatedAt() != null && w.getUpdatedAt() != null)
                .map(w -> {
                    long hours = Duration.between(w.getCreatedAt(), w.getUpdatedAt()).toHours();
                    return Math.max(0.0, hours / 24.0);
                })
                .toList();
        if (!cycleTimes.isEmpty()) {
            double sum = cycleTimes.stream().mapToDouble(Double::doubleValue).sum();
            avgCycleTimeDays = BigDecimal.valueOf(sum / cycleTimes.size())
                    .setScale(1, RoundingMode.HALF_UP)
                    .doubleValue();
        }

        // 缺陷统计
        List<InsightWorkItem> defects = items.stream()
                .filter(w -> "defect".equalsIgnoreCase(w.getType()))
                .toList();
        int defectCount = defects.size();
        int resolvedDefects = (int) defects.stream().filter(this::isDone).count();
        double defectResolutionRate = defectCount > 0 ? (double) resolvedDefects / defectCount : 0.0;

        // 故事点统计
        BigDecimal totalStoryPoints = items.stream()
                .map(InsightWorkItem::getStoryPoints)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal completedStoryPoints = doneItems.stream()
                .map(InsightWorkItem::getStoryPoints)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 缺陷严重度分布
        Map<String, Integer> severityDist = new LinkedHashMap<>();
        severityDist.put("致命", 0);
        severityDist.put("严重", 0);
        severityDist.put("一般", 0);
        severityDist.put("轻微", 0);
        for (InsightWorkItem d : defects) {
            String sev = d.getSeverity() != null ? d.getSeverity() : "一般";
            severityDist.put(sev, severityDist.getOrDefault(sev, 0) + 1);
        }

        // 迭代速率列表
        List<InsightSprint> sprints = productId != null
                ? sprintRepo.findByProductId(productId)
                : sprintRepo.findAll();
        List<SprintVelocityDto> velocities = new ArrayList<>();
        for (InsightSprint s : sprints) {
            List<InsightWorkItem> sItems = workItemRepo.findBySprintId(s.getId());
            BigDecimal sTotal = sItems.stream()
                    .map(InsightWorkItem::getStoryPoints)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal sDone = sItems.stream()
                    .filter(this::isDone)
                    .map(InsightWorkItem::getStoryPoints)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            velocities.add(new SprintVelocityDto(s.getId(), s.getName(), sDone, sTotal));
        }

        return new EfficiencyReportDto(
                totalItems,
                completedItems,
                completionRate,
                avgCycleTimeDays,
                defectCount,
                defectResolutionRate,
                totalStoryPoints,
                completedStoryPoints,
                severityDist,
                velocities
        );
    }

    /**
     * 计算指定迭代的燃尽图数据
     *
     * @param sprintId 迭代 ID
     * @return 燃尽图完整数据 DTO
     */
    public BurndownReportDto getBurndownReport(UUID sprintId) {
        if (sprintId == null) {
            // 默认返回空结构
            return new BurndownReportDto(null, "未选择迭代", LocalDate.now(), LocalDate.now(), BigDecimal.ZERO, Collections.emptyList());
        }

        InsightSprint sprint = sprintRepo.findById(sprintId).orElse(null);
        if (sprint == null) {
            return new BurndownReportDto(sprintId, "未知迭代", LocalDate.now(), LocalDate.now(), BigDecimal.ZERO, Collections.emptyList());
        }

        List<InsightWorkItem> items = workItemRepo.findBySprintId(sprintId);
        BigDecimal totalPoints = items.stream()
                .map(InsightWorkItem::getStoryPoints)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 确定迭代起止日期
        LocalDate start = sprint.getStartDate() != null ? sprint.getStartDate() : LocalDate.now().minusDays(14);
        LocalDate due = sprint.getDueDate() != null ? sprint.getDueDate() : start.plusDays(14);
        if (due.isBefore(start)) {
            due = start.plusDays(14);
        }

        long daysBetween = ChronoUnit.DAYS.between(start, due);
        int totalDays = (int) Math.max(1, daysBetween);

        List<BurndownPointDto> timeline = new ArrayList<>();
        double totalPtsVal = totalPoints.doubleValue();

        for (int i = 0; i <= totalDays; i++) {
            LocalDate currentDate = start.plusDays(i);
            // 理想线性下降故事点
            double ideal = totalPtsVal * (1.0 - ((double) i / totalDays));

            // 计算实际剩余点数：在 currentDate 当天尚未完成的工作项故事点之和
            double remainingPoints = items.stream()
                    .filter(w -> !isItemCompletedOnOrBefore(w, currentDate))
                    .map(w -> w.getStoryPoints() != null ? w.getStoryPoints().doubleValue() : 0.0)
                    .mapToDouble(Double::doubleValue)
                    .sum();

            timeline.add(new BurndownPointDto(
                    i + 1,
                    currentDate,
                    BigDecimal.valueOf(Math.max(0.0, ideal)).setScale(1, RoundingMode.HALF_UP).doubleValue(),
                    BigDecimal.valueOf(Math.max(0.0, remainingPoints)).setScale(1, RoundingMode.HALF_UP).doubleValue()
            ));
        }

        return new BurndownReportDto(
                sprint.getId(),
                sprint.getName(),
                start,
                due,
                totalPoints,
                timeline
        );
    }

    /**
     * 生成累积流图 (CFD) 时序数据
     *
     * @param productId 产品可选过滤
     * @param sprintId 迭代可选过滤
     * @param days 回溯自然日天数（默认 30 天，范围 7~90）
     * @return 累积流图时序响应
     */
    public CfdReportDto getCfdReport(UUID productId, UUID sprintId, int days) {
        int rangeDays = Math.max(7, Math.min(days, 90));
        List<InsightWorkItem> items = loadFilteredItems(productId, sprintId);

        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(rangeDays);

        List<CfdPointDto> series = new ArrayList<>();

        for (int i = 0; i <= rangeDays; i++) {
            LocalDate date = startDate.plusDays(i);

            // 当日或之前创建的工作项
            List<InsightWorkItem> availableItems = items.stream()
                    .filter(w -> w.getCreatedAt() == null
                            || !w.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate().isAfter(date))
                    .toList();

            int done = 0;
            int inProgress = 0;
            int todo = 0;

            for (InsightWorkItem w : availableItems) {
                if (isItemCompletedOnOrBefore(w, date)) {
                    done++;
                } else if (!"todo".equalsIgnoreCase(w.getStatus())) {
                    inProgress++;
                } else {
                    todo++;
                }
            }

            series.add(new CfdPointDto(date, todo, inProgress, done));
        }

        return new CfdReportDto(series);
    }

    // ==================== 辅助计算与口径判定 ====================

    /**
     * 判定工作项是否处于最终完成/关闭态
     *
     * @param item 目标工作项
     * @return true 表示已完成或已关闭
     */
    public boolean isDone(InsightWorkItem item) {
        if (item == null || item.getStatus() == null) {
            return false;
        }
        String status = item.getStatus().trim();
        String type = item.getType() != null ? item.getType().trim().toLowerCase() : "";

        if ("defect".equals(type)) {
            return "已修复".equals(status) || "回归通过".equals(status) || "已关闭".equals(status);
        }
        if ("test_task".equals(type)) {
            return "passed".equalsIgnoreCase(status);
        }
        if ("requirement".equals(type)) {
            return "delivered".equalsIgnoreCase(status) || "closed".equalsIgnoreCase(status);
        }
        // 缺省/任务类型
        return "done".equalsIgnoreCase(status) || "closed".equalsIgnoreCase(status);
    }

    /**
     * 判定工作项是否在指定日期或之前已进入完成态
     *
     * @param item 目标工作项
     * @param date 指定截止日期
     * @return true 表示已在指定日期之前完成
     */
    private boolean isItemCompletedOnOrBefore(InsightWorkItem item, LocalDate date) {
        if (!isDone(item)) {
            return false;
        }
        if (item.getUpdatedAt() == null) {
            return true;
        }
        LocalDate updatedDate = item.getUpdatedAt().atZone(ZoneOffset.UTC).toLocalDate();
        return !updatedDate.isAfter(date);
    }

    /**
     * 按产品与迭代联合筛选工作项列表
     *
     * @param productId 可选产品 ID
     * @param sprintId 可选迭代 ID
     * @return 过滤后的工作项列表
     */
    private List<InsightWorkItem> loadFilteredItems(UUID productId, UUID sprintId) {
        if (productId != null && sprintId != null) {
            return workItemRepo.findByProductIdAndSprintId(productId, sprintId);
        } else if (productId != null) {
            return workItemRepo.findByProductId(productId);
        } else if (sprintId != null) {
            return workItemRepo.findBySprintId(sprintId);
        } else {
            return workItemRepo.findAll();
        }
    }
}
