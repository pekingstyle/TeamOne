package cn.teamone.prd.repo;

import java.util.UUID;

/**
 * 门禁阻塞缺陷清单视图（native 投影，04230 details 与 recalcGate 冗余清单共用）。
 *
 * <p>别名与 getter 一一对应（Spring Data native 接口投影按列别名绑定）。</p>
 */
public interface BlockingDefectView {

    UUID getId();

    String getKey();

    String getSeverity();

    /** 处理人（4230 清单「key severity assignee」第三列） */
    UUID getAssignee();
}
