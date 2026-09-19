package cn.teamone.platform.domain;

import java.time.Instant;
import java.util.UUID;

import cn.teamone.platform.authz.RepoRole;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 仓库成员授权实体（docs/v2/13 §4.1 / V21）。表 platform.repo_member。
 *
 * <p>角色粒度 ACL：一仓库一主体一条（UNIQUE 约束，改角色 = UPDATE 走审计）；
 * 判定真相源在 {@code RepoAclService} 五步链，本表只是数据。
 * role 列在库内为小写（与 API 契约一致），经 {@link RoleConverter} 与 {@link RepoRole} 互转；
 * effect 同理（allow/deny 小写）；source 为大写枚举直存（DIRECT/INHERITED/GROUP）。</p>
 *
 * <p>跨域纪律：repo_id 是 eng.repository 的逻辑引用，不建 FK（沿 §4.1 惯例）；
 * GROUP 组主体为运行时派生不落行（source 枚举预留，Q2 裁决组管理 UI 不做）。</p>
 *
 * @author Ivan Yang, 2026-09-17
 */
@Entity
@Table(name = "repo_member", schema = "platform",
       uniqueConstraints = @UniqueConstraint(columnNames = {"repo_id", "subject_user_id"}))
public class RepoMember {

    /** 授权来源：DIRECT=手工授予；INHERITED=建仓系统授予（建仓人 Owner）；GROUP=组命中（运行时解析，预留） */
    public enum Source { DIRECT, INHERITED, GROUP }

    /** 生效方向：allow 正常授权；deny 封禁（保留 role 原值，解封恢复——封禁 ≠ 降级） */
    public enum Effect { ALLOW, DENY }

    /** RepoRole ↔ 库内小写线格式转换（V21 CHECK：owner/maintainer/developer/reporter） */
    @jakarta.persistence.Converter
    public static class RoleConverter implements jakarta.persistence.AttributeConverter<RepoRole, String> {
        @Override
        public String convertToDatabaseColumn(RepoRole attribute) {
            return attribute == null ? null : attribute.wire();
        }

        @Override
        public RepoRole convertToEntityAttribute(String dbData) {
            return dbData == null ? null : RepoRole.valueOf(dbData.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /** Effect ↔ 库内小写线格式转换（V21 CHECK：allow/deny） */
    @jakarta.persistence.Converter
    public static class EffectConverter implements jakarta.persistence.AttributeConverter<Effect, String> {
        @Override
        public String convertToDatabaseColumn(Effect attribute) {
            return attribute == null ? null : attribute.name().toLowerCase(java.util.Locale.ROOT);
        }

        @Override
        public Effect convertToEntityAttribute(String dbData) {
            return dbData == null ? null : Effect.valueOf(dbData.toUpperCase(java.util.Locale.ROOT));
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** eng.repository 逻辑引用（不建 FK） */
    @Column(name = "repo_id", nullable = false)
    private UUID repoId;

    /** 主体用户（platform.app_user.id）；GROUP 组命中为运行时解析不落行 */
    @Column(name = "subject_user_id", nullable = false)
    private UUID subjectUserId;

    /** 仓库四级角色（能力位判定真相，owner ⊇ maintainer ⊇ developer ⊇ reporter） */
    @Convert(converter = RoleConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private RepoRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Source source = Source.DIRECT;

    @Convert(converter = EffectConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Effect effect = Effect.ALLOW;

    /** 授予人（系统授予=操作人/建仓人；NULL 仅理论兜底） */
    @Column(name = "granted_by")
    private UUID grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getRepoId() { return repoId; }
    public void setRepoId(UUID v) { this.repoId = v; }
    public UUID getSubjectUserId() { return subjectUserId; }
    public void setSubjectUserId(UUID v) { this.subjectUserId = v; }
    public RepoRole getRole() { return role; }
    public void setRole(RepoRole v) { this.role = v; }
    public Source getSource() { return source; }
    public void setSource(Source v) { this.source = v; }
    public Effect getEffect() { return effect; }
    public void setEffect(Effect v) { this.effect = v; }
    public UUID getGrantedBy() { return grantedBy; }
    public void setGrantedBy(UUID v) { this.grantedBy = v; }
    public Instant getGrantedAt() { return grantedAt; }
    public void setGrantedAt(Instant v) { this.grantedAt = v; }
}
