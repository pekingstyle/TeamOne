package cn.teamone.app.event;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * teamone.* 配置属性（W3 定义，③b WS 扇出 / W4 话题归档扫描器复用）。
 *
 * <p>前缀 teamone 下其余键（jwt/auth/security/cache）由既有绑定各自消化，
 * ConfigurationProperties 默认忽略未知字段，互不干扰。</p>
 */
@ConfigurationProperties(prefix = "teamone")
public class TeamoneProps {

    private final Topic topic = new Topic();
    private final Event event = new Event();
    private final Ws ws = new Ws();
    private final Im im = new Im();

    public Topic getTopic() { return topic; }
    public Event getEvent() { return event; }
    public Ws getWs() { return ws; }
    public Im getIm() { return im; }

    /** 话题生命周期：target_closed 后延迟归档缓冲分钟数（06 §3 W3：ZADD sched:topic-archive 24h） */
    public static class Topic {

        private int archiveDelayMinutes = 1440;

        public int getArchiveDelayMinutes() { return archiveDelayMinutes; }
        public void setArchiveDelayMinutes(int v) { this.archiveDelayMinutes = v; }
    }

    /** Outbox→Stream 事件骨干参数（relay/consumer 共用） */
    public static class Event {

        /** 分片数：shard = floorMod(aggregate_id.hashCode(), shards)，同聚合同分片保序 */
        private int shards = 4;

        /**
         * XREADGROUP BLOCK 毫秒数（teamone.event.block-ms，默认 500；0=永久阻塞旧语义）。
         * M1 硬编码 BLOCK 0（永久阻塞）→ 优雅停机时消费线程僵在 read 上，06 §10 清账项：
         * 有界阻塞后每 block 周期醒一次回到调度循环，stop 最多等一个 block 周期。
         * IM 延迟诊断批（2026-09-14）由 5000 降至 500：BLOCK 在数据到达时即时唤醒，
         * 该值只决定「消息恰好落在两轮消费间隙」时的最坏等待与停机汇合周期——
         * 500ms 使兜底链路（直推失败/离线补推）端到端最坏等待从 ~1s 级降至 ~350ms。
         */
        private long blockMs = 500;

        /** 消费组内本实例名；空=启动时取 UUID 短码（环境变量 TEAMONE_CONSUMER_ID 可覆写） */
        private String consumerId = "";

        public int getShards() { return shards; }
        public void setShards(int v) { this.shards = v; }
        public long getBlockMs() { return blockMs; }
        public void setBlockMs(long v) { this.blockMs = v; }
        public String getConsumerId() { return consumerId; }
        public void setConsumerId(String v) { this.consumerId = v; }
    }

    /** WS 扇出（③b）：Valkey Pub/Sub 频道名 */
    public static class Ws {

        private String fanoutChannel = "teamone:ws:fanout";

        public String getFanoutChannel() { return fanoutChannel; }
        public void setFanoutChannel(String v) { this.fanoutChannel = v; }
    }

    /**
     * IM 参数（M2-INC-2）：撤回时间窗（小时，S-6 拍板）。
     * 键 teamone.im.withdraw-window-hours（默认 24；≤0=不限窗——测试撤旧消息可配 0；
     * README 有注）。collab 侧 REST 端点因模块方向（collab 不可见 app）以同键 @Value 绑定，
     * 本类为 app 侧同源声明与运维口径入口。
     */
    public static class Im {

        private long withdrawWindowHours = 24;

        public long getWithdrawWindowHours() { return withdrawWindowHours; }
        public void setWithdrawWindowHours(long v) { this.withdrawWindowHours = v; }
    }
}
