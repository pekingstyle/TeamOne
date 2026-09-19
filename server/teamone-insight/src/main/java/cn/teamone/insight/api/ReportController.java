package cn.teamone.insight.api;

import cn.teamone.insight.app.EfficiencyReportService;
import cn.teamone.insight.dto.BurndownReportDto;
import cn.teamone.insight.dto.CfdReportDto;
import cn.teamone.insight.dto.EfficiencyReportDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 研发效能度量与统计报表端点（M3-INC-2 V-19）。
 *
 * <p>提供：
 * 1. GET /api/v1/reports/efficiency 效能综合报表（交付完成率、周期、缺陷与速率）；
 * 2. GET /api/v1/reports/burndown 迭代燃尽图明细与理想/实际双曲线；
 * 3. GET /api/v1/reports/cfd 累积流图 (CFD) 时序数据堆叠。</p>
 *
 * <p>鉴权：登录态（JWT 校验生效，只读分析端点）。</p>
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final EfficiencyReportService reportService;

    /**
     * 构造报表控制器，注入效能统计服务
     *
     * @param reportService 研发效能报表服务
     */
    public ReportController(EfficiencyReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * 查询研发效能综合指标汇总
     *
     * @param productId 可选产品筛选
     * @param sprintId 可选迭代筛选
     * @return 效能综合报表 DTO
     */
    @GetMapping("/efficiency")
    public EfficiencyReportDto getEfficiency(
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID sprintId) {
        Actor.require();
        return reportService.getEfficiencyReport(productId, sprintId);
    }

    /**
     * 查询指定迭代燃尽图数据
     *
     * @param sprintId 目标迭代 ID
     * @return 迭代燃尽报表 DTO
     */
    @GetMapping("/burndown")
    public BurndownReportDto getBurndown(
            @RequestParam(required = false) UUID sprintId) {
        Actor.require();
        return reportService.getBurndownReport(sprintId);
    }

    /**
     * 查询累积流图 (CFD) 时序堆叠数据
     *
     * @param productId 可选产品筛选
     * @param sprintId 可选迭代筛选
     * @param days 回溯天数（默认 30 天，范围 7~90 天）
     * @return 累积流图数据 DTO
     */
    @GetMapping("/cfd")
    public CfdReportDto getCfd(
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID sprintId,
            @RequestParam(defaultValue = "30") int days) {
        Actor.require();
        return reportService.getCfdReport(productId, sprintId, days);
    }
}
