package cn.teamone.prd.repo;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorkItemRepository 需求拆解任务计数 SQL 契约测试（挂账收口：GET /work-items 列表
 * type=requirement 行 taskCount/taskDoneCount 投影；无真库、反射读取 @Query 钉死口径）：
 * children 按 parent_id 且 type ∈ task/test_task/defect；done 集与
 * WorkItem.doneStatusesOf 字节级一致（task done·closed / test_task passed /
 * defect 已修复·回归通过·已关闭）；一次 IN 聚合 GROUP BY parent_id（列表 size≤200）防 N+1。
 */
class WorkItemRepositoryChildStatsSqlContractTest {

    private static String sqlOf() throws Exception {
        Query q = WorkItemRepository.class
                .getMethod("childStatsByParentIds", Collection.class)
                .getAnnotation(Query.class);
        return q == null ? "" : q.value();
    }

    @Test
    void childStatsAggregationContract() throws Exception {
        String sql = sqlOf();
        // 子行定位：一批 parent_id 单次 IN 聚合（防 N+1），禁止逐父查询
        assertTrue(sql.contains("parent_id IN (:ids)"), "必须一次 IN 聚合（列表 size≤200 防 N+1）");
        assertTrue(sql.contains("GROUP BY parent_id"), "必须按 parent_id 分组");
        // 子类型口径：task/test_task/defect（requirement 不入子任务桶）
        assertTrue(sql.contains("type IN ('task','test_task','defect')"),
                "子类型必须限定 task/test_task/defect");
        // done 口径与 WorkItem.doneStatusesOf 字节级一致
        assertTrue(sql.contains("(type = 'task' AND status IN ('done','closed'))"),
                "task done 集必须 done·closed");
        assertTrue(sql.contains("(type = 'test_task' AND status = 'passed')"),
                "test_task done 集必须 passed");
        assertTrue(sql.contains("(type = 'defect' AND status IN ('已修复','回归通过','已关闭'))"),
                "defect done 集必须 已修复·回归通过·已关闭");
        // 接口投影按列别名绑定，别名缺失即整列丢失
        for (String col : new String[]{"\"parentId\"", "\"taskCount\"", "\"taskDoneCount\""}) {
            assertTrue(sql.contains(col), "投影别名缺失 " + col);
        }
    }
}
