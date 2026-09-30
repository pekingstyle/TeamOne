package cn.teamone.prd.domain;

import cn.teamone.platform.infra.AuditableListener;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 项目交付实例（⑥o 客制化双轨，docs/v2/15 §3.1）。表 prd.project。
 *
 * <p>产品是唯一的工程账本：客制化需求仍挂产品（work_item.product_id），
 * 仅以 work_item.source_project_id 记来源；项目详情=该项目的客制化需求视图。</p>
 *
 * @author Ivan Yang, 2026-09-28
 */
@Entity
@EntityListeners(AuditableListener.class)
@Table(name = "project", schema = "prd")
public class Project {

    // ---------- status（CHECK: status IN ('delivering','accepted','closed')） ----------
    public static final String STATUS_DELIVERING = "delivering"; // 交付中
    public static final String STATUS_ACCEPTED = "accepted";     // 已验收
    public static final String STATUS_CLOSED = "closed";         // 已关闭

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 项目名（如「A 集团协同平台实施」），全局唯一 */
    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "customer_name")
    private String customerName;

    @Column(nullable = false, columnDefinition = "text")
    private String status = STATUS_DELIVERING;

    /** 交付范围（一期单产品；多产品范围 M6） */
    @Column(name = "product_id", nullable = false)
    private UUID productId;

    /** 项目经理 */
    @Column(name = "manager_id")
    private UUID managerId;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "plan_accept_date")
    private LocalDate planAcceptDate;

    @Version
    @Column(nullable = false)
    private int version;

    /** ⑥r 交付分支（project/*；NULL=交付未工程化） */
    @Column(name = "branch_name")
    private String branchName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String v) { this.customerName = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public UUID getProductId() { return productId; }
    public void setProductId(UUID v) { this.productId = v; }
    public UUID getManagerId() { return managerId; }
    public void setManagerId(UUID v) { this.managerId = v; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate v) { this.startDate = v; }
    public LocalDate getPlanAcceptDate() { return planAcceptDate; }
    public void setPlanAcceptDate(LocalDate v) { this.planAcceptDate = v; }
    public int getVersion() { return version; }
    public String getBranchName() { return branchName; }
    public void setBranchName(String v) { this.branchName = v; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
