package cn.teamone.eng.dto;

/**
 * 新建仓库请求体（⑥h 建仓批：POST /api/v1/repos）。
 *
 * <p>name 必填（白名单：字母/数字/_/-，1~64）；description/defaultBranch 可省略
 * （defaultBranch 缺省 main）。</p>
 *
 * @author Ivan Yang, 2026-09-14
 */
public record CreateRepositoryRequest(
        String name,
        String description,
        String defaultBranch
) {}
