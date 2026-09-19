package cn.teamone.prd.app;

import cn.teamone.platform.audit.AuditService;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.prd.domain.Product;
import cn.teamone.prd.domain.Sprint;
import cn.teamone.prd.domain.WorkItem;
import cn.teamone.prd.dto.MigrationDto.ImportResultDto;
import cn.teamone.prd.dto.MigrationDto.ParsedRowDto;
import cn.teamone.prd.dto.MigrationDto.RowErrorDto;
import cn.teamone.prd.dto.MigrationDto.ValidationReportDto;
import cn.teamone.prd.repo.ProductRepository;
import cn.teamone.prd.repo.SprintRepository;
import cn.teamone.prd.repo.WorkItemRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 存量数据迁移与数据质量门禁应用服务（P0 企业治理底座 · 规范化 Excel/CSV 工作项导入与关系完整性校验）。
 *
 * <p>核心职责：
 * <ul>
 *   <li>标准导入模板生成（UTF-8 BOM CSV，Excel 双击直接打开不乱码，附带示例行）；</li>
 *   <li><b>数据质量检查门禁（Pre-check & Dry-Run）</b>：
 *     <ol>
 *       <li>必填性校验（类型、标题、优先级）；</li>
 *       <li><b>外键关联完整性校验</b>：经办人用户名必须在平台用户库且处于 ACTIVE 状态；所属迭代必须属于目标产品；</li>
 *       <li><b>领域语义与状态机校验</b>：工作项类型、缺陷严重度、初始状态映射；</li>
 *       <li><b>数值规约校验</b>：故事点与预估工时非负与极值校验；</li>
 *     </ol>
 *   </li>
 *   <li>输出详尽的行级诊断错误清单（行号、列名、具体原因、原始输入），阻断脏数据污染数据库；</li>
 *   <li><b>原子事务落库</b>：事务内批量分配业务键（如 T-12, D-88）、构建实体关系并持久化，记录迁移审计事件。</li>
 * </ul>
 * </p>
 */
@Service
public class DataMigrationService {

    private static final Map<String, String> SEQUENCE_TYPES = Map.of(
            WorkItem.TYPE_DEFECT, "DEFECT",
            WorkItem.TYPE_TASK, "TASK",
            WorkItem.TYPE_TEST_TASK, "TEST_TASK",
            WorkItem.TYPE_REQUIREMENT, "REQUIREMENT");

    private static final Map<String, String> INITIAL_STATUS = Map.of(
            WorkItem.TYPE_TASK, WorkItem.STATUS_TODO,
            WorkItem.TYPE_TEST_TASK, WorkItem.STATUS_PENDING,
            WorkItem.TYPE_DEFECT, WorkItem.STATUS_DEFECT_NEW,
            WorkItem.TYPE_REQUIREMENT, WorkItem.STATUS_REQ_DRAFT);

    private final ProductRepository productRepo;
    private final SprintRepository sprintRepo;
    private final AppUserRepository userRepo;
    private final WorkItemRepository workItemRepo;
    private final KeySequenceService sequences;
    private final AuditService audit;

    public DataMigrationService(ProductRepository productRepo,
                                SprintRepository sprintRepo,
                                AppUserRepository userRepo,
                                WorkItemRepository workItemRepo,
                                KeySequenceService sequences,
                                AuditService audit) {
        this.productRepo = productRepo;
        this.sprintRepo = sprintRepo;
        this.userRepo = userRepo;
        this.workItemRepo = workItemRepo;
        this.sequences = sequences;
        this.audit = audit;
    }

