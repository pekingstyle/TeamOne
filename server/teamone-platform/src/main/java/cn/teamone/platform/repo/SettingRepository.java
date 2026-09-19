package cn.teamone.platform.repo;

import cn.teamone.platform.domain.Setting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 系统设置仓库（platform.setting，复合主键 group+key，R-12/V19）。
 * 派生查询走属性名 groupCode/keyName（规避 HQL 关键字 group/key 的解析歧义）。
 */
public interface SettingRepository extends JpaRepository<Setting, Setting.SettingPk> {

    /** 整组配置（GET /settings/{group} 按组读取，组内按 key 排序展示） */
    List<Setting> findByGroupCodeOrderByKeyNameAsc(String groupCode);

    /** 单个配置项（凭据连通性测试 / 秘密值解密读取） */
    Optional<Setting> findByGroupCodeAndKeyName(String groupCode, String keyName);
}
