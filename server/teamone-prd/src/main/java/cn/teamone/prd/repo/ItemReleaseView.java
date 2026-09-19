package cn.teamone.prd.repo;

import java.util.UUID;

/**
 * 工作项 → 版本 分布投影（rollup 的 releaseIds 聚合源，M2-INC-1 W1）。
 * 一行 = (roadmap_item_id, release_id) 去重对；条目自身 release_id 由服务层并集。
 */
public interface ItemReleaseView {

    /** wi.roadmap_item_id */
    UUID getItemId();

    /** wi.release_id */
    UUID getReleaseId();
}
