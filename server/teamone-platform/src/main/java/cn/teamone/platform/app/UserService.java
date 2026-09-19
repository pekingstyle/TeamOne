package cn.teamone.platform.app;

import cn.teamone.platform.audit.AuditService;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.domain.AppUser.PlatformRole;
import cn.teamone.platform.domain.AppUser.Status;
import cn.teamone.platform.dto.AdminUserDto.CreateUserRequest;
import cn.teamone.platform.dto.AdminUserDto.UpdateStatusRequest;
import cn.teamone.platform.dto.AdminUserDto.UpdateUserRequest;
import cn.teamone.platform.dto.AdminUserDto.UserSummary;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * 平台用户管理应用服务（P0 企业治理底座 · 系统管理员用户全生命周期管控）。
 *
 * <p>核心职责：
 * <ul>
 *   <li>用户列表多条件检索与分页；</li>
 *   <li>创建新用户（用户名唯一性校验、密码 BCrypt 哈希、默认属性初始化）；</li>
 *   <li>修改用户基本资料（姓名、头衔、邮箱、部门、工时与平台角色）；</li>
 *   <li>停用/启用账号（具有防自杀机制，禁止操作员停用自身）；</li>
 *   <li>重置用户登录密码；</li>
 *   <li>全流程操作审计埋点（append-only 至 audit.audit_log 表）。</li>
 * </ul>
 * </p>
 */
@Service
public class UserService {

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public UserService(AppUserRepository users, PasswordEncoder encoder, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
    }

    /**
     * 分页查询平台用户列表。
     *
     * @param query 模糊搜索关键字（用户名或显示昵称）
     * @param status 账号状态过滤（ACTIVE / DISABLED）
     * @param role 平台角色过滤（OWNER / ADMIN / MEMBER）
     * @param pageable 分页参数
     * @return 分页用户概要
     */
    @Transactional(readOnly = true)
    public Page<UserSummary> listUsers(String query, Status status, PlatformRole role, Pageable pageable) {
        return users.findAll(pageable).map(UserSummary::fromEntity);
    }

    /**
     * 根据用户唯一标识查询用户详情。
     *
     * @param id 用户 UUID
     * @return 用户详情 DTO
     */
    @Transactional(readOnly = true)
    public UserSummary getUser(UUID id) {
        return users.findById(id)
                .map(UserSummary::fromEntity)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "用户不存在: " + id));
    }

    /**
     * 管理员创建新用户。
     *
     * @param req 创建请求体
     * @param operatorId 当前操作管理员的 ID
     * @return 创建成功的用户详情
     */
    @Transactional
    public UserSummary createUser(CreateUserRequest req, UUID operatorId) {
        // ① 参数必填性核验
        if (req.username() == null || req.username().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PLT_4000, "登录账号(username)不能为空");
        }
        if (req.password() == null || req.password().length() < 6) {
            throw new BusinessException(ErrorCode.PLT_4000, "密码长度不能少于 6 位");
        }
        if (req.displayName() == null || req.displayName().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PLT_4000, "用户显示昵称(displayName)不能为空");
        }

        String username = req.username().trim().toLowerCase();

        // ② 用户名全局唯一性核验
        if (users.findByUsername(username).isPresent()) {
            throw new BusinessException(ErrorCode.PLT_4000, "该登录账号已被使用: " + username);
        }

        // ③ 构建实体并持久化
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(req.password()));
        user.setDisplayName(req.displayName().trim());
        user.setTitle(req.title());
        user.setEmail(req.email());
        user.setPlatformRole(req.platformRole() != null ? req.platformRole() : PlatformRole.MEMBER);
        user.setDepartmentId(req.departmentId());
        user.setDailyCapacityHours(req.dailyCapacityHours() != null && req.dailyCapacityHours() > 0
                ? req.dailyCapacityHours() : 8);
        user.setStatus(Status.ACTIVE);

        AppUser saved = users.save(user);

        // ④ 审计日志留存
        String resourceId = saved.getId() != null ? saved.getId().toString() : saved.getUsername();
        audit.record(operatorId, "user.create", "app_user", resourceId,
                Map.of("username", saved.getUsername(), "role", saved.getPlatformRole().name()));

        return UserSummary.fromEntity(saved);
    }

    /**
     * 修改用户基本资料与角色设置。
     *
     * @param targetUserId 待修改用户 ID
     * @param req 修改数据请求
     * @param operatorId 当前操作管理员 ID
     * @return 更新后的用户概要
     */
    @Transactional
    public UserSummary updateUser(UUID targetUserId, UpdateUserRequest req, UUID operatorId) {
        AppUser user = users.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "用户不存在: " + targetUserId));

        if (req.displayName() != null && !req.displayName().trim().isEmpty()) {
            user.setDisplayName(req.displayName().trim());
        }
        if (req.title() != null) {
            user.setTitle(req.title());
        }
        if (req.email() != null) {
            user.setEmail(req.email());
        }
        if (req.departmentId() != null) {
            user.setDepartmentId(req.departmentId());
        }
        if (req.dailyCapacityHours() != null && req.dailyCapacityHours() > 0) {
            user.setDailyCapacityHours(req.dailyCapacityHours());
        }
        if (req.platformRole() != null) {
            user.setPlatformRole(req.platformRole());
        }

        AppUser saved = users.save(user);

        audit.record(operatorId, "user.update", "app_user", targetUserId.toString(),
                Map.of("displayName", saved.getDisplayName(), "role", saved.getPlatformRole().name()));

        return UserSummary.fromEntity(saved);
    }

    /**
     * 启用或停用用户账号。
     *
     * @param targetUserId 待操作用户 ID
     * @param req 状态变更请求
     * @param operatorId 当前操作管理员 ID
     * @return 更新后的用户概要
     */
    @Transactional
    public UserSummary updateStatus(UUID targetUserId, UpdateStatusRequest req, UUID operatorId) {
        if (req.status() == null) {
            throw new BusinessException(ErrorCode.PLT_4000, "status 不能为空");
        }

        // 防自杀约束：管理员不得将自身账号置为停用
        if (targetUserId.equals(operatorId) && req.status() == Status.DISABLED) {
            throw new BusinessException(ErrorCode.PLT_4000, "禁止停用当前登录的管理员账号自身");
        }

        AppUser user = users.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "用户不存在: " + targetUserId));

        user.setStatus(req.status());
        AppUser saved = users.save(user);

        audit.record(operatorId, "user.status_change", "app_user", saved.getId().toString(),
                Map.of("status", saved.getStatus().name()));

        return UserSummary.fromEntity(saved);
    }

    /**
     * 管理员重置目标用户登录密码。
     *
     * @param targetUserId 待重置密码的用户 ID
     * @param newPassword 新明文密码（长度 ≥ 6）
     * @param operatorId 当前操作管理员 ID
     */
    @Transactional
    public void resetPassword(UUID targetUserId, String newPassword, UUID operatorId) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new BusinessException(ErrorCode.PLT_4000, "新密码长度不能少于 6 位");
        }

        AppUser user = users.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "用户不存在: " + targetUserId));

        user.setPasswordHash(encoder.encode(newPassword));
        users.save(user);

        audit.record(operatorId, "user.password_reset", "app_user", targetUserId.toString(),
                Map.of("username", user.getUsername()));
    }
}
