package cn.teamone.platform.app;

import cn.teamone.platform.audit.AuditService;
import cn.teamone.platform.domain.Setting;
import cn.teamone.platform.dto.SettingDto.GroupView;
import cn.teamone.platform.dto.SettingDto.ItemUpsert;
import cn.teamone.platform.dto.SettingDto.ItemView;
import cn.teamone.platform.repo.SettingRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 系统设置应用服务（R-12/D6）：按组读取（秘密值掩码）与按组保存（行级乐观锁 + 审计）。
 *
 * <p>审计纪律：一次 PUT 落一条审计（组 + 键清单 + 秘密键清单），<b>不含任何明文/掩码值</b>；
 * 值本体只在 setting 行内（秘密项密文）。</p>
 */
@Service
public class SettingService {

    private static final Logger log = LoggerFactory.getLogger(SettingService.class);

    /** 合法分组白名单（与前端分组 tab 一致；llm 后端可用，前端置灰「规划中」） */
    public static final List<String> GROUPS = List.of("project", "repo", "server", "credential", "llm");

    /** 键白名单：字母/数字/下划线/连字符/点，1~128（与建表列宽一致），杜绝控制字符入库 */
    private static final String KEY_PATTERN = "[a-zA-Z0-9_\\-.]{1,128}";

    private final SettingRepository settings;
    private final SettingCryptoService crypto;
    private final AuditService audit;

    public SettingService(SettingRepository settings, SettingCryptoService crypto, AuditService audit) {
        this.settings = settings;
        this.crypto = crypto;
        this.audit = audit;
    }

    /** 校验分组合法性（白名单外 404，避免误打误撞建出垃圾分组） */
    public void requireGroup(String group) {
        if (group == null || !GROUPS.contains(group)) {
            throw new BusinessException(ErrorCode.PLT_4040, "未知设置分组: " + group);
        }
    }

    /** GET /settings/{group}：整组条目，秘密项仅掩码 + 已配置布尔（评审必改③） */
    @Transactional(readOnly = true)
    public GroupView view(String group) {
        requireGroup(group);
        List<ItemView> items = settings.findByGroupCodeOrderByKeyNameAsc(group).stream()
                .map(s -> toItemView(group, s))
                .toList();
        return new GroupView(group, items);
    }

