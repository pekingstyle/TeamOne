package cn.teamone.platform.domain;

import jakarta.persistence.*;
import java.util.UUID;

/** 部门（树）。表 platform.department */
@Entity
@Table(name = "department", schema = "platform")
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "lead_user_id")
    private UUID leadUserId;

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public UUID getParentId() { return parentId; }
    public void setParentId(UUID v) { this.parentId = v; }
    public UUID getLeadUserId() { return leadUserId; }
    public void setLeadUserId(UUID v) { this.leadUserId = v; }
}
