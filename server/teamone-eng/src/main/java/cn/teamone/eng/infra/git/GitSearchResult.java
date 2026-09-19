package cn.teamone.eng.infra.git;

import java.util.List;

/**
 * Git 代码全文检索结构化结果集（V-21 / U4）。
 * <p>
 * 封装在自研裸仓中检索关键字所得的代码行列表及检索执行指标。
 * </p>
 *
 * @param repoKey      仓库路径标识（如 "teamone/teamone.git"）
 * @param ref          检索的目标版本（分支或 Commit SHA）
 * @param query        检索的关键字文本
 * @param pathPattern  限定的文件路径模式（可为空，表示全仓库范围）
 * @param matches      命中明细列表
 * @param totalMatches 匹配总条数
 * @param durationMs   底层搜索执行耗时（毫秒）
 * @author Ivan Yang, 2026-09-13
 */
public record GitSearchResult(
        String repoKey,
        String ref,
        String query,
        String pathPattern,
        List<GitSearchMatch> matches,
        int totalMatches,
        long durationMs
) {
    /**
     * 空结果工厂方法。
     */
    public static GitSearchResult empty(String repoKey, String ref, String query) {
        return new GitSearchResult(repoKey, ref, query, "", List.of(), 0, 0);
    }
}
