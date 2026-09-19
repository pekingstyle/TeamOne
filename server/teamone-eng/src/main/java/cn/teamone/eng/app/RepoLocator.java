package cn.teamone.eng.app;

import java.util.UUID;

import org.springframework.stereotype.Component;

import cn.teamone.eng.domain.Repository;
import cn.teamone.eng.repo.RepositoryRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;

/**
 * 仓库定位器（⑥i-A1 M-a · 两路定位器之一）：idOrName（UUID 或仓库名）→ Repository。
 *
 * <p>供两端复用：① {@code @RequireRepoPerm} 切面（app 装配层）路径解析归一；
 * ② members 等新增 eng 端点。与 RepositoryController 内既有 findRepo 同口径
 * （UUID 先查 id，非 UUID 或未命中查 name，均未命中 404 T1-PLT-4040）。</p>
 *
 * @author Ivan Yang, 2026-09-17
 */
@Component
public class RepoLocator {

    private final RepositoryRepository repositoryRepo;

    public RepoLocator(RepositoryRepository repositoryRepo) {
        this.repositoryRepo = repositoryRepo;
    }

    /**
     * 解析仓库标识：UUID 直接按 id 查；否则按业务键 name 查；均未命中 404。
     *
     * @param idOrName 路径段（UUID 串或仓库名）
     * @return 仓库实体（永不为 null）
     */
    public Repository resolve(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "仓库标识不能为空");
        }
        String trimmed = idOrName.trim();
        try {
            UUID id = UUID.fromString(trimmed);
            return repositoryRepo.findById(id)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "仓库不存在: " + trimmed));
        } catch (IllegalArgumentException e) {
            return repositoryRepo.findByName(trimmed)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "仓库不存在: " + trimmed));
        }
    }
}
