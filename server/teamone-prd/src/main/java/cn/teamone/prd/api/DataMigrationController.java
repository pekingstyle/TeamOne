package cn.teamone.prd.api;

import cn.teamone.platform.domain.AppUser;
import cn.teamone.prd.app.DataMigrationService;
import cn.teamone.prd.dto.MigrationDto.ImportExecutionRequest;
import cn.teamone.prd.dto.MigrationDto.ImportResultDto;
import cn.teamone.prd.dto.MigrationDto.ValidationReportDto;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * 数据迁移与质量门禁 REST 控制器（P0 企业治理底座 · 模板下载、预检质量诊断与原子导入）。
 */
@RestController
@RequestMapping("/api/v1/products/{productId}/migration")
public class DataMigrationController {

    private final DataMigrationService migrationService;

    public DataMigrationController(DataMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    /**
     * 下载标准工作项导入 CSV 模板（UTF-8 BOM 格式，兼容 Excel 中文）。
     *
     * @param productId 目标产品 ID
     * @return 附件文件流
     */
    @GetMapping("/template")
    public ResponseEntity<byte[]> downloadTemplate(@PathVariable UUID productId) {
        byte[] bytes = migrationService.generateCsvTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"work_items_template.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(bytes);
    }

    /**
     * 上传工作项文件执行第一阶段数据质量预检（支持文件上传或直接 POST 文本内容）。
     *
     * @param me 当前登录用户
     * @param productId 目标产品 ID
     * @param file 上传的文件（可选）
     * @param body JSON 请求体（可选）
     * @return 数据质量诊断报告
     */
    @PostMapping("/validate")
    public ValidationReportDto validateImport(@AuthenticationPrincipal AppUser me,
                                             @PathVariable UUID productId,
                                             @RequestParam(value = "file", required = false) MultipartFile file,
                                             @RequestBody(required = false) Map<String, String> body) throws IOException {
        String content;
        String filename;

        if (file != null && !file.isEmpty()) {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
            filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.csv";
        } else if (body != null && body.containsKey("content")) {
            content = body.get("content");
            filename = body.getOrDefault("filename", "raw_content.csv");
        } else {
            content = "";
            filename = "empty.csv";
        }

        return migrationService.validateWorkItemImport(productId, content, filename);
    }

    /**
     * 第二阶段：确认执行原子事务导入（落库并分配业务键）。
     *
     * @param me 当前登录用户
     * @param productId 目标产品 ID
     * @param req 包含预检行列表与跳过错误选项的请求体
     * @return 导入结果
     */
    @PostMapping("/import")
    public ImportResultDto executeImport(@AuthenticationPrincipal AppUser me,
                                         @PathVariable UUID productId,
                                         @RequestBody ImportExecutionRequest req) {
        return migrationService.executeWorkItemImport(productId, req.rows(), req.skipErrors(), me.getId());
    }
}
