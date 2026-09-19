package cn.teamone.eng.app;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GitHookService 纯逻辑单测（无 Spring）：refs #KEY 提取与仓库聚合 id 派生。
 *
 * @author Ivan Yang, 2026-09-12
 */
class GitHookServiceTest {

    @Test
    void extractKeys_subjectAndBody_dedupKeepOrder() {
        Set<String> keys = GitHookService.extractKeys(
                "fix: refs #D-88 gateway",
                "also refs #D-88 again and #REQ-12, closes #T-3");
        // 去重（subject+body 同 key 只算一次）、保序（subject 先于 body）
        assertEquals(List.of("D-88", "REQ-12", "T-3"), List.copyOf(keys));
    }

    @Test
    void extractKeys_noMatch_returnsEmpty() {
        assertTrue(GitHookService.extractKeys("chore: no refs here", null).isEmpty());
        // 大小写敏感：小写不算引用
        assertTrue(GitHookService.extractKeys("fix: refs #d-88", null).isEmpty());
        // 前缀超 4 位大写不匹配
        assertTrue(GitHookService.extractKeys("refs #ABCDE-1", null).isEmpty());
        // 缺数字段不匹配
        assertTrue(GitHookService.extractKeys("refs #D-", "see #AB-x").isEmpty());
    }

    @Test
    void repoAggregateId_deterministicPerRepoKey() {
        UUID a = GitHookService.repoAggregateId("teamone/web.git");
        assertEquals(a, GitHookService.repoAggregateId("teamone/web.git"));
        assertNotEquals(a, GitHookService.repoAggregateId("teamone/app.git"));
    }
}
