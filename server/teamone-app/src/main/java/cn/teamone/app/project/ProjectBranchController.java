package cn.teamone.app.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import cn.teamone.eng.app.MergeRequestService;
import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.dto.CreateMrRequest;
import cn.teamone.eng.dto.MrDetailResponse;
import cn.teamone.eng.infra.git.GitBranch;
import cn.teamone.eng.infra.git.GitPort;
import cn.teamone.eng.app.RepoPermChecker;
import cn.teamone.eng.repo.MergeRequestRepository;
import cn.teamone.eng.repo.RepositoryRepository;
import cn.teamone.platform.authz.PermissionService;
import cn.teamone.platform.authz.RepoActions;
import cn.teamone.prd.domain.Project;
import cn.teamone.prd.domain.ProjectUpgrade;
import cn.teamone.prd.repo.ProjectRepository;
import cn.teamone.prd.repo.ProjectUpgradeRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.prd.api.Actor;
import cn.teamone.shared.api.ErrorCode;

/**
 * 项目交付分支端点（⑥r · docs/v2/16 §1，app 装配层聚合 prd 项目数据 + eng Git/MR 能力——
 * prd ⊥ eng 互不依赖，跨域聚合只能落在 app；ArchUnit 依赖方向合规）。
 *
 * <p>模型：交付分支 project/* = 客户实例的工程线（基于产品 main 拉出）；客制化经 feature/* MR
 * 合入交付分支；产品升级 = MR release/* → 交付分支（走既有评审/门禁，合并后惰性回写升级记录）。
 * ahead/behind 双向计数给出「客制领先量 / 产品待升级量」。</p>
 *
 * @author Ivan Yang, 2026-09-28
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectBranchController {

    private final ProjectRepository projects;
    private final ProjectUpgradeRepository upgrades;
    private final RepositoryRepository repositories;
    private final GitPort gitPort;
    private final MergeRequestService mrService;
    private final MergeRequestRepository mrRepository;
    private final PermissionService permissions;
    private final RepoPermChecker permChecker;

    public ProjectBranchController(ProjectRepository projects, ProjectUpgradeRepository upgrades,
                                   RepositoryRepository repositories, GitPort gitPort,
                                   MergeRequestService mrService, MergeRequestRepository mrRepository,
                                   PermissionService permissions, RepoPermChecker permChecker) {
        this.projects = projects;
        this.upgrades = upgrades;
        this.repositories = repositories;
        this.gitPort = gitPort;
        this.mrService = mrService;
        this.mrRepository = mrRepository;
        this.permissions = permissions;
        this.permChecker = permChecker;
    }

    /** 绑定交付分支请求（name=project/* 分支名，须已在产品仓库存在） */
    public record BindBranchRequest(String name) {}

    /** 发起升级请求（fromRef=产品侧引用，如 release/v2.5.0 / main） */
    public record UpgradeRequest(String fromRef) {}

    /** 绑定交付分支（产品须已绑仓库；分支须存在；换绑直接覆盖） */
    @PutMapping("/{id}/branch")
    @Transactional
    public Map<String, Object> bind(@PathVariable String id, @RequestBody BindBranchRequest req) {
        UUID actor = Actor.require();
        Project project = project(id);
        // QA MUST-FIX：写权限对齐 prd 项目写基线（product:edit）
        permissions.require(actor, "product", project.getProductId(), "edit");
        Repository repo = repoOf(project);
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "交付分支名不能为空");
        }
        boolean exists = gitPort.branches(repo.getRepoPath()).stream()
                .anyMatch(b -> b.name().equals(name));
        if (!exists) {
            throw new BusinessException(ErrorCode.PLT_4040, "分支不存在: " + name);
        }
        project.setBranchName(name);
        projects.save(project);
        return statusOf(project, repo);
    }

    /** 解绑交付分支（升级记录保留作历史） */
    @DeleteMapping("/{id}/branch")
    @Transactional
    public Map<String, Object> unbind(@PathVariable String id) {
        UUID actor = Actor.require();
        Project project = project(id);
        permissions.require(actor, "product", project.getProductId(), "edit");
        project.setBranchName(null);
        projects.save(project);
        return statusOf(project, repoOf(project));
    }

    /**
     * 分支状态聚合（抽屉单次拉取）：分支/存在性/ahead 客制领先量/behind 产品待升级量/
     * 基线（产品默认分支）/升级记录（requested 项惰性同步 MR 实际状态）。
     */
    @GetMapping("/{id}/branch")
    @Transactional
    public Map<String, Object> status(@PathVariable String id) {
        Actor.require();
        Project project = project(id);
        return statusOf(project, repoOf(project));
    }

    /** 发起升级 MR（fromRef → 交付分支；走既有 MR 评审/门禁，此处只立升级意图） */
    @PostMapping("/{id}/branch/upgrades")
    @Transactional
    public Map<String, Object> requestUpgrade(@PathVariable String id, @RequestBody UpgradeRequest req) {
        UUID actor = Actor.require();
        Project project = project(id);
        // QA MUST-FIX：升级=项目配置变更（product:edit）+ 在产品仓建 MR（CREATE_MR，Reporter+）
        permissions.require(actor, "product", project.getProductId(), "edit");
        Repository repo = repoOf(project);
        permChecker.require(actor, repo.getId(), RepoActions.CREATE_MR);
        if (project.getBranchName() == null || project.getBranchName().isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "请先绑定交付分支");
        }
        String fromRef = req.fromRef() == null ? "" : req.fromRef().trim();
        if (fromRef.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "fromRef 不能为空");
        }
        boolean exists = gitPort.branches(repo.getRepoPath()).stream()
                .anyMatch(b -> b.name().equals(fromRef));
        if (!exists) {
            throw new BusinessException(ErrorCode.PLT_4040, "引用分支不存在: " + fromRef);
        }
        MrDetailResponse mr = mrService.createMr(new CreateMrRequest(
                repo.getId().toString(),
                "项目升级：" + fromRef + " → " + project.getBranchName() + "（" + project.getName() + "）",
                "产品升级带入交付分支（docs/v2/16 §1）；合并前请对照「升级冲突预警」清单完成对齐。",
                fromRef, project.getBranchName(), null, null, null), actor);
        ProjectUpgrade row = new ProjectUpgrade();
        row.setProjectId(project.getId());
        row.setMrId(mr.id());
        row.setFromRef(fromRef);
        upgrades.save(row);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("mrId", mr.id());
        res.put("mrNumber", mr.number());
        res.put("fromRef", fromRef);
        res.put("targetBranch", project.getBranchName());
        return res;
    }

    // ==================== 内部 ====================

    private Project project(String id) {
        try {
            return projects.findById(UUID.fromString(id.trim()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "项目不存在: " + id));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PLT_4000, "项目 id 非法: " + id);
        }
    }

    /** 产品绑定的仓库（一产品一仓约定；未绑定 400 引导先建仓/绑产品） */
    private Repository repoOf(Project project) {
        List<Repository> repos = repositories.findByProductId(project.getProductId());
        if (repos.isEmpty()) {
            throw new BusinessException(ErrorCode.PLT_4000, "产品尚未绑定仓库，无法使用交付分支（请先建仓并设置 productId）");
        }
        return repos.get(0);
    }

    private Map<String, Object> statusOf(Project project, Repository repo) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("repoName", repo.getName());
        res.put("defaultBranch", repo.getDefaultBranch());
        String branch = project.getBranchName();
        res.put("branchName", branch);
        boolean bound = branch != null && !branch.isBlank();
        if (bound) {
            List<GitBranch> branches = gitPort.branches(repo.getRepoPath());
            boolean exists = branches.stream().anyMatch(b -> b.name().equals(branch));
            res.put("exists", exists);
            if (exists) {
                GitPort.AheadBehind ab = gitPort.aheadBehind(repo.getRepoPath(), branch, repo.getDefaultBranch());
                res.put("ahead", ab.ahead());
                res.put("behind", ab.behind());
            }
        }
        // 升级记录（倒序）+ requested 项惰性同步 MR 实际状态
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProjectUpgrade u : upgrades.findByProjectIdOrderByCreatedAtDesc(project.getId())) {
            if (ProjectUpgrade.STATUS_REQUESTED.equals(u.getStatus())) {
                mrRepository.findById(u.getMrId()).ifPresent(mr -> {
                    if ("merged".equals(mr.getStatus())) {
                        u.setStatus(ProjectUpgrade.STATUS_MERGED);
                        upgrades.save(u);
                    } else if ("closed".equals(mr.getStatus())) {
                        u.setStatus(ProjectUpgrade.STATUS_CLOSED);
                        upgrades.save(u);
                    }
                });
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", u.getId());
            row.put("mrId", u.getMrId());
            row.put("fromRef", u.getFromRef());
            row.put("status", u.getStatus());
            row.put("createdAt", u.getCreatedAt());
            rows.add(row);
        }
        res.put("upgrades", rows);
        return res;
    }
}
