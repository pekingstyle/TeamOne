package cn.teamone.eng.app;

import cn.teamone.eng.domain.BranchRule;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.dto.BranchRuleItem;
import cn.teamone.eng.dto.SaveBranchRulesRequest;
import cn.teamone.eng.repo.BranchRuleRepository;
import cn.teamone.shared.api.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BranchRuleService 纯逻辑单测（无 Spring）：glob 匹配口径、PUT 请求校验、
 * 生效点一/二（建分支名匹配 / MR 目标一致）判定。
 *
 * @author Ivan Yang, 2026-09-14
 */
class BranchRuleServiceTest {

    private static Repository repo(UUID id) {
        Repository r = new Repository();
        r.setId(id);
        r.setName("teamone");
        r.setRepoPath("teamone/teamone.git");
        r.setDefaultBranch("main");
        return r;
    }

    private static BranchRule rule(String type, String pattern, String mergeTarget) {
        BranchRule r = new BranchRule();
        r.setRepoId(UUID.randomUUID());
        r.setBranchType(type);
        r.setNamePattern(pattern);
        r.setMergeTarget(mergeTarget);
        return r;
    }

    private static BranchRuleService serviceWith(List<BranchRule> rules) {
        BranchRuleRepository repo = mock(BranchRuleRepository.class);
        when(repo.findByRepoId(any())).thenReturn(rules);
        return new BranchRuleService(repo, org.mockito.Mockito.mock(cn.teamone.eng.repo.RepositoryRepository.class));
    }

    // ---------- glob 匹配 ----------

    @Test
    void matchesGlob_starSegment_matchesNestedAndCaseInsensitive() {
        assertTrue(BranchRuleService.matchesGlob("feature/login-page", "feature/*"));
        // 忽略大小写
        assertTrue(BranchRuleService.matchesGlob("FEATURE/LOGIN-PAGE", "feature/*"));
        // 与 branch_protection 同口径：'*' 跨段（嵌套命名不误伤 422）
        assertTrue(BranchRuleService.matchesGlob("feature/auth/login-page", "feature/*"));
        // 精确模式无通配 = 全等
        assertTrue(BranchRuleService.matchesGlob("main", "main"));
        assertFalse(BranchRuleService.matchesGlob("mainx", "main"));
        // 前缀不符不命中
        assertFalse(BranchRuleService.matchesGlob("hotfix/urgent", "feature/*"));
    }

    @Test
    void matchesGlob_nullBlankAndMultiStar_safe() {
        assertFalse(BranchRuleService.matchesGlob(null, "feature/*"));
        assertFalse(BranchRuleService.matchesGlob("  ", "feature/*"));
        assertFalse(BranchRuleService.matchesGlob("feature/x", null));
        assertFalse(BranchRuleService.matchesGlob("feature/x", "  "));
        // 多 '*' 非法（保存已拦截），匹配按不命中兜底
        assertFalse(BranchRuleService.matchesGlob("a/b/c", "a/*/c*"));
    }

    // ---------- PUT 请求校验 ----------

    @Test
    void validateRequest_acceptsLegalRules() {
        List<BranchRuleItem> items = BranchRuleService.validateRequest(new SaveBranchRulesRequest("gitflow",
                List.of(new BranchRuleItem("feature", "feature/*", "develop", "develop", false, false, null),
                        new BranchRuleItem("MAIN", "main", null, null, false, false, ""))));
        assertEquals(2, items.size());
    }

    @Test
    void validateRequest_rejectsIllegalInput() {
        // null 请求体
        assertThrows(BusinessException.class, () -> BranchRuleService.validateRequest(null));
        // model 非法
        assertTrue(assertThrows(BusinessException.class, () -> BranchRuleService.validateRequest(
                new SaveBranchRulesRequest("trunk-based", List.of()))).getMessage().contains("model"));
        // branchType 非法
        assertTrue(assertThrows(BusinessException.class, () -> BranchRuleService.validateRequest(
                new SaveBranchRulesRequest("custom",
                        List.of(new BranchRuleItem("trunk", "trunk", null, null, false, false, null)))))
                .getMessage().contains("branchType"));
        // namePattern 空
        assertTrue(assertThrows(BusinessException.class, () -> BranchRuleService.validateRequest(
                new SaveBranchRulesRequest("custom",
                        List.of(new BranchRuleItem("feature", "  ", null, null, false, false, null)))))
                .getMessage().contains("namePattern"));
        // namePattern 多个 '*'
        assertTrue(assertThrows(BusinessException.class, () -> BranchRuleService.validateRequest(
                new SaveBranchRulesRequest("custom",
                        List.of(new BranchRuleItem("feature", "feature/*/x*", null, null, false, false, null)))))
                .getMessage().contains("'"));
        // namePattern 含白名单外字符（防正则元字符注入语义）
        assertTrue(assertThrows(BusinessException.class, () -> BranchRuleService.validateRequest(
                new SaveBranchRulesRequest("custom",
                        List.of(new BranchRuleItem("feature", "feature/(.*)", null, null, false, false, null)))))
                .getMessage().contains("namePattern"));
        // branchType 重复
        assertTrue(assertThrows(BusinessException.class, () -> BranchRuleService.validateRequest(
                new SaveBranchRulesRequest("custom",
                        List.of(new BranchRuleItem("feature", "feature/*", null, null, false, false, null),
                                new BranchRuleItem("FEATURE", "feature/x", null, null, false, false, null)))))
                .getMessage().contains("重复"));
    }

