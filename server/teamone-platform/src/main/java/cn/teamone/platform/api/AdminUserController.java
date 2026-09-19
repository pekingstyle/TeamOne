package cn.teamone.platform.api;

import cn.teamone.platform.app.UserService;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.domain.AppUser.PlatformRole;
import cn.teamone.platform.domain.AppUser.Status;
import cn.teamone.platform.dto.AdminUserDto.CreateUserRequest;
import cn.teamone.platform.dto.AdminUserDto.ResetPasswordRequest;
import cn.teamone.platform.dto.AdminUserDto.UpdateStatusRequest;
import cn.teamone.platform.dto.AdminUserDto.UpdateUserRequest;
import cn.teamone.platform.dto.AdminUserDto.UserSummary;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 系统管理员用户全生命周期管控端点（P0 企业治理底座）。
 *
 * <p>权限规约：仅具备 {@code OWNER} 或 {@code ADMIN} 平台角色的账号方可访问本控制器端点，
 * 普通成员访问直接按 {@link ErrorCode#PLT_4030} 拦截拒绝。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final UserService userService;

    public AdminUserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 分页查询用户列表。
     *
     * @param me 当前登录用户
     * @param query 搜索关键词
     * @param status 状态过滤
     * @param role 角色过滤
     * @param pageable 分页参数
     * @return 分页结果
     */
    @GetMapping
    public Page<UserSummary> listUsers(@AuthenticationPrincipal AppUser me,
                                       @RequestParam(required = false) String query,
                                       @RequestParam(required = false) Status status,
                                       @RequestParam(required = false) PlatformRole role,
                                       @PageableDefault(size = 20) Pageable pageable) {
        requireAdmin(me);
        return userService.listUsers(query, status, role, pageable);
    }

    /**
     * 获取单个用户详情。
     *
     * @param me 当前登录用户
     * @param id 目标用户 ID
     * @return 用户详情
     */
    @GetMapping("/{id}")
    public UserSummary getUser(@AuthenticationPrincipal AppUser me, @PathVariable UUID id) {
        requireAdmin(me);
        return userService.getUser(id);
    }

    /**
     * 管理员创建新用户。
     *
     * @param me 当前登录用户
     * @param req 创建参数
     * @return 新建用户信息
     */
    @PostMapping
    public UserSummary createUser(@AuthenticationPrincipal AppUser me, @RequestBody CreateUserRequest req) {
        requireAdmin(me);
        return userService.createUser(req, me.getId());
    }

    /**
     * 修改用户资料与角色。
     *
     * @param me 当前登录用户
     * @param id 目标用户 ID
     * @param req 修改参数
     * @return 更新后用户信息
     */
    @PutMapping("/{id}")
    public UserSummary updateUser(@AuthenticationPrincipal AppUser me,
                                  @PathVariable UUID id,
                                  @RequestBody UpdateUserRequest req) {
        requireAdmin(me);
        return userService.updateUser(id, req, me.getId());
    }

    /**
     * 启用或停用用户账号。
     *
     * @param me 当前登录用户
     * @param id 目标用户 ID
     * @param req 状态变更参数
     * @return 更新后用户信息
     */
    @PutMapping("/{id}/status")
    public UserSummary updateStatus(@AuthenticationPrincipal AppUser me,
                                    @PathVariable UUID id,
                                    @RequestBody UpdateStatusRequest req) {
        requireAdmin(me);
        return userService.updateStatus(id, req, me.getId());
    }

    /**
     * 重置用户密码。
     *
     * @param me 当前登录用户
     * @param id 目标用户 ID
     * @param req 新密码请求
     * @return 操作结果
     */
    @PutMapping("/{id}/password")
    public Map<String, Object> resetPassword(@AuthenticationPrincipal AppUser me,
                                             @PathVariable UUID id,
                                             @RequestBody ResetPasswordRequest req) {
        requireAdmin(me);
        userService.resetPassword(id, req.newPassword(), me.getId());
        return Map.of("ok", true, "message", "密码重置成功");
    }

    /** 检查当前登录用户是否具有系统管理员权限 */
    private static void requireAdmin(AppUser me) {
        if (me == null || (me.getPlatformRole() != PlatformRole.ADMIN && me.getPlatformRole() != PlatformRole.OWNER)) {
            throw new BusinessException(ErrorCode.PLT_4030, "只有系统管理员或所有者可以管理用户");
        }
    }
}
