package cn.teamone.eng.app;

import cn.teamone.eng.domain.BranchRule;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.dto.BranchRuleItem;
import cn.teamone.eng.dto.SaveBranchRulesRequest;
import cn.teamone.eng.repo.BranchRuleRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 分支规则应用服务（分支治理批：分支模型可配置 + 两处生效点校验）。
 *
 * <p>职责：
 * <ul>
 *   <li>GET/PUT/DELETE /branch-rules 的整仓替换式规则管理（PUT 事务内先删后插）；</li>
 *   <li>生效点一：建分支时分支名必须命中至少一条 name_pattern（{@link #assertBranchNameAllowed}）；</li>
 *   <li>生效点二：MR 创建时源分支命中规则且规则指定 merge_target 的，目标必须一致
 *       （{@link #assertMergeTargetAllowed}）；</li>
 *   <li>生效点三：MR 合并成功后规则 auto_delete_after_merge=true 的源分支自动删除
 *       （{@link #shouldAutoDeleteAfterMerge}，⑥h 批激活 V15 字段执行点）。</li>
 * </ul>
 * 受保护语义（最少批准/单测门禁/禁强推）仍由 {@link BranchProtectionService} 承担，二者互补。</p>
 *
 * @author Ivan Yang, 2026-09-14
 */
@Service
public class BranchRuleService {

    /** 合法分支类型（与 V15 迁移 CHECK 约束一致，应用层先行校验给出可读报错） */
    public static final Set<String> BRANCH_TYPES = Set.of(
            "main", "develop", "release", "hotfix", "feature", "fix", "poc", "other");

    /** 合法分支模型标识（PUT body.model，配置意图留痕，不落库） */
    private static final Set<String> MODELS = Set.of("gitflow", "github-flow", "custom");

    /** name_pattern 白名单：字母/数字/下划线/点/斜杠/连字符 + 至多一个 '*'（长度 ≤200） */
    private static final Pattern PATTERN_WHITELIST = Pattern.compile("[a-zA-Z0-9_./\\-*]{1,200}");

    /** 分支类型展示排序（GET 输出与 422 提示的稳定次序） */
    private static final List<String> TYPE_ORDER = List.of(
            "main", "develop", "release", "hotfix", "feature", "fix", "poc", "other");

    private final BranchRuleRepository branchRuleRepo;
    private final cn.teamone.eng.repo.RepositoryRepository repositoryRepo;

    public BranchRuleService(BranchRuleRepository branchRuleRepo,
                             cn.teamone.eng.repo.RepositoryRepository repositoryRepo) {
        this.branchRuleRepo = branchRuleRepo;
        this.repositoryRepo = repositoryRepo;
    }

    /** 按 UUID 或仓库名解析仓库（与既有 eng 服务同一口径） */
    public Repository findRepo(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "仓库标识不能为空");
        }
        try {
            UUID id = UUID.fromString(idOrName.trim());
            return repositoryRepo.findById(id)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "仓库不存在: " + idOrName));
        } catch (IllegalArgumentException e) {
            return repositoryRepo.findByName(idOrName.trim())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "仓库不存在: " + idOrName));
        }
    }

    // ---------- 规则管理端点 ----------

    /**
     * 查询仓库全部分支规则（无规则返回空列表，不抛错）。
     */
    @Transactional(readOnly = true)
    public List<BranchRuleItem> listRules(String idOrName) {
        Repository repo = findRepo(idOrName);
        return findByRepoIdSorted(repo.getId()).stream().map(BranchRuleService::toItem).toList();
    }

    /**
     * 整仓替换式保存（事务内先删后插）：branchType 合法、namePattern 非空且至多一个 '*'、
     * 同仓库不重复 branchType；model 须为 gitflow|github-flow|custom（缺省 custom）。
     *
     * @return 保存后的规则列表（与请求顺序一致）
     */
    @Transactional
    public List<BranchRuleItem> saveRules(String idOrName, SaveBranchRulesRequest req) {
        Repository repo = findRepo(idOrName);
        List<BranchRuleItem> items = validateRequest(req);
        branchRuleRepo.deleteByRepoId(repo.getId());
        List<BranchRuleItem> saved = new ArrayList<>(items.size());
        Instant now = Instant.now();
        for (BranchRuleItem item : items) {
            BranchRule rule = new BranchRule();
            rule.setRepoId(repo.getId());
            rule.setBranchType(item.branchType().trim().toLowerCase(Locale.ROOT));
            rule.setNamePattern(item.namePattern().trim());
            rule.setBaseBranch(normalizeNullable(item.baseBranch()));
            rule.setMergeTarget(normalizeNullable(item.mergeTarget()));
            rule.setAllowDirectPush(item.allowDirectPush());
            rule.setAutoDeleteAfterMerge(item.autoDeleteAfterMerge());
            rule.setDescription(normalizeNullable(item.description()));
            rule.setCreatedAt(now);
            rule.setUpdatedAt(now);
            saved.add(toItem(branchRuleRepo.save(rule)));
        }
        return saved;
    }

    /**
     * 删除仓库下指定分支类型的规则（不存在 404）。
     */
    @Transactional
    public void deleteRule(String idOrName, String branchType) {
        Repository repo = findRepo(idOrName);
        String type = normalizeBranchType(branchType);
        BranchRule rule = branchRuleRepo.findByRepoIdAndBranchType(repo.getId(), type)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "分支规则不存在: " + type));
        branchRuleRepo.delete(rule);
    }

    // ---------- 生效点校验 ----------

    /**
     * 生效点一（建分支校验）：仓库存在任一 branch_rule 时，分支名必须匹配至少一条
     * name_pattern（glob、忽略大小写）；不匹配抛 ENG_4255（422），message 列出允许前缀。
     * 仓库零规则时不设限（策略未启用的仓库保持现状）。
     */
    public void assertBranchNameAllowed(Repository repo, String branchName) {
        List<BranchRule> rules = findByRepoIdSorted(repo.getId());
        if (rules.isEmpty()) {
            return;
        }
        for (BranchRule rule : rules) {
            if (matchesGlob(branchName, rule.getNamePattern())) {
                return;
            }
        }
        String allowed = rules.stream()
                .map(BranchRule::getNamePattern)
                .collect(Collectors.joining("、"));
        throw new BusinessException(ErrorCode.ENG_4255, "分支名须匹配：" + allowed);
    }

    /**
     * 生效点二（MR 目标校验）：源分支命中某规则且该规则 merge_target 非空、
     * 与请求 targetBranch 不同 → ENG_4255（422），message 说明「源分支 X 按分支策略应合入 Y」。
     */
    public void assertMergeTargetAllowed(Repository repo, String sourceBranch, String targetBranch) {
        for (BranchRule rule : findByRepoIdSorted(repo.getId())) {
            boolean hit = matchesGlob(sourceBranch, rule.getNamePattern());
            boolean hasTarget = rule.getMergeTarget() != null && !rule.getMergeTarget().isBlank();
            boolean differs = !hasTarget || !rule.getMergeTarget().trim().equals(targetBranch);
            if (hit && hasTarget && differs) {
                throw new BusinessException(ErrorCode.ENG_4255,
                        "源分支 " + sourceBranch + " 按分支策略应合入 " + rule.getMergeTarget().trim());
            }
        }
    }

    /**
     * 生效点三（合并后自动删源分支，⑥h 自我完善批 · V15 auto_delete_after_merge 执行点）：
     * 源分支命中任一 autoDeleteAfterMerge=true 的规则即判定应删。
     *
     * <p>「源 ≠ 目标」「源不受保护」两条前置由调用方（MergeRequestService.merge）把关——
     * 本判定只回答分支策略语义，保护判定属 {@link BranchProtectionService} 职责。</p>
     */
    @Transactional(readOnly = true)
    public boolean shouldAutoDeleteAfterMerge(Repository repo, String sourceBranch) {
        for (BranchRule rule : findByRepoIdSorted(repo.getId())) {
            if (rule.isAutoDeleteAfterMerge() && matchesGlob(sourceBranch, rule.getNamePattern())) {
                return true;
            }
        }
        return false;
    }

    // ---------- glob 匹配（纯函数，包内可见供单测） ----------

    /**
     * 分支名 glob 匹配：pattern 至多一个 '*'，'*' 通配任意字符（跨段，大小写不敏感）。
     *
     * <p>口径说明：与既有 BranchProtectionService 的 'release/*' 通配实现同用 {@code .*}
     * 语义（不限于单段），避免同一平台出现两套通配行为；如 'feature/*' 同时命中
     * 'feature/login' 与 'feature/auth/login'（嵌套命名不误伤 422）。</p>
     *
     * @param branch  分支名
     * @param pattern glob 模式（如 feature/*）
     * @return 命中 true
     */
    static boolean matchesGlob(String branch, String pattern) {
        if (branch == null || branch.isBlank() || pattern == null || pattern.isBlank()) {
            return false;
        }
        String p = pattern.trim();
        String b = branch.trim();
        int star = p.indexOf('*');
        if (star < 0) {
            return p.equals(b); // 无通配 = 精确匹配
        }
        if (p.indexOf('*', star + 1) >= 0) {
            return false; // 多 '*' 非法（保存时已拦截），此处按不匹配兜底
        }
        // \Q..\E 字面量包裹 + '.*' 通配，防模式内容被当正则元字符解释
        String regex = "\\Q" + p.substring(0, star) + "\\E.*\\Q" + p.substring(star + 1) + "\\E";
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(b).matches();
    }

    // ---------- 请求校验（静态，便于纯单测） ----------

    /**
     * PUT 请求体校验与规整：model 合法、每条规则 branchType/namePattern 合法、branchType 不重复。
     * 返回原样条目列表（空 rules = 清空仓库全部规则，符合「整仓替换」语义）。
     */
    static List<BranchRuleItem> validateRequest(SaveBranchRulesRequest req) {
        if (req == null) {
            throw new BusinessException(ErrorCode.PLT_4000, "请求体不能为空");
        }
        if (req.model() != null && !req.model().isBlank() && !MODELS.contains(req.model().trim().toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.PLT_4000, "model 须为 gitflow、github-flow 或 custom");
        }
        List<BranchRuleItem> items = req.rules() == null ? List.of() : req.rules();
        Set<String> seen = new HashSet<>();
        for (BranchRuleItem item : items) {
            if (item == null) {
                throw new BusinessException(ErrorCode.PLT_4000, "规则条目不能为空");
            }
            normalizeBranchType(item.branchType());
            if (item.namePattern() == null || item.namePattern().isBlank()) {
                throw new BusinessException(ErrorCode.PLT_4000, "分支类型 " + item.branchType() + " 的 namePattern 不能为空");
            }
            String pattern = item.namePattern().trim();
            if (!PATTERN_WHITELIST.matcher(pattern).matches()) {
                throw new BusinessException(ErrorCode.PLT_4000,
                        "namePattern 非法（限字母/数字/_/./-//与 '*'，长度 ≤200）: 分支类型 " + item.branchType());
            }
            if (pattern.indexOf('*') != pattern.lastIndexOf('*')) {
                throw new BusinessException(ErrorCode.PLT_4000,
                        "namePattern 至多包含一个 '*' : 分支类型 " + item.branchType());
            }
            String type = item.branchType().trim().toLowerCase(Locale.ROOT);
            if (!seen.add(type)) {
                throw new BusinessException(ErrorCode.PLT_4000, "分支类型重复: " + type);
            }
        }
        return items;
    }

    /** 分支类型合法性（大小写归一），非法抛 PLT_4000 */
    private static String normalizeBranchType(String branchType) {
        if (branchType == null || branchType.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "branchType 不能为空");
        }
        String type = branchType.trim().toLowerCase(Locale.ROOT);
        if (!BRANCH_TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.PLT_4000,
                    "branchType 非法（main/develop/release/hotfix/feature/fix/poc/other）: " + branchType);
        }
        return type;
    }

    /** 空串归一为 NULL，避免空白占位 */
    private static String normalizeNullable(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** 按 branch_type 稳定次序排序取规则 */
    private List<BranchRule> findByRepoIdSorted(UUID repoId) {
        List<BranchRule> rules = new ArrayList<>(branchRuleRepo.findByRepoId(repoId));
        Map<String, Integer> order = new LinkedHashMap<>();
        for (int i = 0; i < TYPE_ORDER.size(); i++) {
            order.put(TYPE_ORDER.get(i), i);
        }
        rules.sort(Comparator.comparingInt(r -> order.getOrDefault(r.getBranchType(), Integer.MAX_VALUE)));
        return rules;
    }

    private static BranchRuleItem toItem(BranchRule r) {
        return new BranchRuleItem(
                r.getBranchType(),
                r.getNamePattern(),
                r.getBaseBranch(),
                r.getMergeTarget(),
                r.isAllowDirectPush(),
                r.isAutoDeleteAfterMerge(),
                r.getDescription());
    }
}
