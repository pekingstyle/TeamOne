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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 用户管理应用服务单元测试（P0 企业治理底座 · 覆盖用户生命周期各分支）。
 */
class UserServiceTest {

    private AppUserRepository userRepo;
    private PasswordEncoder encoder;
    private AuditService audit;
    private UserService service;

    private static final UUID OPERATOR_ID = UUID.randomUUID();
    private static final UUID TARGET_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepo = mock(AppUserRepository.class);
        encoder = mock(PasswordEncoder.class);
        audit = mock(AuditService.class);
        service = new UserService(userRepo, encoder, audit);

        when(encoder.encode(any())).thenReturn("hashed_secret");
    }

    /**
     * 正向测试：管理员创建新用户，密码被哈希、默认角色与工时生效、触发审计。
     */
    @Test
    void testCreateUserSuccess() {
        CreateUserRequest req = new CreateUserRequest(
                "zhangsan",
                "Password123",
                "张三",
                "高级工程师",
                "zhangsan@example.com",
                PlatformRole.MEMBER,
                null,
                8
        );

        when(userRepo.findByUsername("zhangsan")).thenReturn(Optional.empty());
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> {
            AppUser u = inv.getArgument(0);
            return u;
        });

        UserSummary summary = service.createUser(req, OPERATOR_ID);

        assertEquals("zhangsan", summary.username());
        assertEquals("张三", summary.displayName());
        assertEquals(PlatformRole.MEMBER, summary.platformRole());
        assertEquals(Status.ACTIVE, summary.status());
        assertEquals(8, summary.dailyCapacityHours());

        // 验证审计事件记录
        verify(audit).record(eq(OPERATOR_ID), eq("user.create"), eq("app_user"), any(), any());
    }

    /**
     * 反向测试：用户名重复应拦截抛出异常。
     */
    @Test
    void testCreateUserDuplicateUsernameThrows() {
        CreateUserRequest req = new CreateUserRequest(
                "admin", "123456", "管理员", null, null, PlatformRole.ADMIN, null, null
        );

        when(userRepo.findByUsername("admin")).thenReturn(Optional.of(new AppUser()));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createUser(req, OPERATOR_ID));
        assertTrue(ex.getMessage().contains("已被使用"));
    }

    /**
     * 反向测试：密码过短拦截。
     */
    @Test
    void testCreateUserShortPasswordThrows() {
        CreateUserRequest req = new CreateUserRequest(
                "lisi", "123", "李四", null, null, PlatformRole.MEMBER, null, null
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createUser(req, OPERATOR_ID));
        assertTrue(ex.getMessage().contains("密码长度不能少于"));
    }

    /**
     * 防自杀测试：管理员禁止停用自身账号。
     */
    @Test
    void testAdminCannotDisableSelf() {
        UpdateStatusRequest req = new UpdateStatusRequest(Status.DISABLED);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateStatus(OPERATOR_ID, req, OPERATOR_ID));
        assertTrue(ex.getMessage().contains("禁止停用当前登录的管理员账号自身"));
    }

    /**
     * 正常更新用户资料与平台角色。
     */
    @Test
    void testUpdateUserSuccess() {
        AppUser existing = new AppUser();
        existing.setUsername("wangwu");
        existing.setDisplayName("王五");
        existing.setPlatformRole(PlatformRole.MEMBER);

        when(userRepo.findById(TARGET_ID)).thenReturn(Optional.of(existing));
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateUserRequest req = new UpdateUserRequest("王小五", "技术总监", "wangwu@test.com", PlatformRole.ADMIN, null, 7);
        UserSummary updated = service.updateUser(TARGET_ID, req, OPERATOR_ID);

        assertEquals("王小五", updated.displayName());
        assertEquals("技术总监", updated.title());
        assertEquals(PlatformRole.ADMIN, updated.platformRole());
        assertEquals(7, updated.dailyCapacityHours());

        verify(audit).record(eq(OPERATOR_ID), eq("user.update"), eq("app_user"), any(), any());
    }

    /**
     * 密码重置测试：新密码经哈希落库并记录审计。
     */
    @Test
    void testResetPasswordSuccess() {
        AppUser user = new AppUser();
        user.setUsername("zhaoliu");

        when(userRepo.findById(TARGET_ID)).thenReturn(Optional.of(user));

        service.resetPassword(TARGET_ID, "NewPass@2026", OPERATOR_ID);

        verify(encoder).encode("NewPass@2026");
        verify(userRepo).save(user);
        verify(audit).record(eq(OPERATOR_ID), eq("user.password_reset"), eq("app_user"), any(), any());
    }
}
