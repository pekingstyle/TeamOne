package cn.teamone.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 系统设置（R-12/D6，表 platform.setting，V19）。
 *
 * <p>复合业务键 (group, key)，无代理主键——配置项天然按名寻址。秘密值纪律：
 * {@code is_secret=true} 的行明文只进 {@code value_cipher}（AES-256-GCM，
 * AAD=group+":"+key，见 SettingCryptoService），{@code value} 保持 NULL；
 * 读取一律掩码，明文永不回显。</p>
 *
 * <p>属性名刻意取 groupCode/keyName 而非列名 group/key：二者均为 HQL 关键字，
 * 派生查询（findByGroupCodeOrderByKeyNameAsc 等）用属性路径规避解析歧义。</p>
 */
@Entity
@Table(name = "setting", schema = "platform")
@IdClass(Setting.SettingPk.class)
public class Setting {

    /** 复合主键 (group, key)——@IdClass 需可序列化且实现 equals/hashCode（属性名与实体一致） */
    public static class SettingPk implements Serializable {
        private String groupCode;
        private String keyName;

        public SettingPk() { }
        public SettingPk(String groupCode, String keyName) {
            this.groupCode = groupCode;
            this.keyName = keyName;
        }

        public String getGroupCode() { return groupCode; }
        public void setGroupCode(String v) { this.groupCode = v; }
        public String getKeyName() { return keyName; }
        public void setKeyName(String v) { this.keyName = v; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SettingPk that)) return false;
            return Objects.equals(groupCode, that.groupCode) && Objects.equals(keyName, that.keyName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(groupCode, keyName);
        }
    }

    /** 分组：project=项目 / repo=仓库 / server=服务器 / credential=凭据 / llm=大模型（预留） */
    @Id
    @Column(name = "`group`", nullable = false, length = 32)
    private String groupCode;

    @Id
    @Column(name = "key", nullable = false, length = 128)
    private String keyName;

    /** 明文值（仅 is_secret=false 的行使用；秘密行恒为 NULL） */
    @Column(name = "value")
    private String value;

    /** 是否秘密项：true 时明文只落 value_cipher（AES-256-GCM），value 恒 NULL */
    @Column(name = "is_secret", nullable = false)
    private boolean secret = false;

    /** 秘密值密文（AES-256-GCM 输出 = IV(12B) || 密文+认证标签） */
    @Column(name = "value_cipher")
    private byte[] valueCipher;

    /** 最近修改人（platform.app_user.id，弱引用） */
    @Column(name = "updated_by")
    private UUID updatedBy;

    /** 行级乐观锁版本：每次成功 PUT +1，请求携带读到的旧值，不匹配回 409 */
    @Column(name = "version", nullable = false)
    private long version = 0;

    /** 最近修改时刻（应用侧赋值 now()，不用 DB default——persist 显式写列会盖掉库端默认值） */
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public String getGroupCode() { return groupCode; }
    public void setGroupCode(String v) { this.groupCode = v; }
    public String getKeyName() { return keyName; }
    public void setKeyName(String v) { this.keyName = v; }
    public String getValue() { return value; }
    public void setValue(String v) { this.value = v; }
    public boolean isSecret() { return secret; }
    public void setSecret(boolean v) { this.secret = v; }
    public byte[] getValueCipher() { return valueCipher; }
    public void setValueCipher(byte[] v) { this.valueCipher = v; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID v) { this.updatedBy = v; }
    public long getVersion() { return version; }
    public void setVersion(long v) { this.version = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
