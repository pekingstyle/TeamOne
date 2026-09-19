package cn.teamone.eng.infra.git;

import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 外部仓库连通性探针（R-12 凭据「测试连接」）：对配置的仓库地址执行 {@code git ls-remote}。
 *
 * <p>与 {@link GitCommandPort} 同款进程纪律（ArchUnit R8：git 子进程只允许出自
 * cn.teamone.eng.infra.git 包）——<b>URL 白名单校验（防注入/防内网）→ ProcessBuilder
 * 无 shell 参数数组直传 → GIT_TERMINAL_PROMPT=0 绝不交互 → 超时强杀 → stderr 脱敏</b>。
 * 与本地 bare 库命令的差异：目标是外网 https 地址，超时放宽到 15s（网络往返）。</p>
 *
 * <p>URL 白名单（评审口径：仅 https:// 且禁本地路径）：
 * 协议仅 https；禁止 userinfo（@ 形式内嵌口令）；host 须为域名或点分 IPv4，
 * 拒绝 localhost/环回/链路本地/链路本地元数据地址；路径禁止 ".." 上跳。</p>
 *
 * <p><b>口径声明（⑥h 批收口）</b>：校验有意<b>放行私网地址</b>（10/8、172.16/12、192.168/16
 * 等内网网段）——内网自建 Git（公司机房/私有 VPC 的 GitLab/Gitea 等）正是本功能的目标场景，
 * 一律拒绝反而使功能失效；风险由「仅 platform:manage 管理员可达 + 每次探测写审计」兜底。
 * 与之相对，{@code file:}/本地路径形态已被协议白名单（仅 https）拒绝——本地路径探针可被
 * 用于绕过白名单直读服务器文件系统，二者是不同维度的风险，故一禁一放。</p>
 *
 * <p>令牌传递：经 {@code GIT_CONFIG_KEY_0/GIT_CONFIG_VALUE_0} 环境变量注入
 * {@code http.extraheader}（Basic 认证）——令牌不进 argv（ps 不可见），返回/日志前再做一层脱敏。</p>
 */
@Component
public class RemoteRepoProbe {

    private static final Logger log = LoggerFactory.getLogger(RemoteRepoProbe.class);

    /** 网络探测超时（秒）：外部地址往返 + 认证握手，比本地 bare 库 5s 放宽；到点强杀 */
    private static final long TIMEOUT_SECONDS = 15;

    /** 连通结果（ok=false 时 message 已脱敏，可直接展示给管理员） */
    public record ProbeResult(boolean ok, String message, long elapsedMs) { }

    /**
     * 探测外部仓库连通性。
     *
     * @param url   仓库地址（须通过 {@link #validateUrl} 白名单）
     * @param token 可选访问令牌（null/空白=匿名探测；经环境变量注入，不进 argv/日志/返回）
     */
    public ProbeResult probe(String url, String token) {
        validateUrl(url);
        List<String> cmd = new ArrayList<>(List.of("git", "ls-remote", url));
        long start = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0"); // 强制覆写（QA 复审）：宿主预置非 0 值会交互挂起，仅靠超时兜底不够
        if (token != null && !token.isBlank()) {
            // 令牌走环境变量型 git config（http.extraheader Basic 认证）：argv 不出现令牌
            String basic = Base64.getEncoder()
                    .encodeToString(("teamone:" + token).getBytes(StandardCharsets.UTF_8));
            pb.environment().put("GIT_CONFIG_COUNT", "1");
            pb.environment().put("GIT_CONFIG_KEY_0", "http.extraheader");
            pb.environment().put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
        }
        Process proc;
        try {
            proc = pb.start();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SRV_5030, "git 进程启动失败（git 不可用？）");
        }
        try {
            StringBuilder stderr = new StringBuilder();
            Thread errDrain = drain(proc.getErrorStream(), stderr);
            if (!proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return new ProbeResult(false, "探测超时（>" + TIMEOUT_SECONDS + "s），仓库不可达或网络受限",
                        System.currentTimeMillis() - start);
            }
            errDrain.join(1000);
            long elapsed = System.currentTimeMillis() - start;
            if (proc.exitValue() == 0) {
                return new ProbeResult(true, "连接成功，仓库可访问", elapsed);
            }
            log.warn("[repo-probe] ls-remote exit={} url-scheme-only=https stderr={}",
                    proc.exitValue(), tail(sanitize(stderr.toString(), token)));
            return new ProbeResult(false,
                    "连接失败：" + tail(sanitize(stderr.toString(), token)) + "（地址或访问令牌有误？）", elapsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeResult(false, "探测被中断", System.currentTimeMillis() - start);
        } finally {
            if (proc.isAlive()) { // 超时/中断路径兜底强杀（与 GitCommandPort 同款）
                proc.destroyForcibly();
            }
        }
    }

    /**
     * URL 白名单校验（防注入/防内网；违规统一 400，不回显原值细节）：
     * 仅 https；禁 userinfo；host 须域名/点分 IPv4 且非环回/本地/链路本地；路径禁 ".."。
     */
    static void validateUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PLT_4000, "仓库地址不合法（须为 https:// 开头的标准 URL）");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            // 白名单协议：仅 https（http/file/ssh/git/local path 一律拒绝）
            throw new BusinessException(ErrorCode.PLT_4000, "仓库地址协议不在白名单内（仅支持 https://）");
        }
        if (uri.getUserInfo() != null) {
            // 禁止 https://user:pass@host 形式——口令内嵌 URL 会进 argv 与日志
            throw new BusinessException(ErrorCode.PLT_4000, "仓库地址禁止内嵌用户信息（请改用访问令牌字段）");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (host.isEmpty() || !host.matches("[a-z0-9.\\-]+") || host.startsWith(".") || host.endsWith(".")) {
            throw new BusinessException(ErrorCode.PLT_4000, "仓库主机名不合法");
        }
        if (host.equals("localhost") || host.endsWith(".localhost") || host.equals("0.0.0.0")
                || host.startsWith("127.") || host.startsWith("169.254.") || host.equals("::1")) {
            // 禁本地/环回/链路本地（含云元数据 169.254.169.254）
            throw new BusinessException(ErrorCode.PLT_4000, "仓库地址禁止指向本机/内网保留地址");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (path.contains("..")) {
            // 禁相对上跳（本地路径逃逸形态）
            throw new BusinessException(ErrorCode.PLT_4000, "仓库地址路径不合法");
        }
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            throw new BusinessException(ErrorCode.PLT_4000, "仓库地址端口不合法");
        }
    }

    /** stderr 脱敏：令牌若因服务端回显混入输出，替换为 ***（双保险） */
    static String sanitize(String text, String token) {
        if (text == null) return "";
        String out = text;
        if (token != null && !token.isBlank() && out.contains(token)) {
            out = out.replace(token, "***");
        }
        return out;
    }

    private static String tail(String s) {
        String t = s.trim();
        if (t.isEmpty()) return "git 返回非零退出码";
        return t.length() <= 200 ? t : "…" + t.substring(t.length() - 200);
    }

    private static Thread drain(java.io.InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sink.append(line).append('\n');
                }
            } catch (Exception ignored) {
                // 进程被强杀时的流关闭，忽略
            }
        }, "repo-probe-stderr-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
