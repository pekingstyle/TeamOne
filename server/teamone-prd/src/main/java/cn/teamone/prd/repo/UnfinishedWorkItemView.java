package cn.teamone.prd.repo;

/**
 * 版本未完结工作项分组视图（R-9 发布一致性硬门禁 4231 明细投影，native GROUP BY type）。
 *
 * <p>别名与 getter 一一对应（Spring Data native 接口投影按列别名绑定）。</p>
 */
public interface UnfinishedWorkItemView {

    /** 工作项类型（task/test_task/defect/requirement） */
    String getType();

    /** 该类型未完结数量 */
    long getCount();

    /** 未完结业务 key 样例（string_agg 按 key 序，逗号分隔） */
    String getKeys();
}
