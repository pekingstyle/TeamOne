package cn.teamone.eng.app;

import cn.teamone.eng.domain.Baseline;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.dto.BaselineResponse;
import cn.teamone.eng.dto.CreateBaselineRequest;
import cn.teamone.eng.infra.git.GitCommit;
import cn.teamone.eng.infra.git.GitPort;
import cn.teamone.eng.repo.BaselineRepository;
import cn.teamone.eng.repo.RepositoryRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 基线全流程管理应用服务（FR-v2-13 / R9 / U10；M2-INC-3 V-15 验收基准）。
 *
 * @author Ivan Yang, 2026-09-13
 */
@Service
public class BaselineService {

    private final BaselineRepository baselineRepo;
    private final RepositoryRepository repositoryRepo;
    private final GitPort gitPort;

    public BaselineService(
            BaselineRepository baselineRepo,
            RepositoryRepository repositoryRepo,
            GitPort gitPort) {
        this.baselineRepo = baselineRepo;
        this.repositoryRepo = repositoryRepo;
        this.gitPort = gitPort;
    }

    /**
     * 根据仓库 ID (UUID) 或仓库名称（如 "teamone"）解析对应的仓库实体。
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
     * 基线→所属仓库解析（⑥j-A M-b B1 服务层回退定位：无 /repos 前缀端点
     * {@code /baselines/{id}/**} 经 repo_id 列归一 repoId，§2.1「基线权限锚点 = repo_id」）。
     *
     * @param id 基线 UUID
     * @return 所属仓库 id（永不为 null）
     * @throws BusinessException 基线不存在时 404
     */
    @Transactional(readOnly = true)
    public UUID repoIdOf(UUID id) {
        return baselineRepo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "基线不存在: " + id))
                .getRepoId();
    }

    /**
     * 查询指定仓库下建立的所有质量/工程基线列表，按创建时间降序排序。
     *
     * @param repoIdOrName 仓库 ID 或仓库短名称
     * @return 基线响应 DTO 列表
     */
    @Transactional(readOnly = true)
    public List<BaselineResponse> listBaselines(String repoIdOrName) {
        Repository repo = findRepo(repoIdOrName);
        return baselineRepo.findByRepoIdOrderByCreatedAtDesc(repo.getId()).stream()
                .map(b -> toResponse(b, repo.getName()))
                .toList();
    }

    /**
     * 查询单条基线的详细信息。
     *
     * @param id 基线唯一标识 UUID
     * @return 基线响应 DTO
     * @throws BusinessException 当基线记录不存在时抛出 PLT_4040
     */
    @Transactional(readOnly = true)
    public BaselineResponse getBaseline(UUID id) {
        Baseline b = baselineRepo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "基线不存在"));
        Repository repo = repositoryRepo.findById(b.getRepoId()).orElse(null);
        return toResponse(b, repo != null ? repo.getName() : "");
    }

    /**
     * 创建一条新的工程/质量基线（初始状态为 draft 草稿）。
     * <p>
     * 支持三大基线类型（功能基线 functional / 分配基线 allocated / 产品基线 product），
     * 自动解析 targetRefOrSha 对应的 Git Commit，校验 TagRef 唯一性。
     * </p>
     *
     * @param repoIdOrName 仓库 ID 或仓库短名
     * @param req          基线创建请求体（名称、类型、TagRef、版本号、关联需求快照等）
     * @param creatorId    基线创建人用户 ID
     * @return 创建后的基线响应 DTO
     */
    @Transactional
    public BaselineResponse createBaseline(String repoIdOrName, CreateBaselineRequest req, UUID creatorId) {
        Repository repo = findRepo(repoIdOrName);
        if (req.name() == null || req.name().isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "基线名称不能为空");
        }
        if (req.type() == null || req.type().isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "基线类型不能为空 (functional / allocated / product)");
        }
        String type = req.type().trim().toLowerCase();
        if (!List.of("functional", "allocated", "product").contains(type)) {
            throw new BusinessException(ErrorCode.PLT_4000, "基线类型无效: " + req.type());
        }
        if (req.tagRef() == null || req.tagRef().isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "基线 TagRef 不能为空");
        }
        String tagRef = req.tagRef().trim();

        // 校验同一个仓库内 TagRef 不可重复
        if (baselineRepo.findByRepoIdAndTagRef(repo.getId(), tagRef).isPresent()) {
            throw new BusinessException(ErrorCode.ENG_4250, "基线 Tag 已存在: " + tagRef);
        }

        // 解析指向的 Commit SHA
        String targetRef = (req.targetRefOrSha() == null || req.targetRefOrSha().isBlank())
                ? repo.getDefaultBranch()
                : req.targetRefOrSha().trim();
        String commitSha = null;
        try {
            List<GitCommit> commits = gitPort.commits(repo.getRepoPath(), targetRef, 1, 1);
            if (!commits.isEmpty()) {
                commitSha = commits.get(0).sha();
            }
        } catch (Exception ignored) {
        }

        Baseline b = new Baseline();
        b.setRepoId(repo.getId());
        b.setName(req.name().trim());
        b.setType(type);
        b.setTagRef(tagRef);
        b.setCommitSha(commitSha);
        b.setArtifactVersion(req.artifactVersion() != null ? req.artifactVersion().trim() : null);
        b.setRequirementSnapshotId(req.requirementSnapshotId() != null ? req.requirementSnapshotId().trim() : null);
        b.setStatus("draft");
        b.setCreatedBy(creatorId != null ? creatorId : UUID.fromString("00000000-0000-0000-0000-000000000001"));

        b = baselineRepo.save(b);
        return toResponse(b, repo.getName());
    }

    /**
     * 将草稿状态的基线提交进入审核流（进入 in_review 评审中状态）。
     *
     * @param id 基线唯一标识 UUID
     * @return 状态流转后的基线响应 DTO
     */
    @Transactional
    public BaselineResponse submitBaseline(UUID id) {
        Baseline b = baselineRepo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "基线不存在"));
        if (!"draft".equalsIgnoreCase(b.getStatus())) {
            throw new BusinessException(ErrorCode.PLT_4000, "只有草稿状态基线方可提交审批");
        }
        b.setStatus("in_review");
        b = baselineRepo.save(b);
        Repository repo = repositoryRepo.findById(b.getRepoId()).orElse(null);
        return toResponse(b, repo != null ? repo.getName() : "");
    }

    /**
     * 审批指定基线（支持双人会签机制）。
     * <p>
     * 规则说明：
     * 1. 当累计独立审批人达到 2 人时，基线自动转为 "approved"（已定版冻结）；
     * 2. 定版瞬间，系统会调用 GitPort 创建 Annotated Tag 固化代码快照（不可篡改）；
     * 3. 已定版或已废止的基线禁止重复审批。
     * </p>
     *
     * @param id            基线唯一标识 UUID
     * @param approverId    当前审批人用户 ID
     * @param approverName  当前审批人显示名称
     * @param approverEmail 当前审批人电子邮箱
     * @return 审批后的基线响应 DTO
     */
    @Transactional
    public BaselineResponse approveBaseline(UUID id, UUID approverId, String approverName, String approverEmail) {
        Baseline b = baselineRepo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "基线不存在"));
        if ("approved".equalsIgnoreCase(b.getStatus())) {
            throw new BusinessException(ErrorCode.ENG_4254, "基线已定版冻结（immutable），禁止重复审批");
        }
        if ("superseded".equalsIgnoreCase(b.getStatus())) {
            throw new BusinessException(ErrorCode.PLT_4000, "基线已废止，无法审批");
        }

        UUID validApprover = approverId != null ? approverId : UUID.fromString("00000000-0000-0000-0000-000000000001");
        List<UUID> approvers = new ArrayList<>(b.getApproverIds());
        if (!approvers.contains(validApprover)) {
            approvers.add(validApprover);
            b.setApproverIds(approvers);
        }

        Repository repo = repositoryRepo.findById(b.getRepoId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "基线关联仓库不存在"));

        // 达 2 人自动定版并冻结（打 annotated tag）
        if (approvers.size() >= 2) {
            String targetRef = b.getCommitSha() != null ? b.getCommitSha() : repo.getDefaultBranch();
            String createdSha = gitPort.createTag(
                    repo.getRepoPath(),
                    b.getTagRef(),
                    targetRef,
                    "Baseline frozen: " + b.getName(),
                    approverName != null ? approverName : "TeamOne",
                    approverEmail != null ? approverEmail : "teamone@teamone.cn"
            );
            b.setCommitSha(createdSha);
            b.setStatus("approved");
            b.setApprovedAt(Instant.now());
        }

        b = baselineRepo.save(b);
        return toResponse(b, repo.getName());
    }

    /**
     * 废止旧基线并创建新基线替代（实现基线版本演进与可追溯性链条）。
     *
     * @param oldBaselineId 被废止的原基线 UUID（必须处于 approved 状态）
     * @param newReq        新基线创建参数
     * @param creatorId     创建人用户 ID
     * @return 新建的替代基线响应 DTO
     */
    @Transactional
    public BaselineResponse supersedeBaseline(UUID oldBaselineId, CreateBaselineRequest newReq, UUID creatorId) {
        Baseline oldB = baselineRepo.findById(oldBaselineId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "原基线不存在"));
        if (!"approved".equalsIgnoreCase(oldB.getStatus())) {
            throw new BusinessException(ErrorCode.PLT_4000, "只有已定版基线才能进行版本迭代废止");
        }
        Repository repo = repositoryRepo.findById(oldB.getRepoId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "仓库不存在"));

        BaselineResponse newResp = createBaseline(repo.getName(), newReq, creatorId);
        oldB.setStatus("superseded");
        oldB.setSupersededById(newResp.id());
        baselineRepo.save(oldB);

        return newResp;
    }

    /**
     * 将基线持久化实体转换为 API 响应 DTO。
     *
     * @param b        基线实体对象
     * @param repoName 所属仓库名称
     * @return 结构化的基线响应 DTO
     */
    private BaselineResponse toResponse(Baseline b, String repoName) {
        return new BaselineResponse(
                b.getId(),
                b.getRepoId(),
                repoName,
                b.getName(),
                b.getType(),
                b.getTagRef(),
                b.getCommitSha(),
                b.getArtifactVersion(),
                b.getRequirementSnapshotId(),
                b.getStatus(),
                b.getSupersededById(),
                b.getApproverIds(),
                b.getCreatedBy(),
                b.getCreatedAt(),
                b.getApprovedAt()
        );
    }
}