    /**
     * PUT /settings/{group}：逐条乐观锁保存（版本不匹配回 409 + currentVersion），
     * 全组一次审计留痕（不落明文）。任一条冲突则整批失败（先全量校验再写，避免半提交观感）。
     */
    @Transactional
    public GroupView save(UUID operatorId, String group, List<ItemUpsert> items) {
        requireGroup(group);
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.PLT_4000, "保存内容不能为空");
        }
        // ---- 第一遍：键白名单 + 乐观锁预检（读全不做写） ----
        Map<String, Setting> loaded = new LinkedHashMap<>();
        for (ItemUpsert item : items) {
            if (item.key() == null || !item.key().matches(KEY_PATTERN)) {
                throw new BusinessException(ErrorCode.PLT_4000, "配置键不合法（限字母/数字/_-.，1~128）");
            }
            Setting row = settings.findByGroupCodeAndKeyName(group, item.key()).orElse(null);
            long expected = item.updateVersion() == null ? 0L : item.updateVersion();
            if (row == null) {
                if (expected != 0L) {
                    throw conflict(group, item.key(), 0L);
                }
            } else if (row.getVersion() != expected) {
                throw conflict(group, item.key(), row.getVersion());
            }
            loaded.put(item.key(), row);
        }
        // ---- 第二遍：落库（秘密项按 is_secret 分列；版本 +1；审计一次） ----
        List<String> secretKeys = new ArrayList<>();
        for (ItemUpsert item : items) {
            Setting row = loaded.get(item.key());
            boolean secret = item.secret() != null && item.secret();
            if (row == null) {
                row = new Setting();
                row.setGroupCode(group);
                row.setKeyName(item.key());
                row.setVersion(0L);
            }
            if (secret) {
                // 秘密项：明文只进 value_cipher（AAD 绑坐标）；value 未携带=保留已存密文
                if (item.value() != null && !item.value().isEmpty()) {
                    row.setValueCipher(crypto.encrypt(item.value(), group, item.key()));
                    row.setValue(null);
                } else if (row.getValueCipher() == null) {
                    // 新建秘密项又不带值：无意义写入，直接拒绝，避免产生「空秘密」行
                    throw new BusinessException(ErrorCode.PLT_4000, "秘密项 " + item.key() + " 缺少值");
                }
                row.setSecret(true);
                secretKeys.add(item.key());
            } else {
                // 非秘密项：明文落 value；清掉可能残留的密文列（防形态漂移）
                if (item.value() != null) {
                    row.setValue(item.value());
                }
                row.setValueCipher(null);
                row.setSecret(false);
            }
            row.setVersion(row.getVersion() + 1);
            row.setUpdatedBy(operatorId);
            row.setUpdatedAt(OffsetDateTime.now());
            settings.save(row);
        }
        // 审计：只记坐标不记值（值明文/掩码均不入审计，D6 口径）
        audit.record(operatorId, "settings.update", "platform", null,
                Map.of("group", group, "keys", loaded.keySet().stream().toList(),
                        "secretKeys", secretKeys));
        log.info("[settings] group={} updated by={} keys={} secretKeys={}",
                group, operatorId, loaded.keySet(), secretKeys);
        return view(group);
    }

    // ================= 内部读取（供凭据连通性测试等跨域调用） =================

    /** 非秘密项明文（未配置返回 null） */
    @Transactional(readOnly = true)
    public String plainValue(String group, String key) {
        return settings.findByGroupCodeAndKeyName(group, key)
                .map(s -> s.isSecret() ? null : s.getValue())
                .orElse(null);
    }

    /** 秘密项解密明文（未配置返回 null；解密失败按 crypto 的业务错误上抛） */
    @Transactional(readOnly = true)
    public String secretValue(String group, String key) {
        return settings.findByGroupCodeAndKeyName(group, key)
                .filter(Setting::isSecret)
                .filter(s -> s.getValueCipher() != null)
                .map(s -> crypto.decrypt(s.getValueCipher(), group, key))
                .orElse(null);
    }

    /** 某键是否已存在（凭据测试端点区分 404 与「暂不支持」） */
    @Transactional(readOnly = true)
    public boolean exists(String group, String key) {
        return settings.findByGroupCodeAndKeyName(group, key).isPresent();
    }

    // ================= 投影 =================

    private ItemView toItemView(String group, Setting s) {
        String updatedBy = s.getUpdatedBy() == null ? null : s.getUpdatedBy().toString();
        String updatedAt = s.getUpdatedAt() == null
                ? null : s.getUpdatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        if (s.isSecret()) {
            String masked = null;
            if (s.getValueCipher() != null) {
                // 掩码需前 4 位明文：能解则解出前缀；密钥缺失/不匹配 → 全掩码（不阻断列表展示）
                try {
                    masked = SettingCryptoService.mask(crypto.decrypt(s.getValueCipher(), group, s.getKeyName()));
                } catch (BusinessException e) {
                    masked = "****";
                }
            }
            return new ItemView(s.getKeyName(), null, masked, true, s.getValueCipher() != null,
                    s.getVersion(), updatedAt, updatedBy);
        }
        String value = s.getValue();
        return new ItemView(s.getKeyName(), value, null, false, value != null && !value.isEmpty(),
                s.getVersion(), updatedAt, updatedBy);
    }

    private static BusinessException conflict(String group, String key, long currentVersion) {
        return new BusinessException(ErrorCode.PLT_4091,
                "配置项 " + group + "/" + key + " 已被他人修改，请刷新后重试",
                List.of("currentVersion=" + currentVersion));
    }
}
