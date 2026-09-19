package cn.teamone.platform.dto;

import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.domain.AppUser.PlatformRole;
import cn.teamone.platform.domain.AppUser.Status;

import java.util.UUID;

/**
 * 用户全生命周期管理数据契约（P0 企业治理底座）。
 */
public class AdminUserDto {

    /**
     * 管理员端用户详情视图。
     *
     * @param id 用户唯一标识 UUID
     * @param username 登录账号
     * @param displayName 显示昵称/真实姓名
     * @param title 职位头衔
     * @param email 电子邮箱
     * @param platformRole 平台系统角色（OWNER / ADMIN / MEMBER）
     * @param departmentId 所属部门 ID
     * @param dailyCapacityHours 每日标准产能工时（默认 8）
     * @param status 账号状态（ACTIVE 正常 / DISABLED 停用）
     */
    public record UserSummary(
            UUID id,
            String username,
            String displayName,
            String title,
            String email,
            PlatformRole platformRole,
            UUID departmentId,
            int dailyCapacityHours,
            Status status
    ) {
        public static UserSummary fromEntity(AppUser u) {
            return new UserSummary(
                    u.getId(),
                    u.getUsername(),
                    u.getDisplayName(),
                    u.getTitle(),
                    u.getEmail(),
                    u.getPlatformRole(),
                    u.getDepartmentId(),
                    u.getDailyCapacityHours(),
                    u.getStatus()
            );
        }
    }

    /**
     * 管理员创建用户请求。
     *
     * @param username 登录账号（唯一）
     * @param password 初始明文密码
     * @param displayName 显示昵称
     * @param title 职位头衔
     * @param email 电子邮箱
     * @param platformRole 平台角色（默认 MEMBER）
     * @param departmentId 所属部门 ID
     * @param dailyCapacityHours 每日工时（默认 8）
     */
    public record CreateUserRequest(
            String username,
            String password,
            String displayName,
            String title,
            String email,
            PlatformRole platformRole,
            UUID departmentId,
            Integer dailyCapacityHours
    ) {}

    /**
     * 管理员修改用户资料请求。
     *
     * @param displayName 显示昵称
     * @param title 职位头衔
     * @param email 电子邮箱
     * @param platformRole 平台角色
     * @param departmentId 所属部门 ID
     * @param dailyCapacityHours 每日工时
     */
    public record UpdateUserRequest(
            String displayName,
            String title,
            String email,
            PlatformRole platformRole,
            UUID departmentId,
            Integer dailyCapacityHours
    ) {}

    /**
     * 管理员更新用户状态请求。
     *
     * @param status 状态枚举 ACTIVE / DISABLED
     */
    public record UpdateStatusRequest(
            Status status
    ) {}

    /**
     * 管理员重置用户密码请求。
     *
     * @param newPassword 新明文密码
     */
    public record ResetPasswordRequest(
            String newPassword
    ) {}
}
