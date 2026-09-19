package deploy.ws;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TeamOne 高性能 WebSocket 长连接密度与并发延迟基准压测工具（Spike S-4）
 * 
 * 纯 Java 17 标准库无侵入实现（基于 HttpClient + 异步非阻塞 WebSocket）。
 * 评测指标：
 *   1. 批量握手建连速率（Connections / Sec）
 *   2. 首帧 JWT 身份认证成功率（Auth -> Ready）
 *   3. 周期性心跳往返往还延迟分布（Min / Avg / Max / P95 / P99）
 *   4. 单长连接内存占用开销（Memory Footprint per Connection）
 *   5. 海量长连接并发下的广播吞吐与稳定性
 */
public class WsBenchmark {

    private static final AtomicInteger CONNECTED_COUNT = new AtomicInteger(0);
    private static final AtomicInteger AUTHED_COUNT = new AtomicInteger(0);
    private static final AtomicInteger ERROR_COUNT = new AtomicInteger(0);
    private static final AtomicLong FRAMES_RECEIVED = new AtomicLong(0);

    private static final List<Long> LATENCY_SAMPLES = Collections.synchronizedList(new ArrayList<>(10000));

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法: java WsBenchmark <wsUrl> <jwtToken> [targetConnections] [batchSize] [holdSeconds]");
            System.out.println("示例: java WsBenchmark ws://127.0.0.1:8080/ws eyJhbGciOi... 1000 50 15");
            System.exit(1);
        }

        String wsUrl = args[0];
        String token = args[1];
        int targetConns = args.length > 2 ? Integer.parseInt(args[2]) : 500;
        int batchSize = args.length > 3 ? Integer.parseInt(args[3]) : 50;
        int holdSeconds = args.length > 4 ? Integer.parseInt(args[4]) : 10;

        System.out.println("=================================================================");
        System.out.println("     TeamOne Spike S-4 WebSocket 高并发连接密度基准压测工具      ");
        System.out.println("=================================================================");
        System.out.println("目标服务地址    : " + wsUrl);
        System.out.println("目标并发连接数  : " + targetConns);
        System.out.println("批次并发握手步长: " + batchSize);
        System.out.println("稳定压测保活时长: " + holdSeconds + " 秒");
        System.out.println("-----------------------------------------------------------------");

        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        System.gc();
        Thread.sleep(500);
        long memBefore = memBean.getHeapMemoryUsage().getUsed();

        // 兼容 Java 17 LTS 标准线程池
        ExecutorService workerPool = Executors.newFixedThreadPool(Math.min(200, Math.max(20, batchSize * 2)));
        HttpClient client = HttpClient.newBuilder()
                .executor(workerPool)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        List<WebSocket> activeSockets = Collections.synchronizedList(new ArrayList<>(targetConns));
        long startTime = System.currentTimeMillis();

        System.out.println("[阶段 1/3] 正在发起批量并发握手与 JWT 认证...");
        int dispatched = 0;
        while (dispatched < targetConns) {
            int currentBatch = Math.min(batchSize, targetConns - dispatched);
            CountDownLatch batchLatch = new CountDownLatch(currentBatch);

            for (int i = 0; i < currentBatch; i++) {
                final int connId = dispatched + i + 1;
                workerPool.submit(() -> {
                    try {
                        connectOne(client, wsUrl, token, activeSockets, batchLatch);
                    } catch (Exception e) {
                        ERROR_COUNT.incrementAndGet();
                        batchLatch.countDown();
                    }
                });
            }

            batchLatch.await(15, TimeUnit.SECONDS);
            dispatched += currentBatch;
            System.out.printf(" -> 已派发: %d/%d (已建连: %d, 已鉴权: %d, 异常: %d)\n",
                    dispatched, targetConns, CONNECTED_COUNT.get(), AUTHED_COUNT.get(), ERROR_COUNT.get());
            Thread.sleep(100);
        }

        long handshakeDuration = System.currentTimeMillis() - startTime;
        double handshakeRate = (double) AUTHED_COUNT.get() / (Math.max(1, handshakeDuration) / 1000.0);

        System.out.println("\n[阶段 2/3] 全量长连接建立完毕，开始心跳探测与保活监测 (持续 " + holdSeconds + "s)...");
        long peakMem = memBean.getHeapMemoryUsage().getUsed();

        ScheduledExecutorService pingScheduler = Executors.newScheduledThreadPool(2);
        pingScheduler.scheduleAtFixedRate(() -> {
            long sendTs = System.currentTimeMillis();
            synchronized (activeSockets) {
                for (WebSocket ws : activeSockets) {
                    if (!ws.isOutputClosed()) {
                        PING_TIMES.put(ws, sendTs);
                        ws.sendText("{\"type\":\"ping\",\"_ts\":" + sendTs + "}", true);
                    }
                }
            }
        }, 1, 2, TimeUnit.SECONDS);

        // 维持连接稳定 holdSeconds 秒
        for (int s = 0; s < holdSeconds; s++) {
            Thread.sleep(1000);
            System.out.printf(" [保活中 %2ds] 活跃长连接: %d | 累计接收帧: %d | 采样心跳数: %d\n",
                    s + 1, AUTHED_COUNT.get(), FRAMES_RECEIVED.get(), LATENCY_SAMPLES.size());
        }

        pingScheduler.shutdownNow();

        System.out.println("\n[阶段 3/3] 压测结束，正在优雅断开连接并统计评测数据...");
        synchronized (activeSockets) {
            for (WebSocket ws : activeSockets) {
                try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "benchmark done");
                } catch (Exception ignored) {}
            }
        }
        workerPool.shutdown();

        // 计算延迟分位数
        List<Long> latencies;
        synchronized (LATENCY_SAMPLES) {
            latencies = new ArrayList<>(LATENCY_SAMPLES);
        }
        Collections.sort(latencies);

        long minLat = latencies.isEmpty() ? 0 : latencies.get(0);
        long maxLat = latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1);
        double avgLat = latencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
        long p95Lat = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.95));
        long p99Lat = latencies.isEmpty() ? 0 : latencies.get((int) (latencies.size() * 0.99));

        long memDelta = Math.max(0, peakMem - memBefore);
        double memPerConnKb = AUTHED_COUNT.get() > 0 ? (double) memDelta / AUTHED_COUNT.get() / 1024.0 : 0.0;

        System.out.println("=================================================================");
        System.out.println("              Spike S-4 WebSocket 性能基准测试报告               ");
        System.out.println("=================================================================");
        System.out.printf("1. 目标压测规模        : %d 连接\n", targetConns);
        System.out.printf("2. 成功认证连接数      : %d (成功率: %.2f%%)\n",
                AUTHED_COUNT.get(), (double) AUTHED_COUNT.get() / Math.max(1, targetConns) * 100.0);
        System.out.printf("3. 异常连接数          : %d\n", ERROR_COUNT.get());
        System.out.printf("4. 批量握手建连速率    : %.2f 连接/秒 (总耗时 %.2fs)\n",
                handshakeRate, handshakeDuration / 1000.0);
        System.out.printf("5. 累计接收帧数        : %d 帧\n", FRAMES_RECEIVED.get());
        System.out.printf("6. 心跳往返延迟 (RTT)  : Avg: %.2fms | Min: %dms | Max: %dms\n", avgLat, minLat, maxLat);
        System.out.printf("7. 延迟分位数 (SLA)    : P95: %dms | P99: %dms\n", p95Lat, p99Lat);
        System.out.printf("8. 单连接估算内存开销  : %.2f KB/连接 (峰值堆增量: %.2f MB)\n",
                memPerConnKb, memDelta / 1024.0 / 1024.0);
        System.out.println("-----------------------------------------------------------------");
        if (AUTHED_COUNT.get() >= (targetConns * 0.95) && p95Lat < 50) {
            System.out.println("[ok] 验收合格：WebSocket 并发连接密度与低延迟响应全面达标！");
        } else {
            System.out.println("[i] 压测完成，数据已记录。");
        }
        System.out.println("=================================================================");
        System.exit(0);
    }

    private static final Map<WebSocket, Long> PING_TIMES = new ConcurrentHashMap<>();

    private static void connectOne(HttpClient client, String wsUrl, String token,
                                   List<WebSocket> activeSockets, CountDownLatch batchLatch) {
        CompletableFuture<WebSocket> future = client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        CONNECTED_COUNT.incrementAndGet();
                        // 建立连接后立刻发送 auth 鉴权帧
                        webSocket.sendText("{\"type\":\"auth\",\"payload\":{\"token\":\"" + token + "\"}}", true);
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        FRAMES_RECEIVED.incrementAndGet();
                        String frame = data.toString();

                        if (frame.contains("\"type\":\"ready\"")) {
                            AUTHED_COUNT.incrementAndGet();
                            activeSockets.add(webSocket);
                            batchLatch.countDown();
                        } else if (frame.contains("\"type\":\"pong\"")) {
                            Long sendTs = PING_TIMES.get(webSocket);
                            if (sendTs != null) {
                                long rtt = System.currentTimeMillis() - sendTs;
                                LATENCY_SAMPLES.add(rtt);
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        int err = ERROR_COUNT.incrementAndGet();
                        if (err <= 2) {
                            System.err.println("[!] WebSocket 异常: " + error.getMessage());
                        }
                        batchLatch.countDown();
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        return null;
                    }
                });

        future.exceptionally(ex -> {
            int err = ERROR_COUNT.incrementAndGet();
            if (err <= 2) {
                System.err.println("[!] 握手失败: " + ex.getMessage());
            }
            batchLatch.countDown();
            return null;
        });
    }
}
