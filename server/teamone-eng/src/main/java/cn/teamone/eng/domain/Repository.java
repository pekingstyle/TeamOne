package cn.teamone.eng.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Git 仓库实体（07 §2.1 bare repo 映射与元数据；M2-INC-3 U1~U3）。
 *
 * <p><b>eng 零 prd 依赖</b>：product_id 与 component_id 仅为逻辑引用，不建外键。
 * {@code repo_path} 为相对 {@code TEAMONE_GIT_ROOT} 的路径（如 {@code teamone/teamone.git}）。</p>
 *
 * @author Ivan Yang, 2026-09-13
 */
@Entity
@Table(name = "repository", schema = "eng")
public class Repository {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "repo_path", nullable = false, unique = true)
    private String repoPath;

    @Column(name = "default_branch", nullable = false)
    private String defaultBranch = "main";

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "component_id")
    private UUID componentId;

    @Column(nullable = false)
    private String visibility = "INTERNAL";

    @Column(name = "ci_enabled", nullable = false)
    private boolean ciEnabled = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRepoPath() {
        return repoPath;
    }

    public void setRepoPath(String repoPath) {
        this.repoPath = repoPath;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public UUID getComponentId() {
        return componentId;
    }

    public void setComponentId(UUID componentId) {
        this.componentId = componentId;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public boolean isCiEnabled() {
        return ciEnabled;
    }

    public void setCiEnabled(boolean ciEnabled) {
        this.ciEnabled = ciEnabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
