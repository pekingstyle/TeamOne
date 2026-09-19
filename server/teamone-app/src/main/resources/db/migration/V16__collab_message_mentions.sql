-- V16（B1 批 · R-10e）：collab.message 增加 @提醒成员列表（IM 未读与提醒修复）
-- 口径（12 号设计文档 R-10e）：
--   * mentions uuid[]：发送帧可选携带的被 @ 成员 id 数组；空数组=无提醒；
--   * collab.message 为按月 RANGE 分区表（V4）——分区父表直接 ALTER，
--     列定义由全部分区（含未来分区）继承，无需逐分区处理；
--   * NOT NULL DEFAULT '{}'：存量行零回填成本，读写两侧免判空。
-- 消费路径：MessageService.send 同事务写 collab.notification(kind='im.mention')，
-- 事务提交后 L3 直推 WS user:{uid} notify 帧（ImFastFanout）；L2 事件链兜底（FanoutHandler）。
ALTER TABLE collab.message ADD COLUMN mentions uuid[] NOT NULL DEFAULT '{}';
