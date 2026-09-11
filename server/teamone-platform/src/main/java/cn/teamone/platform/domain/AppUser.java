package cn.teamone.platform.domain;

import jakarta.persistence.*;

import java.util.UUID;

/** 平台用户。表 platform.app_user（架构设计 §2.4） */
@Entity
@Table(name = "app_user", schema = "platform")
public class AppUser {

    public enum PlatformRole { OWNER, ADMIN, MEMBER }

    public enum Status { ACTIVE, DISABLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    private String title;

    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform_role", nullable = false, columnDefinition = "text")
    private PlatformRole platformRole = PlatformRole.MEMBER;

    @Column(name = "department_id")
    private UUID departmentId;

    @Column(name = "daily_capacity_hours", nullable = false)
    private int dailyCapacityHours = 8;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Status status = Status.ACTIVE;

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String v) { this.passwordHash = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public PlatformRole getPlatformRole() { return platformRole; }
    public void setPlatformRole(PlatformRole v) { this.platformRole = v; }
    public UUID getDepartmentId() { return departmentId; }
    public void setDepartmentId(UUID v) { this.departmentId = v; }
    public int getDailyCapacityHours() { return dailyCapacityHours; }
    public void setDailyCapacityHours(int v) { this.dailyCapacityHours = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; }
}