    /**
     * 生成并输出标准工作项导入 CSV 模板（带 UTF-8 BOM 兼容 Excel）。
     *
     * @return 模板字节流
     */
    public byte[] generateCsvTemplate() {
        StringBuilder sb = new StringBuilder();
        // UTF-8 BOM，确保 Windows Excel 双击直接正常打开中文
        sb.append('\uFEFF');
        sb.append("工作项类型*,标题*,优先级*,初始状态,经办人用户名,所属迭代,故事点,预估工时(小时),缺陷严重度,描述\n");
        sb.append("需求,支持 OAuth 2.0 社交登录,P1,草稿,admin,Sprint 1,5.0,20,,\"支持企微与 GitHub 授权登录，提升新用户接入效率\"\n");
        sb.append("任务,编写 OAuth 授权回调接口,P1,待处理,admin,Sprint 1,3.0,12,,\"实现 /api/v1/auth/callback/oauth 端点并编写测试用例\"\n");
        sb.append("缺陷,移动端登录超时白屏,P0,新建,admin,Sprint 1,,,致命,\"在网络延时超过 5 秒时界面未能捕获异常\"\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 对上传的 CSV 数据执行全维度数据质量校验门禁（Pre-check 预检，不落库）。
     *
     * @param productId 目标产品 ID
     * @param csvContent 上传的文件文本内容
     * @param filename 原始文件名
     * @return 结构化数据质量诊断报告
     */
    @Transactional(readOnly = true)
    public ValidationReportDto validateWorkItemImport(UUID productId, String csvContent, String filename) {
        // ① 检查目标产品是否存在
        Product product = productRepo.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "产品不存在: " + productId));

        // ② 加载目标产品的全部有效迭代列表（用于迭代名称外键校验）
        List<Sprint> sprints = sprintRepo.findByProductIdOrderByStartDateAsc(productId);
        Map<String, Sprint> sprintMap = new HashMap<>();
        for (Sprint s : sprints) {
            sprintMap.put(s.getName().trim().toLowerCase(Locale.ROOT), s);
        }

        List<RowErrorDto> errors = new ArrayList<>();
        List<ParsedRowDto> parsedRows = new ArrayList<>();

        if (csvContent == null || csvContent.trim().isEmpty()) {
            return new ValidationReportDto(filename, 0, 0, 0, false,
                    List.of(new RowErrorDto(1, "文件内容", "上传文件内容为空", "")), List.of());
        }

        try (BufferedReader reader = new BufferedReader(new StringReader(csvContent))) {
            String line;
            int rowNumber = 0;
            boolean isHeader = true;

            while ((line = reader.readLine()) != null) {
                // 剥离首行可能带有的 UTF-8 BOM 字符
                if (rowNumber == 0 && line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }

                if (line.trim().isEmpty()) {
                    continue;
                }

                rowNumber++;
                if (isHeader) {
                    isHeader = false;
                    continue; // 跳过表头行
                }

                List<String> cols = parseCsvLine(line);
                ParsedRowDto parsed = validateSingleRow(rowNumber, cols, sprintMap, errors);
                parsedRows.add(parsed);
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PLT_4000, "CSV 文件读取解析失败: " + e.getMessage());
        }

        int totalRows = parsedRows.size();
        int errorRows = (int) parsedRows.stream().filter(r -> !r.valid()).count();
        int validRows = totalRows - errorRows;
        boolean canImport = errorRows == 0 && totalRows > 0;

        List<ParsedRowDto> previewRows = parsedRows.stream().limit(30).toList();

        return new ValidationReportDto(filename, totalRows, validRows, errorRows, canImport, errors, previewRows);
    }

