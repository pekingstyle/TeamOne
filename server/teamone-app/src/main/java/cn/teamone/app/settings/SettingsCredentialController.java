package cn.teamone.app.settings;

import cn.teamone.eng.infra.git.RemoteRepoProbe;
import cn.teamone.platform.audit.AuditService;
import cn.teamone.platform.app.SettingService;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.shared.auth.RequirePerm;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 凭据连通性测试端点（R-12：POST /api/v1/settings/credentials/{key}/test）。
 *
 * <p>落在 app 装配层的缘由：git 进程出口在 eng 域（RemoteRepoProbe，R8 白名单包），
 * 配置读取在 platform 域——本端点做跨域编排，platform 不反向依赖业务域（ArchUnit R2）。</p>
 *
 * <p>约定：repo 组条目（{key} 对应行）的值为仓库地址，配套令牌读 (repo, token) 秘密行
 * （已配置才携带）；其余分组一律返回「该凭据类型暂不支持自动验证」（本批仅做 Git 仓库连通）。</p>
 */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsCredentialController {

    private static final String REPO_GROUP = "repo";
    private static final String REPO_TOKEN_KEY = "token";

    private final SettingService settings;
    private final RemoteRepoProbe probe;
    private final AuditService audit;

    public SettingsCredentialController(SettingService settings, RemoteRepoProbe probe, AuditService audit) {
        this.settings = settings;
        this.probe = probe;
        this.audit = audit;
    }

    @PostMapping("/credentials/{key}/test")
    @RequirePerm(resourceType = "platform", action = "settings:edit")
    public Map<String, Object> test(@AuthenticationPrincipal AppUser me, @PathVariable String key) {
        // 非 repo 分组：明确告知本批不支持（前端按钮仅在仓库组出现，此为纵深防御）
        if (!settings.exists(REPO_GROUP, key)) {
            boolean elsewhere = settings.exists("project", key) || settings.exists("server", key)
                    || settings.exists("credential", key) || settings.exists("llm", key);
            return Map.of("supported", false, "ok", false,
                    "message", elsewhere ? "该凭据类型暂不支持自动验证" : "配置项不存在，请先保存后再测试");
        }
        String url = settings.plainValue(REPO_GROUP, key);
        if (url == null || url.isBlank()) {
            return Map.of("supported", true, "ok", false, "message", "仓库地址为空，请先填写并保存");
        }
        // 可选令牌：秘密行已配置才解密携带（解密失败按业务错误上抛——密钥被换时提示重新配置）
        String token = settings.secretValue(REPO_GROUP, REPO_TOKEN_KEY);
        RemoteRepoProbe.ProbeResult result = probe.probe(url, token);
        // 审计留痕：只记坐标与结果，不记地址/令牌（避免探测目标外泄与明文落审计）
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("group", REPO_GROUP);
        detail.put("key", key);
        detail.put("ok", result.ok());
        audit.record(me.getId(), "settings.credential.test", "platform", null, detail);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("supported", true);
        body.put("ok", result.ok());
        body.put("message", result.message());
        body.put("elapsedMs", result.elapsedMs());
        return body;
    }
}
