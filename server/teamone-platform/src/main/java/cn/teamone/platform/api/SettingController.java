package cn.teamone.platform.api;

import cn.teamone.platform.app.SettingService;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.dto.SettingDto.GroupView;
import cn.teamone.platform.dto.SettingDto.ItemUpsert;
import cn.teamone.shared.auth.RequirePerm;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统设置端点（R-12/D6）：按组读取（秘密值掩码）与按组保存（行级乐观锁）。
 *
 * <p>权限：settings:read / settings:edit——四步短路链下仅 OWNER/ADMIN 放行，
 * 其余默认拒绝（403）；凭据连通性测试端点在 app 装配层（SettingsCredentialController，
 * 因 git 进程出口在 eng 域，platform 不反向依赖业务域）。</p>
 */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingController {

    private final SettingService settings;

    public SettingController(SettingService settings) {
        this.settings = settings;
    }

    /** 读取整组配置（秘密项仅掩码 + 已配置布尔，明文永不回显） */
    @GetMapping("/{group}")
    @RequirePerm(resourceType = "platform", action = "settings:read")
    public GroupView get(@PathVariable String group) {
        return settings.view(group);
    }

    /** 保存整组配置（body 为条目数组；行级乐观锁，冲突回 409 + currentVersion；审计不落明文） */
    @PutMapping("/{group}")
    @RequirePerm(resourceType = "platform", action = "settings:edit")
    public GroupView put(@AuthenticationPrincipal AppUser me,
                         @PathVariable String group,
                         @RequestBody List<ItemUpsert> items) {
        return settings.save(me.getId(), group, items);
    }
}