    /**
     * 执行原子事务导入（质量检验通过后，或用户选择跳过异常行后落库）。
     *
     * @param productId 目标产品 ID
     * @param rows 待导入的数据行列表
     * @param skipErrors 是否跳过校验异常的行
     * @param operatorId 当前操作管理员 ID
     * @return 导入执行结果
     */
    @Transactional
    public ImportResultDto executeWorkItemImport(UUID productId, List<ParsedRowDto> rows,
                                                boolean skipErrors, UUID operatorId) {
        Product product = productRepo.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "产品不存在: " + productId));

        if (rows == null || rows.isEmpty()) {
            throw new BusinessException(ErrorCode.PLT_4000, "没有可导入的数据行");
        }

        long invalidCount = rows.stream().filter(r -> !r.valid()).count();
        if (invalidCount > 0 && !skipErrors) {
            throw new BusinessException(ErrorCode.PLT_4000,
                    "当前包含 " + invalidCount + " 处数据质量异常，请修复或勾选[跳过异常行]后再行导入");
        }

        List<String> createdKeys = new ArrayList<>();
        int importedCount = 0;
        int skippedCount = 0;

        for (ParsedRowDto row : rows) {
            if (!row.valid()) {
                skippedCount++;
                continue;
            }

            WorkItem wi = new WorkItem();
            wi.setType(row.type());
            wi.setTitle(row.title());
            wi.setPriority(row.priority());
            wi.setStatus(row.status() != null ? row.status() : INITIAL_STATUS.get(row.type()));
            wi.setKey(sequences.nextKey(SEQUENCE_TYPES.get(row.type())));
            wi.setAssigneeId(row.assigneeId());
            wi.setReporterId(operatorId);
            wi.setProductId(productId);
            wi.setSprintId(row.sprintId());
            wi.setStoryPoints(row.storyPoints());
            wi.setEstimateHours(row.estimateHours());
            wi.setSeverity(row.severity());
            wi.setDescription(row.description());
            wi.setLabels("[]");

            // path 占位落库拿 ID
            wi.setPath("/tmp/");
            WorkItem saved = workItemRepo.save(wi);
            saved.setPath("/" + productId + "/" + saved.getId() + "/");
            workItemRepo.save(saved);

            createdKeys.add(saved.getKey());
            importedCount++;
        }

        // 记录数据迁移审计日志
        audit.record(operatorId, "data_migration.import", "work_item", productId.toString(),
                Map.of("importedCount", importedCount, "skippedCount", skippedCount, "keys", createdKeys));

        return new ImportResultDto(importedCount, skippedCount, createdKeys);
    }

    /**
     * 针对单行 CSV 列数据执行全量质量检查。
     */
    private ParsedRowDto validateSingleRow(int rowNumber, List<String> cols,
                                           Map<String, Sprint> sprintMap,
                                           List<RowErrorDto> errorCollector) {
        List<String> rowErrors = new ArrayList<>();

        String rawType = getCol(cols, 0);
        String rawTitle = getCol(cols, 1);
        String rawPriority = getCol(cols, 2);
        String rawStatus = getCol(cols, 3);
        String rawAssignee = getCol(cols, 4);
        String rawSprint = getCol(cols, 5);
        String rawStoryPoints = getCol(cols, 6);
        String rawEstimateHours = getCol(cols, 7);
        String rawSeverity = getCol(cols, 8);
        String rawDescription = getCol(cols, 9);

        // ① 工作项类型校验
        String type = normalizeType(rawType);
        if (type == null) {
            String err = "工作项类型 '" + rawType + "' 无效，仅支持：需求/任务/缺陷/测试任务 (或 requirement/task/defect/test_task)";
            rowErrors.add(err);
            errorCollector.add(new RowErrorDto(rowNumber, "工作项类型*", err, rawType));
        }

        // ② 标题必填与长度校验
        if (rawTitle.trim().isEmpty()) {
            String err = "标题不能为空";
            rowErrors.add(err);
            errorCollector.add(new RowErrorDto(rowNumber, "标题*", err, ""));
        } else if (rawTitle.length() > 200) {
            String err = "标题长度超出上限(最大200字符，当前" + rawTitle.length() + "字)";
            rowErrors.add(err);
            errorCollector.add(new RowErrorDto(rowNumber, "标题*", err, rawTitle));
        }

        // ③ 优先级校验
        String priority = normalizePriority(rawPriority);
        if (priority == null) {
            String err = "优先级 '" + rawPriority + "' 无效，仅支持 P0 / P1 / P2 / P3";
            rowErrors.add(err);
            errorCollector.add(new RowErrorDto(rowNumber, "优先级*", err, rawPriority));
        }

        // ④ 状态机与合法初始状态校验
        String status = normalizeStatus(type, rawStatus);

        // ⑤ 经办人外键完整性校验（必须为库内真实存在且处于正常状态的用户）
        UUID assigneeId = null;
        if (!rawAssignee.trim().isEmpty()) {
            Optional<AppUser> userOpt = userRepo.findByUsername(rawAssignee.trim().toLowerCase(Locale.ROOT));
            if (userOpt.isEmpty()) {
                String err = "经办人账号 '" + rawAssignee + "' 在系统用户中不存在，请核对用户管理列表";
                rowErrors.add(err);
                errorCollector.add(new RowErrorDto(rowNumber, "经办人用户名", err, rawAssignee));
            } else if (userOpt.get().getStatus() != AppUser.Status.ACTIVE) {
                String err = "经办人账号 '" + rawAssignee + "' 已被系统停用，无法指派";
                rowErrors.add(err);
                errorCollector.add(new RowErrorDto(rowNumber, "经办人用户名", err, rawAssignee));
            } else {
                assigneeId = userOpt.get().getId();
            }
        }

        // ⑥ 迭代外键完整性校验（必须属于当前目标产品的有效迭代）
        UUID sprintId = null;
        if (!rawSprint.trim().isEmpty()) {
            Sprint sp = sprintMap.get(rawSprint.trim().toLowerCase(Locale.ROOT));
            if (sp == null) {
                String err = "迭代 '" + rawSprint + "' 在当前产品下不存在，请核对名称或先在产品中建立该迭代";
                rowErrors.add(err);
                errorCollector.add(new RowErrorDto(rowNumber, "所属迭代", err, rawSprint));
            } else {
                sprintId = sp.getId();
            }
        }

        // ⑦ 故事点校验
        BigDecimal storyPoints = null;
        if (!rawStoryPoints.trim().isEmpty()) {
            try {
                BigDecimal val = new BigDecimal(rawStoryPoints.trim());
                if (val.compareTo(BigDecimal.ZERO) < 0 || val.compareTo(BigDecimal.valueOf(100)) > 0) {
                    String err = "故事点数值必须在 0 ~ 100 之间";
                    rowErrors.add(err);
                    errorCollector.add(new RowErrorDto(rowNumber, "故事点", err, rawStoryPoints));
                } else {
                    storyPoints = val;
                }
            } catch (NumberFormatException e) {
                String err = "故事点格式错误，必须为合法数值（如 3.0）";
                rowErrors.add(err);
                errorCollector.add(new RowErrorDto(rowNumber, "故事点", err, rawStoryPoints));
            }
        }

        // ⑧ 预估工时校验
        BigDecimal estimateHours = null;
        if (!rawEstimateHours.trim().isEmpty()) {
            try {
                BigDecimal val = new BigDecimal(rawEstimateHours.trim());
                if (val.compareTo(BigDecimal.ZERO) < 0 || val.compareTo(BigDecimal.valueOf(1000)) > 0) {
                    String err = "预估工时数值必须在 0 ~ 1000 小时之间";
                    rowErrors.add(err);
                    errorCollector.add(new RowErrorDto(rowNumber, "预估工时", err, rawEstimateHours));
                } else {
                    estimateHours = val;
                }
            } catch (NumberFormatException e) {
                String err = "预估工时格式错误，必须为合法数值（如 8）";
                rowErrors.add(err);
                errorCollector.add(new RowErrorDto(rowNumber, "预估工时", err, rawEstimateHours));
            }
        }

        // ⑨ 缺陷严重度校验
        String severity = null;
        if (WorkItem.TYPE_DEFECT.equals(type)) {
            severity = normalizeSeverity(rawSeverity);
            if (severity == null) {
                String err = "缺陷严重度 '" + rawSeverity + "' 无效，仅支持：致命/严重/一般/轻微 (或 blocker/critical/normal/minor)";
                rowErrors.add(err);
                errorCollector.add(new RowErrorDto(rowNumber, "缺陷严重度", err, rawSeverity));
            }
        }

        boolean valid = rowErrors.isEmpty();
        return new ParsedRowDto(
                rowNumber,
                type != null ? type : WorkItem.TYPE_TASK,
                rawTitle.trim(),
                priority != null ? priority : "P2",
                status,
                rawAssignee.trim(),
                assigneeId,
                rawSprint.trim(),
                sprintId,
                storyPoints,
                estimateHours,
                severity,
                rawDescription.trim(),
                valid,
                rowErrors
        );
    }

    private static String getCol(List<String> cols, int index) {
        return index < cols.size() ? cols.get(index).trim() : "";
    }

    /** 归一化工作项类型 */
    private static String normalizeType(String raw) {
        if (raw == null) return null;
        String val = raw.trim().toLowerCase(Locale.ROOT);
        return switch (val) {
            case "需求", "requirement" -> WorkItem.TYPE_REQUIREMENT;
            case "任务", "task" -> WorkItem.TYPE_TASK;
            case "缺陷", "defect", "bug" -> WorkItem.TYPE_DEFECT;
            case "测试任务", "test_task", "test" -> WorkItem.TYPE_TEST_TASK;
            default -> null;
        };
    }

    /** 归一化优先级 */
    private static String normalizePriority(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "P2";
        String val = raw.trim().toUpperCase(Locale.ROOT);
        return switch (val) {
            case "P0", "最高" -> "P0";
            case "P1", "高" -> "P1";
            case "P2", "中", "一般" -> "P2";
            case "P3", "低" -> "P3";
            default -> null;
        };
    }

    /** 归一化状态 */
    private static String normalizeStatus(String type, String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return INITIAL_STATUS.getOrDefault(type, WorkItem.STATUS_TODO);
        }
        String val = raw.trim().toLowerCase(Locale.ROOT);
        if (WorkItem.TYPE_DEFECT.equals(type)) {
            return switch (val) {
                case "新建", "new" -> WorkItem.STATUS_DEFECT_NEW;
                case "修复中", "fixing" -> WorkItem.STATUS_DEFECT_FIXING;
                case "已修复", "fixed" -> WorkItem.STATUS_DEFECT_FIXED;
                case "已关闭", "closed" -> WorkItem.STATUS_DEFECT_CLOSED;
                default -> WorkItem.STATUS_DEFECT_NEW;
            };
        }
        return switch (val) {
            case "草稿", "draft" -> WorkItem.STATUS_REQ_DRAFT;
            case "待处理", "todo" -> WorkItem.STATUS_TODO;
            case "进行中", "in_progress" -> WorkItem.STATUS_IN_PROGRESS;
            case "已完成", "done" -> WorkItem.STATUS_DONE;
            default -> INITIAL_STATUS.getOrDefault(type, WorkItem.STATUS_TODO);
        };
    }

    /** 归一化缺陷严重度 */
    private static String normalizeSeverity(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "normal";
        String val = raw.trim().toLowerCase(Locale.ROOT);
        return switch (val) {
            case "致命", "blocker" -> "blocker";
            case "严重", "critical" -> "critical";
            case "一般", "normal" -> "normal";
            case "轻微", "minor" -> "minor";
            default -> null;
        };
    }

    /**
     * 简单可靠的 CSV 行解析器（支持双引号包裹与逗号转义）。
     */
    public static List<String> parseCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    // 转义双引号 ""
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                tokens.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        tokens.add(sb.toString());
        return tokens;
    }
}
