package cn.teamone.eng.app;

import cn.teamone.eng.domain.BranchProtection;
import cn.teamone.eng.repo.BranchProtectionRepository;
import cn.teamone.eng.repo.RepositoryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BranchProtectionService 纯逻辑单测（⑥h 口径收口批）：
 * matchesPattern 与 BranchRuleService.matchesGlob 同口径——忽略大小写、'*' 跨段；
 * 放宽方向说明：保护「多命中」只会更安全（更多分支落入保护），不存在漏放风险。
 *
 * @author Ivan Yang, 2026-09-14
 */
class BranchProtectionServiceTest {

    @Test
    void matchesPattern_caseInsensitive_exactAndWildcard() {
        // 精确匹配忽略大小写（MAIN / Main 均命中 main 保护规则）
        assertTrue(BranchProtectionService.matchesPattern("MAIN", "main"));
        assertTrue(BranchProtectionService.matchesPattern("main", "MAIN"));
        // 通配命中同样忽略大小写
        assertTrue(BranchProtectionService.matchesPattern("Release/1.0", "release/*"));
        assertTrue(BranchProtectionService.matchesPattern("release/1.0", "RELEASE/*"));
        // 精确比较原本命中
        assertTrue(BranchProtectionService.matchesPattern("main", "main"));
    }

    @Test
    void matchesPattern_starCrossesSegments_andMisses() {
        // '*' 跨段（与 matchesGlob 同口径）：嵌套命名不漏保护
        assertTrue(BranchProtectionService.matchesPattern("release/1.0/rc1", "release/*"));
        // 模式不同名不命中
        assertFalse(BranchProtectionService.matchesPattern("feature/x", "main"));
        assertFalse(BranchProtectionService.matchesPattern("featurex", "feature/*"));
    }

    @Test
    void matchesPattern_nullSafe() {
        assertFalse(BranchProtectionService.matchesPattern(null, "main"));
        assertFalse(BranchProtectionService.matchesPattern("main", null));
        // 空串口径与旧实现一致：调用方（findMatchingProtection）已前置拦截空白分支名
        assertFalse(BranchProtectionService.matchesPattern("main", ""));
        assertFalse(BranchProtectionService.matchesPattern("", "main"));
    }

    @Test
    void findMatchingProtection_usesCaseInsensitivePattern() {
        BranchProtectionRepository protectionRepo = mock(BranchProtectionRepository.class);
        BranchProtectionService svc = new BranchProtectionService(protectionRepo, mock(RepositoryRepository.class));
        BranchProtection rule = new BranchProtection();
        UUID repoId = UUID.randomUUID();
        rule.setRepoId(repoId);
        rule.setBranchPattern("MAIN");
        when(protectionRepo.findByRepoId(any())).thenReturn(List.of(rule));

        // 分支名大小写差异不再绕过保护（旧实现精确匹配时会漏）
        Optional<BranchProtection> hit = svc.findMatchingProtection(repoId, "main");
        assertTrue(hit.isPresent());
        // 不相关的分支不受影响
        assertTrue(svc.findMatchingProtection(repoId, "feature/x").isEmpty());
    }
}
