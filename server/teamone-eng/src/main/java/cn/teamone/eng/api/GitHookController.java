package cn.teamone.eng.api;

import cn.teamone.eng.app.GitHookService;
import cn.teamone.shared.api.ApiError;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * git post-receive hook 回调端点（05 §3.3 / 07 §2.2；SecurityConfig 对本路径 permitAll，
 * 鉴权靠共享口令 {@code X-TeamOne-Hook-Token}，deploy/git-init-repo.sh 安装的 hook 发起）。
 *
 * <p>安全红线：口令常量时间比较（{@link MessageDigest#isEqual}）；校验失败 401 T1-PLT-4010
 * 不泄漏具体原因；日志一律不落 token 值。服务 token 未配置（.env 缺 TEAMONE_HOOK_TOKEN）时
 * WARN 并拒绝全部回调（fail-closed，等 W4 收口配置后再放开）。坏 JSON 由全局异常出口回
 * 400 T1-PLT-4000。仅处理 refs/heads/main，其余 ref 200 忽略——hook 失败不重试不阻塞 push。</p>
 *
 * @author Ivan Yang, 2026-09-12
 */
@RestController
@RequestMapping("/api/v1/git/hooks")
public class GitHookController {

    private static final Logger log = LoggerFactory.getLogger(GitHookController.class);

    /** hook 共享口令头（07 §2.2；与 deploy/git-init-repo.sh 安装的 hook 对齐） */
    public static final String TOKEN_HEADER = "X-TeamOne-Hook-Token";

    /** hook 只对 main 分支回调（脚本侧已过滤，服务端再守一层） */
    private static final String MAIN_REF = "refs/heads/main";

    private final GitHookService service;
    private final String hookToken;

    public GitHookController(GitHookService service,
            @Value("${teamone.git.hook-token:}") String hookToken) {
        this.service = service;
        this.hookToken = hookToken == null ? "" : hookToken.trim();
    }

    /** hook 请求体（对齐 deploy/git-init-repo.sh 所发 JSON 字段） */
    public record HookPayload(String repo, String oldRev, String newRev, String ref) {}

    @PostMapping("/post-receive")
    public ResponseEntity<Object> onPostReceive(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody HookPayload body) {

        // 1) 口令校验（先于一切业务；失败不泄漏原因）
        if (!tokenMatches(token)) {
            log.warn("[git-hook] post-receive 回调口令校验失败（token 不落日志），repo={}", body == null ? null : body.repo());
            return ResponseEntity.status(ErrorCode.PLT_4010.httpStatus())
                    .body(ApiError.of(ErrorCode.PLT_4010));
        }
        // 2) 字段完整性（hook 脚本必发四字段；缺视为不合法请求）
        if (isBlank(body.repo()) || isBlank(body.oldRev()) || isBlank(body.newRev()) || isBlank(body.ref())) {
            throw new BusinessException(ErrorCode.PLT_4000, "hook 请求缺少必填字段（repo/oldRev/newRev/ref）");
        }
        // 3) 仅 main 分支产生数据链，其余 ref 接受但忽略（200，不阻塞 push）
        if (!MAIN_REF.equals(body.ref())) {
            Map<String, Object> ignored = new LinkedHashMap<>();
            ignored.put("status", "ignored");
            ignored.put("reason", "non-main ref");
            ignored.put("ref", body.ref());
            return ResponseEntity.ok(ignored);
        }
        // 4) 数据链：logRange → refs #KEY → upsert → outbox（GitHookService 同事务）
        GitHookService.Result r = service.process(body.repo(), body.oldRev(), body.newRev(), body.ref());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "ok");
        res.put("repo", body.repo());
        res.put("ref", body.ref());
        res.put("scanned", r.scanned());
        res.put("mapped", r.mapped());
        res.put("rows", r.rows());
        return ResponseEntity.ok(res);
    }

    /** 常量时间比较；服务端未配置 token 时 fail-closed（WARN 提示配置，不落 token 值） */
    private boolean tokenMatches(String provided) {
        if (hookToken.isEmpty()) {
            log.warn("[git-hook] TEAMONE_HOOK_TOKEN 未配置，拒绝全部 hook 回调（请在 deploy/.env 配置后重启）");
            return false;
        }
        if (provided == null || provided.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(hookToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
