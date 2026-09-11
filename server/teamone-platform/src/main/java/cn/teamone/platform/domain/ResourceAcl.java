package cn.teamone.platform.domain;

import jakarta.persistence.*;
import java.util.UUID;

/** 资源级授权条目（R6）。表 platform.resource_acl */
@Entity
@Table(name = "resource_acl", schema = "platform",
       uniqueConstraints = @UniqueConstraint(columnNames = {
               "subject_type", "subject_id", "resource_type", "resource_id", "action"}))
public class ResourceAcl {

    public enum SubjectType { USER, ROLE, DEPARTMENT }

    public enum Effect { ALLOW, DENY }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, columnDefinition = "text")
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(nullable = false)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Effect effect;

    @Column(name = "granted_by")
    private UUID grantedBy;

    public UUID getId() { return id; }
    public SubjectType getSubjectType() { return subjectType; }
    public void setSubjectType(SubjectType v) { this.subjectType = v; }
    public UUID getSubjectId() { return subjectId; }
    public void setSubjectId(UUID v) { this.subjectId = v; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String v) { this.resourceType = v; }
    public UUID getResourceId() { return resourceId; }
    public void setResourceId(UUID v) { this.resourceId = v; }
    public String getAction() { return action; }
    public void setAction(String v) { this.action = v; }
    public Effect getEffect() { return effect; }
    public void setEffect(Effect v) { this.effect = v; }
    public UUID getGrantedBy() { return grantedBy; }
    public void setGrantedBy(UUID v) { this.grantedBy = v; }
}
