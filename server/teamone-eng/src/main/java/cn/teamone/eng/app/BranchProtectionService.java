package cn.teamone.eng.app;

import cn.teamone.eng.domain.BranchProtection;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.dto.BranchProtectionRequest;
import cn.teamone.eng.dto.BranchProtectionResponse;
import cn.teamone.eng.repo.BranchProtectionRepository;
import cn.teamone.eng.repo.RepositoryRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 分支保护规则应用服务（M2-INC-3 U8；V-15 验收基准）。
 *
 * @author Ivan Yang, 2026-09-13
 */
@Service
public class BranchProtectionService {

    private final BranchProtectionRepository protectionRepo;
    private final RepositoryRepository repositoryRepo;

    public BranchProtectionService(
            BranchProtectionRepository protectionRepo,
            RepositoryRepository repositoryRepo) {
        this.protectionRepo = protectionRepo;
        this.repositoryRepo = repositoryRepo;
    }

    /**
     * 根据仓库 ID (UUID) 或仓库名称解析对应的仓库实体。
     *
     * @param idOrName 仓库 ID 或短名称
     * @return 仓库实体 Repository
     * @throws BusinessException 当参数为空或找不到仓库时抛出 PLT_4000 / PLT_4040
     */
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

    /**
     * 获取指定仓库配置的所有分支保护规则。
     *
     * @param repoIdOrName 仓库 ID 或短名称
     * @return 分支保护规则列表
     */
    @Transactional(readOnly = true)
    public List<BranchProtectionResponse> listProtections(String repoIdOrName) {
        Repository repo = findRepo(repoIdOrName);
        return protectionRepo.findByRepoId(repo.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 保存或更新指定仓库的分支保护规则（按 repoId + branchPattern 判定是更新还是新增）。
     *
     * @param repoIdOrName 仓库 ID 或短名称
     * @param req          保护策略参数（匹配通配符、强制 MR、最少批准人数、单测卡点、禁止强推）
     * @return 保存后的保护规则响应 DTO
     */
    @Transactional
    public BranchProtectionResponse saveProtection(String repoIdOrName, BranchProtectionRequest req) {
        Repository repo = findRepo(repoIdOrName);
        if (req.branchPattern() == null || req.branchPattern().isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "分支模式不能为空");
        }
        String pattern = req.branchPattern().trim();

        BranchProtection bp = protectionRepo.findByRepoIdAndBranchPattern(repo.getId(), pattern)
                .orElseGet(() -> {
                    BranchProtection newBp = new BranchProtection();
                    newBp.setRepoId(repo.getId());
                    newBp.setBranchPattern(pattern);
                    return newBp;
                });

        bp.setRequireMr(req.requireMr());
        bp.setMinApprovals(Math.max(1, req.minApprovals()));
        bp.setRequireUnitTest(req.requireUnitTest());
        bp.setBlockForcePush(req.blockForcePush());
        bp.setUpdatedAt(Instant.now());

        bp = protectionRepo.save(bp);
        return toResponse(bp);
    }

    /**
     * 删除指定仓库下的一条分支保护规则。
     *
     * @param repoIdOrName 仓库 ID 或短名称
     * @param protectionId 规则唯一标识 UUID
     * @throws BusinessException 当规则不存在或不归属于该仓库时抛出 PLT_4040
     */
    @Transactional
    public void deleteProtection(String repoIdOrName, UUID protectionId) {
        Repository repo = findRepo(repoIdOrName);
        BranchProtection bp = protectionRepo.findById(protectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "保护规则不存在"));
        if (!bp.getRepoId().equals(repo.getId())) {
            throw new BusinessException(ErrorCode.PLT_4040, "保护规则不属于该仓库");
        }
        protectionRepo.delete(bp);
    }

    /**
     * 校验分支合并是否符合分支保护策略（U8/ProtectionPolicy）。
     * <p>
     * 若目标分支命中保护规则，会逐一检验：
     * 1. 独立评审批准人数是否达到 minApprovals 阈值；
     * 2. 是否强制要求单元测试门禁通过或豁免。
     * 任意一项不满足时均会抛出 ENG_4253 异常阻断合并。
     * </p>
     *
     * @param repoId          仓库 ID
     * @param targetBranch    合并目标分支（如 "main", "release/*"）
     * @param approvedCount   当前已同意该 MR 的有效审批人数
     * @param unitTestPassed  单测门禁是否已通过
     * @throws BusinessException 当不满足合并条件时抛出 ENG_4253
     */
    @Transactional(readOnly = true)
    public void checkMergeAllowed(UUID repoId, String targetBranch, int approvedCount, boolean unitTestPassed) {
        Optional<BranchProtection> matched = findMatchingProtection(repoId, targetBranch);
        if (matched.isEmpty()) {
            return;
        }
        BranchProtection rule = matched.get();
        if (approvedCount < rule.getMinApprovals()) {
            throw new BusinessException(
                    ErrorCode.ENG_4253,
                    String.format("分支 '%s' 受保护，需至少 %d 位评审人批准（当前已批准 %d 位）",
                            targetBranch, rule.getMinApprovals(), approvedCount)
            );
        }
        if (rule.isRequireUnitTest() && !unitTestPassed) {
            throw new BusinessException(
                    ErrorCode.ENG_4253,
                    String.format("分支 '%s' 受保护，必须通过单测门禁或完成管理员豁免", targetBranch)
            );
        }
    }

    /**
     * 查询匹配给定分支名称的最高优先级保护策略。
     *
     * @param repoId     仓库 ID
     * @param branchName 分支名称（如 "main"）
     * @return 匹配的分支保护策略 Optional 包装
     */
    public Optional<BranchProtection> findMatchingProtection(UUID repoId, String branchName) {
        if (branchName == null || branchName.isBlank()) {
            return Optional.empty();
        }
        String cleanBranch = branchName.trim();
        List<BranchProtection> rules = protectionRepo.findByRepoId(repoId);
        for (BranchProtection r : rules) {
            if (matchesPattern(cleanBranch, r.getBranchPattern())) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /**
     * 判断分支名称是否符合模式规则（支持精确匹配及通配符 '*' 匹配，如 "release/*"）。
     *
     * <p><b>口径收口（⑥h 批）</b>：匹配统一忽略大小写，对齐 {@link BranchRuleService#matchesGlob}
     * 的通配口径。放宽方向说明：保护「多命中」只会让更多分支落入保护（更安全），
     * 不会把受保护分支漏放成不保护；'*' 同样跨段（'release/*' 命中 'release/1.0/rc'）。</p>
     *
     * @param branch  具体分支名
     * @param pattern 模式规则表达式
     * @return true 若匹配成功
     */
    static boolean matchesPattern(String branch, String pattern) {
        if (branch == null || pattern == null) {
            return false;
        }
        if (pattern.equalsIgnoreCase(branch)) {
            return true;
        }
        if (pattern.contains("*")) {
            String regex = "\\Q" + pattern.replace("*", "\\E.*\\Q") + "\\E";
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(branch).matches();
        }
        return false;
    }

    /**
     * 实体转响应 DTO。
     *
     * @param bp 分支保护实体对象
     * @return 分支保护响应 DTO
     */
    private BranchProtectionResponse toResponse(BranchProtection bp) {
        return new BranchProtectionResponse(
                bp.getId(),
                bp.getRepoId(),
                bp.getBranchPattern(),
                bp.isRequireMr(),
                bp.getMinApprovals(),
                bp.isRequireUnitTest(),
                bp.isBlockForcePush(),
                bp.getCreatedAt(),
                bp.getUpdatedAt()
        );
    }
}
