package cn.teamone.eng.app;

import java.util.UUID;

import org.springframework.stereotype.Component;

import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.repo.RepositoryRepository;
import cn.teamone.platform.authz.RepoVisibilityPort;

/**
 * 仓库可见性解析端口实现（eng → platform 依赖方向合法，ArchUnit R2/R5）。
 *
 * <p>platform 的 {@code RepoAclService} 判定链第 4 步（visibility 只读兜底）经本实现读取
 * eng.repository.visibility——platform 不得依赖 eng（R2），故以端口接口解耦，
 * 由组合根（teamone-app）装配。</p>
 *
 * @author Ivan Yang, 2026-09-17
 */
@Component
public class RepoVisibilityResolver implements RepoVisibilityPort {

    private final RepositoryRepository repositoryRepo;

    public RepoVisibilityResolver(RepositoryRepository repositoryRepo) {
        this.repositoryRepo = repositoryRepo;
    }

    @Override
    public String visibilityOf(UUID repoId) {
        return repositoryRepo.findById(repoId).map(Repository::getVisibility).orElse(null);
    }
}