    // ---------- 生效点一：建分支 ----------

    @Test
    void assertBranchNameAllowed_zeroRules_noRestriction() {
        BranchRuleService svc = serviceWith(List.of());
        assertDoesNotThrow(() -> svc.assertBranchNameAllowed(repo(UUID.randomUUID()), "anything/自由命名"));
    }

    @Test
    void assertBranchNameAllowed_unmatched_throws422WithAllowedPrefixes() {
        BranchRuleService svc = serviceWith(List.of(
                rule("feature", "feature/*", "develop"),
                rule("fix", "fix/*", "develop"),
                rule("poc", "poc/*", null),
                rule("release", "release/*", "main"),
                rule("hotfix", "hotfix/*", "main")));
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.assertBranchNameAllowed(repo(UUID.randomUUID()), "misc/random-branch"));
        assertEquals(422, e.errorCode().httpStatus());
        assertTrue(e.getMessage().contains("分支名须匹配"));
        assertTrue(e.getMessage().contains("feature/*"));
    }

    @Test
    void assertBranchNameAllowed_matchedPasses() {
        BranchRuleService svc = serviceWith(List.of(rule("feature", "feature/*", "develop")));
        assertDoesNotThrow(() -> svc.assertBranchNameAllowed(repo(UUID.randomUUID()), "Feature/UA-12"));
    }

    // ---------- 生效点二：MR 目标 ----------

    @Test
    void assertMergeTargetAllowed_ruleTargetDiffers_throwsWithPolicyMessage() {
        BranchRuleService svc = serviceWith(List.of(rule("feature", "feature/*", "develop")));
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.assertMergeTargetAllowed(repo(UUID.randomUUID()), "feature/ua-12", "main"));
        assertEquals(422, e.errorCode().httpStatus());
        assertTrue(e.getMessage().contains("源分支 feature/ua-12 按分支策略应合入 develop"));
    }

    @Test
    void assertMergeTargetAllowed_sameTargetOrNoTargetOrMiss_passes() {
        // 目标一致
        BranchRuleService svc = serviceWith(List.of(rule("feature", "feature/*", "develop")));
        assertDoesNotThrow(() -> svc.assertMergeTargetAllowed(repo(UUID.randomUUID()), "feature/ua-12", "develop"));
        // 规则未设 merge_target（poc）不约束
        BranchRuleService svcPoc = serviceWith(List.of(rule("poc", "poc/*", null)));
        assertDoesNotThrow(() -> svcPoc.assertMergeTargetAllowed(repo(UUID.randomUUID()), "poc/spike", "main"));
        // 源分支未命中任何规则
        BranchRuleService svcFeat = serviceWith(List.of(rule("feature", "feature/*", "develop")));
        assertDoesNotThrow(() -> svcFeat.assertMergeTargetAllowed(repo(UUID.randomUUID()), "release/1.2", "main"));
    }

    // ---------- 生效点三：合并后自动删源分支（⑥h 批） ----------

    @Test
    void shouldAutoDeleteAfterMerge_flagTrueAndHit_returnsTrue() {
        BranchRule autoDel = rule("feature", "feature/*", "develop");
        autoDel.setAutoDeleteAfterMerge(true);
        BranchRuleService svc = serviceWith(List.of(autoDel));
        assertTrue(svc.shouldAutoDeleteAfterMerge(repo(UUID.randomUUID()), "feature/ua-12"));
        // 大小写不敏感命中（与 glob 口径一致）
        assertTrue(svc.shouldAutoDeleteAfterMerge(repo(UUID.randomUUID()), "FEATURE/UA-12"));
    }

    @Test
    void shouldAutoDeleteAfterMerge_flagFalseOrMiss_returnsFalse() {
        // 命中但未开 autoDeleteAfterMerge（GitFlow 模板里 feature 默认 false）
        BranchRuleService svc = serviceWith(List.of(rule("feature", "feature/*", "develop")));
        assertFalse(svc.shouldAutoDeleteAfterMerge(repo(UUID.randomUUID()), "feature/ua-12"));
        // 开了但源分支未命中该规则
        BranchRule poc = rule("poc", "poc/*", null);
        poc.setAutoDeleteAfterMerge(true);
        BranchRuleService svcPoc = serviceWith(List.of(poc));
        assertFalse(svcPoc.shouldAutoDeleteAfterMerge(repo(UUID.randomUUID()), "feature/ua-12"));
        // 零规则仓库不删
        assertFalse(serviceWith(List.of()).shouldAutoDeleteAfterMerge(repo(UUID.randomUUID()), "feature/x"));
    }

    // ---------- GitFlow 模板种子（Seeder 幂等性支撑） ----------

    @Test
    void gitFlowTemplate_sevenRules_pocAllowsDirectPush() {
        List<BranchRule> tpl = cn.teamone.eng.seed.BranchRuleSeeder.gitFlowTemplate(UUID.randomUUID(), Instant.now());
        assertEquals(7, tpl.size());
        BranchRule poc = tpl.stream().filter(r -> "poc".equals(r.getBranchType())).findFirst().orElseThrow();
        assertTrue(poc.isAllowDirectPush());
        assertNull(poc.getMergeTarget());
        BranchRule hotfix = tpl.stream().filter(r -> "hotfix".equals(r.getBranchType())).findFirst().orElseThrow();
        assertTrue(hotfix.getDescription().contains("develop"));
    }
}
