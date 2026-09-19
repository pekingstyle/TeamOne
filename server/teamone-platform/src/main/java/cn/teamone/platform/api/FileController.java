package cn.teamone.platform.api;

import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.domain.FileObject;
import cn.teamone.platform.infra.MinioPort;
import cn.teamone.platform.repo.FileObjectRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import io.minio.StatObjectResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文件两步制端点（05 §3.3 platform 表 / §9.3；M2-INC-2 T-5）：
 *
 * <ul>
 *   <li><b>POST /files/presign</b>：校验（50MB / mime 白名单 / 扩展黑名单）→ 造行
 *       (status=uploading，object_key 服务端生成) → 预签名 PUT（5min）；</li>
 *   <li><b>POST /files/{id}/complete</b>：statObject 验证客户端确实已 PUT（真实 size 兜底
 *       size 谎报，超限 PLT_4000）→ status=ready；</li>
 *   <li><b>GET /files/{id}/download-url</b>：预签名 GET（5min，response-content-disposition
 *       携带原文件名）。</li>
 * </ul>
 *
 * <p>登录态即可（/api/** 全局 JWT）；下载的会话成员/资源级权限 M3 收紧（05 §3.3 注）。
 * MinIO 通信异常统一 SRV_5030（{@link MinioPort} 转换）。</p>
 */
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    /** 单文件上限（INC-2 红线 4：50MB） */
    static final long MAX_SIZE_BYTES = 50L * 1024 * 1024;
    /** 预签名有效期（两步制定稿：5min） */
    static final Duration PRESIGN_TTL = Duration.ofMinutes(5);
    /** mime 白名单（image/*、text/* 前缀 + 下列精确值） */
    private static final Set<String> MIME_EXACT = Set.of(
            "application/pdf", "application/zip", "application/x-zip-compressed",
            "application/msword", "application/rtf",
            "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
            "application/vnd.ms-works", "application/vnd.ms-project");
    private static final String MIME_OFFICE_PREFIX = "application/vnd.openxmlformats-officedocument.";
    /** 扩展名黑名单（小写；可执行/脚本直拒） */
    private static final Set<String> EXT_BLACKLIST = Set.of("exe", "bat", "sh", "msi");

    private final FileObjectRepository files;
    private final MinioPort minio;

    public FileController(FileObjectRepository files, MinioPort minio) {
        this.files = files;
        this.minio = minio;
    }

    /** presign 请求体：sha256 可选（预留秒传/校验） */
    public record PresignReq(String fileName, Long size, String mime, String sha256) {}

    @PostMapping("/presign")
    public Map<String, Object> presign(@AuthenticationPrincipal AppUser me,
                                       @RequestBody PresignReq req) {
        String fileName = req.fileName() == null ? "" : req.fileName().trim();
        if (fileName.isEmpty()) {
            throw new BusinessException(ErrorCode.PLT_4000, "fileName 必填");
        }
        Long declared = req.size();
        if (declared == null || declared <= 0) {
            throw new BusinessException(ErrorCode.PLT_4000, "size 必须为正整数（字节）");
        }
        if (declared > MAX_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.PLT_4000, "文件超出大小限制（≤50MB）");
        }
        String mime = req.mime() == null ? "" : req.mime().trim().toLowerCase(Locale.ROOT);
        if (!mimeAllowed(mime)) {
            throw new BusinessException(ErrorCode.PLT_4000, "不支持的文件类型: " + req.mime());
        }
        String ext = extOf(fileName);
        if (EXT_BLACKLIST.contains(ext)) {
            // 扩展黑名单独立于 mime（浏览器 mime 可伪造，双保险）
            throw new BusinessException(ErrorCode.PLT_4000, "不支持的文件扩展名: ." + ext);
        }

        // object_key 服务端生成（红线 4）：uploads/{yyyyMM}/{uuid}.{ext}，ext 白名单字符净化。
        // 注意：objectKey 的 uuid 与实体 id 独立生成——@GeneratedValue 实体手动 setId 会被
        // Hibernate 判为 detached 走 merge，触发乐观锁异常（实测踩坑，勿回退）。
        FileObject file = new FileObject();
        file.setUploaderId(me.getId());
        file.setBucket(minio.defaultBucket());
        file.setObjectKey("uploads/" + DateTimeFormatter.ofPattern("yyyyMM").format(LocalDate.now())
                + "/" + UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext));
        file.setSize(declared);
        file.setMime(mime);
        file.setOriginalName(fileName);
        file.setSha256(req.sha256());
        file.setStatus(FileObject.STATUS_UPLOADING);
        files.save(file);

        String uploadUrl = minio.presignPut(file.getBucket(), file.getObjectKey(), PRESIGN_TTL);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("fileId", file.getId());
        res.put("uploadUrl", uploadUrl);
        res.put("bucket", file.getBucket());
        res.put("objectKey", file.getObjectKey());
        return res;
    }

    /**
     * 第二步：客户端 PUT 完成后回报。statObject 验证对象真实存在（谎报 size 在此兜底——
     * 以 MinIO 实测为准回填；实测超限拒 ready，PLT_4000）。仅 uploader 本人可 complete。
     */
    @PostMapping("/{id}/complete")
    public Map<String, Object> complete(@AuthenticationPrincipal AppUser me,
                                        @PathVariable UUID id) {
        FileObject file = requireMine(me, id);
        StatObjectResponse stat = minio.statObject(file.getBucket(), file.getObjectKey());
        long actual = stat.size();
        if (actual > MAX_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.PLT_4000,
                    "文件实际大小超出限制（≤50MB）: " + actual + "B");
        }
        file.setSize(actual);
        file.setStatus(FileObject.STATUS_READY);
        files.save(file);
        return file.toView();
    }

    /** 预签名下载（5min，response-content-disposition 还原 original_name） */
    @GetMapping("/{id}/download-url")
    public Map<String, Object> downloadUrl(@AuthenticationPrincipal AppUser me,
                                           @PathVariable UUID id) {
        FileObject file = requireMine(me, id);
        String url = minio.presignGet(file.getBucket(), file.getObjectKey(), PRESIGN_TTL,
                file.getOriginalName());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("downloadUrl", url);
        res.put("fileName", file.getOriginalName());
        res.put("mime", file.getMime());
        res.put("size", file.getSize());
        res.put("status", file.getStatus());
        return res;
    }

    // ==================== 内部 ====================

    private FileObject requireMine(AppUser me, UUID id) {
        FileObject file = files.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "文件不存在: " + id));
        if (!file.getUploaderId().equals(me.getId())) {
            throw new BusinessException(ErrorCode.PLT_4030, "仅上传者可操作该文件");
        }
        return file;
    }

    /** mime 白名单：image/*、text/* 前缀 + pdf/zip/office 精确与 ooxml 前缀 */
    static boolean mimeAllowed(String mime) {
        if (mime.isEmpty()) {
            return false;
        }
        return mime.startsWith("image/") || mime.startsWith("text/")
                || MIME_EXACT.contains(mime) || mime.startsWith(MIME_OFFICE_PREFIX);
    }

    /** 取扩展名：小写、仅 [a-z0-9]{1,10} 认可（防 path traversal/双扩展注入），无扩展返回空串 */
    static String extOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,10}") ? ext : "";
    }
}
