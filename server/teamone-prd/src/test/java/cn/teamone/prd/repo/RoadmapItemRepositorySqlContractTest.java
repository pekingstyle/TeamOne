package cn.teamone.prd.repo;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RoadmapItemRepository 聚合 SQL 契约测试（R-5 AC③ · work_item 直连口径，无真库、
 * 反射读取 @Query 钉死口径，防止后续改动回归）：
 * 条目行 JOIN 维持条目路径（计数零回归）；直挂工作项以 UNION ALL 哨兵行（id=NULL）单独聚合、
 * 不摊入条目行（防重复计数）；一个工作项同挂 roadmap_item_id 与 goal_id 时只走条目路径计一次。
 */
class RoadmapItemRepositorySqlContractTest {

    private static String sqlOf(String method, Class<?>... params) throws Exception {
        Query q = RoadmapItemRepository.class.getMethod(method, params).getAnnotation(Query.class);
        return q == null ? "" : q.value();
    }

    private static int countOccurrences(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    /** 三条聚合查询共有的直连口径契约（哨兵行 + 去重守卫 + 条目路径 JOIN + 完成口径） */
    private void assertDirectLinkContract(String sql, String method) {
        // 条目行 JOIN 维持条目路径（现有演示/真实数据计数零回归）
        assertTrue(sql.contains("LEFT JOIN prd.work_item wi ON wi.roadmap_item_id = ri.id"),
                method + "：条目行必须走条目路径 JOIN");
        // 直挂哨兵行以 UNION ALL 追加（不摊入条目行——LEFT JOIN OR 写法会重复计数，已裁决废弃）
        assertTrue(sql.contains("UNION ALL"), method + "：必须有哨兵行 UNION ALL");
        assertTrue(sql.contains("直挂目标的工作项"), method + "：哨兵行名固定");
        assertEquals(1, countOccurrences(sql, "roadmap_item_id IS NULL"),
                method + "：直挂守卫 roadmap_item_id IS NULL 恰好出现一次（哨兵分支）");
        // 双路径去重：条目 JOIN 不得携带 OR 直挂路径（同挂双 id 的工作项只经条目行计一次）
        assertFalse(sql.contains("wi.goal_id = ri.goal_id"),
                method + "：条目 JOIN 禁止 OR 直挂路径（重复计数回归）");
        assertFalse(sql.toUpperCase().contains("OR (WI.GOAL_ID"),
                method + "：条目 JOIN 禁止 OR 直挂路径（重复计数回归）");
        // 完成口径零回归：done 集含 'closed'/'已修复'/'回归通过'/'已关闭'/'passed'
        for (String s : new String[]{"'done'", "'closed'", "已修复", "回归通过", "已关闭", "'passed'"}) {
            assertTrue(sql.contains(s), method + "：完成口径丢失 " + s);
        }
    }

    @Test
    void rollupByGoalDirectLinkContract() throws Exception {
        String sql = sqlOf("rollupByGoal", UUID.class);
        assertDirectLinkContract(sql, "rollupByGoal");
        // 哨兵分支限定本目标 + 无条目归属
        assertTrue(sql.contains("WHERE wi.goal_id = :goalId AND wi.roadmap_item_id IS NULL"),
                "rollupByGoal：哨兵分支必须限定 goalId 且 roadmap_item_id 为空");
        // 条目行主集不丢（无工作项条目行不消失）
        assertTrue(sql.contains("FROM prd.roadmap_item ri"), "rollupByGoal：行集以 roadmap_item 为主");
        assertTrue(sql.contains("LEFT JOIN"), "rollupByGoal：必须 LEFT JOIN（条目行不因无工作项消失）");
    }

    @Test
    void rollupAllDirectLinkContract() throws Exception {
        String sql = sqlOf("rollupAll");
        assertDirectLinkContract(sql, "rollupAll");
        // 全表版哨兵行按 goal 分组（无主直挂项不进哨兵行）
        assertTrue(sql.contains("WHERE wi.goal_id IS NOT NULL AND wi.roadmap_item_id IS NULL"),
                "rollupAll：哨兵行按 goal 分组且排除无主工作项");
        assertTrue(sql.contains("GROUP BY wi.goal_id"), "rollupAll：哨兵行必须按 wi.goal_id 分组");
    }

    @Test
    void overviewByGoalDirectLinkContract() throws Exception {
        String sql = sqlOf("overviewByGoal", UUID.class);
        assertDirectLinkContract(sql, "overviewByGoal");
        // 类型计数/工时三桶在哨兵分支同口径跟随（桶名齐全）
        for (String col : new String[]{"\"doneHours\"", "\"inProgressHours\"", "\"todoHours\"",
                "\"taskCount\"", "\"taskDone\"", "\"requirementCount\"", "\"requirementDone\"",
                "\"defectCount\"", "\"defectDone\""}) {
            assertEquals(2, countOccurrences(sql, col),
                    "overviewByGoal：" + col + " 桶必须条目行/哨兵行各出现一次");
        }
        // 哨兵行无版本投影（无条目即无正向挂接版本）
        assertTrue(sql.contains("NULL::uuid AS \"releaseId\""), "overviewByGoal：哨兵行 releaseId 必须为 NULL");
        assertTrue(sql.contains("WHERE wi.goal_id = :goalId AND wi.roadmap_item_id IS NULL"),
                "overviewByGoal：哨兵分支必须限定 goalId 且 roadmap_item_id 为空");
    }
}
