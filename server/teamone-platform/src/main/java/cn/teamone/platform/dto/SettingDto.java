package cn.teamone.platform.dto;

import java.util.List;

/**
 * 系统设置 DTO 族（R-12）。秘密值纪律：出参一律掩码（masked），明文永不回显；
 * 入参 value 仅在「设置新值」时携带，缺省表示保留已存密文/明文。
 */
public final class SettingDto {

    private SettingDto() { }

    /**
     * GET /settings/{group} 条目投影。
     *
     * @param key        配置键
     * @param value      明文值（仅非秘密项；秘密项恒 null）
     * @param masked     秘密项掩码（前4位****；未配置为 null）
     * @param secret     是否秘密项
     * @param configured 是否已配置（秘密项=密文已存在；非秘密项=值非空）
     * @param version    行级乐观锁版本（PUT 回传 updateVersion）
     * @param updatedAt  最近修改时间（ISO-8601，未修改过为 null）
     * @param updatedBy  最近修改人 id（未修改过为 null）
     */
    public record ItemView(String key, String value, String masked, boolean secret,
                           boolean configured, long version, String updatedAt, String updatedBy) { }

    /** GET /settings/{group} 响应：整组条目（按 key 升序） */
    public record GroupView(String group, List<ItemView> items) { }

    /**
     * PUT /settings/{group} 条目入参。
     *
     * @param key           配置键（必填，组内唯一）
     * @param value         新值（秘密项=新明文，将加密落库；null=保留已存值）
     * @param secret        是否秘密项（新建时确定读写形态；改值不改键）
     * @param updateVersion 客户端读到的行版本（新建传 0；不匹配回 409）
     */
    public record ItemUpsert(String key, String value, Boolean secret, Long updateVersion) { }
}
