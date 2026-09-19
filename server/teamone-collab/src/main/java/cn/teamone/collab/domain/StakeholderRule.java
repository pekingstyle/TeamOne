package cn.teamone.collab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 干系人自动拉人规则（collab.stakeholder_rule，V4 Flyway 种子，可运维修改；
 * 05 §6.2 规则表：requirement/release/defect/sprint/roadmap/goal × auto_topic）。
 *
 * <p>M1 现实：上游 prd 在事件 payload 里直接给出 stakeholderUserIds（数据源就近），
 * 本表为规则配置的权威存档——由运维/后续版本驱动 prd 侧拉人口径调整。</p>
 */
@Entity
@Table(name = "stakeholder_rule", schema = "collab")
@IdClass(StakeholderRule.Pk.class)
public class StakeholderRule {

    @Id
    @Column(name = "target_type", columnDefinition = "text")
    private String targetType;

    @Id
    @Column(columnDefinition = "text")
    private String relation;

    /** 拉入者清单（角色码逗号分隔，如 'reporter,fixer,test_task_assignee,release_manager'） */
    @Column(nullable = false, columnDefinition = "text")
    private String include;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public String getTargetType() { return targetType; }
    public void setTargetType(String v) { this.targetType = v; }
    public String getRelation() { return relation; }
    public void setRelation(String v) { this.relation = v; }
    public String getInclude() { return include; }
    public void setInclude(String v) { this.include = v; }
    public Instant getCreatedAt() { return createdAt; }

    /** 复合主键 (target_type, relation) */
    public static class Pk implements Serializable {
        private String targetType;
        private String relation;

        public Pk() {}
        public Pk(String targetType, String relation) {
            this.targetType = targetType;
            this.relation = relation;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(targetType, pk.targetType)
                    && Objects.equals(relation, pk.relation);
        }

        @Override
        public int hashCode() {
            return Objects.hash(targetType, relation);
        }
    }
}
