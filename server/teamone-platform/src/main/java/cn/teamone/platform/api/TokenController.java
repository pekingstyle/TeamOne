package cn.teamone.platform.api;

import cn.teamone.platform.app.PatService;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.dto.PatDto.CreatePatRequest;
import cn.teamone.platform.dto.PatDto.CreatePatResponse;
import cn.teamone.platform.dto.PatDto.PatSummary;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 个人访问令牌（PAT）控制台端点（P0 企业治理底座 · 开发者凭据中心）。
 *
 * <p>允许已登录的合法开发人员管理属于自身的 Git CLI / 自动化访问令牌。</p>
 */
@RestController
@RequestMapping("/api/v1/tokens")
public class TokenController {

    private final PatService patService;

    public TokenController(PatService patService) {
        this.patService = patService;
    }

    /**
     * 查询当前登录用户拥有的全部 PAT 令牌（脱敏前缀列表）。
     *
     * @param me 当前登录用户
     * @return 令牌列表
     */
    @GetMapping
    public List<PatSummary> listMyTokens(@AuthenticationPrincipal AppUser me) {
        return patService.listTokens(me.getId());
    }

    /**
     * 创建新的个人访问令牌（响应中仅此一次返回完整明文 rawToken）。
     *
     * @param me 当前登录用户
     * @param req 创建参数
     * @return 包含明文的响应实体
     */
    @PostMapping
    public CreatePatResponse createToken(@AuthenticationPrincipal AppUser me,
                                         @RequestBody CreatePatRequest req) {
        return patService.createToken(me.getId(), req);
    }

    /**
     * 撤销/销毁指定个人访问令牌。
     *
     * @param me 当前登录用户
     * @param id 目标令牌 ID
     * @return 结果确认
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> revokeToken(@AuthenticationPrincipal AppUser me,
                                           @PathVariable UUID id) {
        patService.revokeToken(me.getId(), id);
        return Map.of("ok", true, "message", "令牌已安全吊销");
    }
}
