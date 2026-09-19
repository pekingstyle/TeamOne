package cn.teamone.platform.infra;

import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 对象存储端口（platform/infra，M2-INC-2 文件两步制，05 §9.3）。
 *
 * <p>两步制语义：presign 只做本地签名计算（不连 MinIO，依赖不可用不影响造行）；
 * statObject 真连（complete 验证上传确实存在）。任何 MinIO 通信异常统一转
 * {@link ErrorCode#SRV_5030}（依赖不可用，可重试）；对象不存在（NoSuchKey）转 PLT_4040。
 * MinIO 未启动时应用照常起（构造不建连，health 不耦合——与 Valkey 同一降级纪律）。</p>
 *
 * <p>依赖方向：platform 只依赖 minio SDK + shared（ArchUnit R2 不受影响）。</p>
 */
@Component
public class MinioPort {

    private static final Logger log = LoggerFactory.getLogger(MinioPort.class);

    private final MinioClient client;
    private final String defaultBucket;

    public MinioPort(@Value("${teamone.minio.endpoint}") String endpoint,
                     @Value("${teamone.minio.access-key}") String accessKey,
                     @Value("${teamone.minio.secret-key}") String secretKey,
                     @Value("${teamone.minio.bucket:teamone-dev}") String defaultBucket) {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.defaultBucket = defaultBucket;
    }

    public String defaultBucket() {
        return defaultBucket;
    }

    /**
     * 预签名 PUT（默认 5min）：客户端持 url 直传 MinIO，服务器不经手文件体。
     * 签名是纯本地计算——MinIO 宕机/未启动时本方法不受影响（两步制第一步可先行）。
     */
    public String presignPut(String bucket, String objectKey, Duration expiry) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry((int) expiry.toSeconds(), TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            log.warn("[minio] presignPut failed: {} / {}", objectKey, e.toString());
            throw new BusinessException(ErrorCode.SRV_5030, "上传地址签发失败，请稍后重试");
        }
    }

    /**
     * 预签名 GET（默认 5min）：response-content-disposition 携带原文件名，
     * 浏览器下载还原 original_name（中文文件名 RFC 6266 URL 编码）。
     */
    public String presignGet(String bucket, String objectKey, Duration expiry, String originalName) {
        try {
            String encoded = URLEncoder.encode(
                    originalName == null ? "file" : originalName, StandardCharsets.UTF_8)
                    .replace("+", "%20");
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry((int) expiry.toSeconds(), TimeUnit.SECONDS)
                    .extraQueryParams(Map.of("response-content-disposition",
                            "attachment; filename*=UTF-8''" + encoded))
                    .build());
        } catch (Exception e) {
            log.warn("[minio] presignGet failed: {} / {}", objectKey, e.toString());
            throw new BusinessException(ErrorCode.SRV_5030, "下载地址签发失败，请稍后重试");
        }
    }

    /**
     * 对象元数据探测（complete 验证客户端确实已 PUT）。
     * 不存在 → PLT_4040；MinIO 失联/其他错误 → SRV_5030。
     */
    public StatObjectResponse statObject(String bucket, String objectKey) {
        try {
            return client.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? "" : e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchBucket".equals(code)) {
                throw new BusinessException(ErrorCode.PLT_4040,
                        "对象不存在: " + objectKey);
            }
            log.warn("[minio] statObject error response: {} / {}", objectKey, code);
            throw new BusinessException(ErrorCode.SRV_5030, "存储服务暂不可用，请稍后重试");
        } catch (Exception e) {
            log.warn("[minio] statObject failed: {} / {}", objectKey, e.toString());
            throw new BusinessException(ErrorCode.SRV_5030, "存储服务暂不可用，请稍后重试");
        }
    }
}
