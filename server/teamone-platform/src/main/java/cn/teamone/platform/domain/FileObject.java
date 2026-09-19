package cn.teamone.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件元数据（platform.file，V9 迁移，05 §9.3 文件两步制）。
 *
 * <p>两步制：presign 造行(status=uploading，object_key 服务端生成 uuid) → 客户端直传 MinIO
 * → complete 时 statObject 验证存在 → status=ready。消息附件引用校验只认 ready 行
 * （未 ready 不得被消息引用，INC-2 红线 4）。DB 不存内容，只存 bucket/object_key/状态。</p>
 *
 * <p>status 用 String 常量（非 enum）：DB CHECK 为小写枚举值，沿用
 * {@code Conversation}/{@code Message} 的字符串常量风格，避免 enum 大小写错位（V2/V3 前车）。</p>
 */
@Entity
@Table(name = "file", schema = "platform")
public class FileObject {

    // ---------- status（CHECK: uploading/ready） ----------
    public static final String STATUS_UPLOADING = "uploading";
    public static final String STATUS_READY = "ready";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "uploader_id", nullable = false)
    private UUID uploaderId;

    @Column(nullable = false, columnDefinition = "text")
    private String bucket;

    /** 服务端生成（uploads/{yyyyMM}/{uuid}.{ext}），客户端不可指定 */
    @Column(name = "object_key", nullable = false, unique = true, columnDefinition = "text")
    private String objectKey;

    /** 客户端可选提供（预留 M3 秒传/完整性校验） */
    @Column(length = 64)
    private String sha256;

    /** presign 时申报值；complete 后以 MinIO statObject 实测为准回填 */
    @Column(nullable = false)
    private long size;

    @Column(nullable = false, columnDefinition = "text")
    private String mime;

    @Column(name = "original_name", nullable = false, columnDefinition = "text")
    private String originalName;

    @Column(nullable = false, columnDefinition = "text")
    private String status = STATUS_UPLOADING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    /** 仅测试反射替代口（@GeneratedValue 实体禁止在业务路径 save 前 setId——会被判 detached 走 merge） */
    public void setId(UUID v) { this.id = v; }
    public UUID getUploaderId() { return uploaderId; }
    public void setUploaderId(UUID v) { this.uploaderId = v; }
    public String getBucket() { return bucket; }
    public void setBucket(String v) { this.bucket = v; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String v) { this.objectKey = v; }
    public String getSha256() { return sha256; }
    public void setSha256(String v) { this.sha256 = v; }
    public long getSize() { return size; }
    public void setSize(long v) { this.size = v; }
    public String getMime() { return mime; }
    public void setMime(String v) { this.mime = v; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String v) { this.originalName = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getCreatedAt() { return createdAt; }

    /** REST 视图（字段级契约，presign/complete/download-url 共用投影） */
    public java.util.Map<String, Object> toView() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("fileId", id);
        m.put("bucket", bucket);
        m.put("objectKey", objectKey);
        m.put("fileName", originalName);
        m.put("mime", mime);
        m.put("size", size);
        m.put("sha256", sha256);
        m.put("status", status);
        m.put("uploaderId", uploaderId);
        m.put("createdAt", createdAt);
        return m;
    }
}
