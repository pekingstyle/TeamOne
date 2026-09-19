package cn.teamone.prd.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 数据迁移与质量门禁数据契约（P0 企业治理底座 · 存量项目工作项迁移与数据质量检查）。
 */
public class MigrationDto {

    /**
     * 单行数据质量校验错误诊断详情。
     *
     * @param rowNumber 表格物理行号（从 2 开始，第 1 行为表头）
     * @param columnName 产生异常的列名称
     * @param message 错误诊断信息与修复引导提示
     * @param rawValue 上传文件中的原始输入值
     */
    public record RowErrorDto(
            int rowNumber,
            String columnName,
            String message,
            String rawValue
    ) {}

    /**
     * 解析后的单条工作项草稿行。
     *
     * @param rowNumber 原始行号
     * @param type 工作项类型（requirement / task / defect / test_task）
     * @param title 标题
     * @param priority 优先级（P0 / P1 / P2 / P3）
     * @param status 初始状态
     * @param assigneeUsername 经办人用户名
     * @param assigneeId 解析匹配后的经办人 UUID（若匹配失败为 null）
     * @param sprintName 所属迭代名称
     * @param sprintId 解析匹配后的迭代 UUID（若匹配失败为 null）
     * @param storyPoints 故事点
     * @param estimateHours 预估工时
     * @param severity 缺陷严重度（blocker / critical / normal / minor）
     * @param description 描述详情
     * @param valid 该行数据是否完全合规
     * @param errorMessages 该行所有的校验异常消息汇总
     */
    public record ParsedRowDto(
            int rowNumber,
            String type,
            String title,
            String priority,
            String status,
            String assigneeUsername,
            UUID assigneeId,
            String sprintName,
            UUID sprintId,
            BigDecimal storyPoints,
            BigDecimal estimateHours,
            String severity,
            String description,
            boolean valid,
            List<String> errorMessages
    ) {}

    /**
     * 数据质量检查诊断总报告。
     *
     * @param filename 上传的文件名
     * @param totalRows 文件内解析的总数据行数
     * @param validRows 校验完全合规的行数
     * @param errorRows 包含至少一处质量缺陷的异常行数
     * @param canImport 是否允许立即执行无损导入（当 errorRows == 0 时为 true）
     * @param errors 行级错误诊断详细列表
     * @param previewRows 导入前预览数据列表（最多展示前 30 条）
     */
    public record ValidationReportDto(
            String filename,
            int totalRows,
            int validRows,
            int errorRows,
            boolean canImport,
            List<RowErrorDto> errors,
            List<ParsedRowDto> previewRows
    ) {}

    /**
     * 执行原子导入请求参数。
     *
     * @param rows 待落库的数据行列表（通常为预检接口回传的数据项）
     * @param skipErrors 若仍存在异常行，是否自动跳过异常行仅导入合规行
     */
    public record ImportExecutionRequest(
            List<ParsedRowDto> rows,
            boolean skipErrors
    ) {}

    /**
     * 数据导入执行完成结果。
     *
     * @param importedCount 成功创建并持久化的工作项总数
     * @param skippedCount 跳过的异常行数
     * @param createdKeys 分配生成的全局业务键列表（如 TEAMONE-101, T-12 等）
     */
    public record ImportResultDto(
            int importedCount,
            int skippedCount,
            List<String> createdKeys
    ) {}
}
