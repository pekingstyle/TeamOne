package cn.teamone.eng.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 构建命令执行器（M4-INC1 · 每作业容器隔离形态）。
 *
 * <p><b>形态演进</b>：初版为进程内 ProcessBuilder 直跑 mvn——实测两次拖垮整个 WSL VM
 * （构建进程与宿主 Spring JVM 同容器互相挤压，Hyper-V 侧 VM 反复重建），遂按
 * docs/v2/11 §4「每作业容器隔离（工具链镜像白名单）」改造：作业经
 * {@code docker run --rm} 在独立工具镜像容器内执行，与长期稳定运行的容器化 IT 同模式。</p>
 *
 * <p>R8 纪律边界：git 子进程仍只经 {@code cn.teamone.eng.infra.git}（GitPort 唯一出墙口）；
 * 本类只拉起 {@code docker} 客户端进程（runner 包允许的构建工具子进程面），命令来自服务端
 * 模板 {@code PipelineJobTemplates}（空白分列成参数数组、作为镜像 ENTRYPOINT argv 直传，
 * 无 shell、无用户输入拼装）。超时强杀 docker CLI 并兜底 {@code docker rm -f} 清容器。</p>
 *
 * <p>挂载契约：工作区必须是<b>docker daemon 可见路径</b>（/teamone-ws，与宿主同路径双向
 * bind）；Maven 缓存挂 teamone-m2 卷；npm 工具镜像未配置时该体系作业诚实降级。</p>
 *
 * @author Ivan Yang, 2026-09-20
 */
@Component
public class BuildCommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(BuildCommandExecutor.class);

    /** 强杀 docker CLI 后等待其退出的宽限 */
    private static final long KILL_GRACE_SECONDS = 10;
    /** 单行截断长度（异常超长行——如 mvn 打印 base64/字节流——不得撑爆 log_tail） */
    private static final int MAX_LINE_CHARS = 2000;
    /** 超时兜底 rm -f 的自身超时（防清理动作挂死收口线程） */
    private static final long RM_TIMEOUT_SECONDS = 20;

    /** Maven 工具镜像（含 JDK+mvn；默认与 IT 容器化同镜像） */
    @Value("${teamone.pipeline.tool-image.maven:maven:3.9-eclipse-temurin-17}")
    private String mavenImage;

    /** npm 工具镜像（M5 工具链镜像化前默认空=该体系作业诚实降级） */
    @Value("${teamone.pipeline.tool-image.npm:}")
    private String npmImage;

    /**
     * 工作区的 daemon 侧根路径：docker -v 由 daemon 解析路径，而 Runner 看到的是 server 容器内
     * 路径（/teamone-ws ← 宿主 /var/lib/teamone-ws 双向 bind）——挂载时必须翻译回宿主路径，
     * 否则 daemon 自动建空目录、作业容器拿到的 /ws 为空（实测踩坑：maven 报 pom 不存在）。
     * 留空=不翻译（Runner 与 daemon 同机同路径的直跑形态）。
     */
    @Value("${teamone.pipeline.workspace-host-dir:}")
    private String workspaceHostDir;

    /** 容器内工作区前缀：与 yml teamone.pipeline.workspace-dir 同键（Runner workspaceRoot 同源） */
    @Value("${teamone.pipeline.workspace-dir:/teamone-ws}")
    private String workspaceContainerPrefix;

    /**
     * 执行结果。
     *
     * @param exitCode  进程退出码（超时/启动失败为 null——视为失败）
     * @param logTail   合流输出尾部文本（行数上限由调用方截断）
     * @param errorMsg  失败原因（成功为 null）
     */
    public record Outcome(Integer exitCode, String logTail, String errorMsg) {

        public boolean success() {
            return exitCode != null && exitCode == 0 && errorMsg == null;
        }
    }

    /**
     * 构建工具是否可执行（容器隔离形态=对应工具镜像是否配置）。
     *
     * @param tool 工具名（mvn / npm）
     * @return true=已配置工具镜像（docker 不可用等运行期故障由 run() 的退出码/错误信息承担）
     */
    public boolean toolAvailable(String tool) {
        return imageOf(tool) != null;
    }

    /** 工具名 → 工具镜像；未配置返回 null（调用方给诚实降级文案） */
    private String imageOf(String tool) {
        if (tool == null) {
            return null;
        }
        return switch (tool) {
            case "mvn" -> mavenImage == null || mavenImage.isBlank() ? null : mavenImage;
            case "npm", "node", "npx" -> npmImage == null || npmImage.isBlank() ? null : npmImage;
            default -> null;
        };
    }

    /**
     * 在独立工具容器内执行命令（stdout/stderr 合流，尾部缓冲）。
     *
     * <p>argv 形态：{@code docker run --rm --name {name} -v {ws}:/ws -v teamone-m2:/root/.m2
     * -e MAVEN_OPTS=… -w /ws {image} {cmd...}}——命令作为镜像 ENTRYPOINT 直传（无 shell）。
     * 超时由看门狗线程强杀 CLI，并异步 {@code docker rm -f} 兜底清容器（--rm 在 CLI 被杀后
     * 不会触发）。</p>
     *
     * @param cmd        参数数组（服务端模板分列：首元素=工具名）
     * @param workdir    作业工作区（docker daemon 可见路径；挂为容器 /ws）
     * @param timeout    超时（超时强杀，exitCode=null + errorMsg 注明）
     * @param tailLines  尾部缓冲行数（log_tail 落库行数上限）
     * @return 执行结果
     */
    public Outcome run(List<String> cmd, Path workdir, Duration timeout, int tailLines) {
        if (cmd == null || cmd.isEmpty()) {
            return new Outcome(null, "", "空命令");
        }
        String image = imageOf(cmd.get(0));
        if (image == null) {
            return new Outcome(null, "",
                    "工具链不可用：未配置 " + cmd.get(0) + " 工具镜像（teamone.pipeline.tool-image.*，M5 工具链镜像化）");
        }
        String containerName = "teamone-job-" + workdir.getFileName().toString().replace("teamone-job-", "");
        // 挂载路径翻译：容器内 /teamone-ws/… → daemon 侧宿主路径（配置了 host 根时）
        String mountDir = workdir.toString();
        if (workspaceHostDir != null && !workspaceHostDir.isBlank()
                && mountDir.startsWith(workspaceContainerPrefix)) {
            mountDir = workspaceHostDir + mountDir.substring(workspaceContainerPrefix.length());
        }
        List<String> argv = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "--name", containerName,
                "-v", mountDir + ":/ws",
                "-v", "teamone-m2:/root/.m2",
                "-e", "MAVEN_OPTS=-Xmx1024m -XX:MaxMetaspaceSize=384m",
                "-w", "/ws",
                image));
        argv.addAll(cmd);

        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            return new Outcome(null, "", "docker 客户端启动失败（容器内未安装/未挂 sock）: " + e.getMessage());
        }
        AtomicBoolean timedOut = new AtomicBoolean(false);
        Thread watchdog = new Thread(() -> {
            try {
                if (!proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    timedOut.set(true);
                    proc.destroyForcibly();
                    forceRemoveContainer(containerName);
                }
            } catch (InterruptedException ignored) {
                // 主流程已收口，看门狗静默退场
            }
        }, "pipeline-job-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        ArrayDeque<String> tail = new ArrayDeque<>();
        boolean interrupted = false;
        try {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() > MAX_LINE_CHARS) {
                        line = line.substring(0, MAX_LINE_CHARS) + "…";
                    }
                    if (tail.size() >= tailLines) {
                        tail.pollFirst();
                    }
                    tail.addLast(line);
                }
            }
            if (!proc.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly(); // 读尽后仍未退出（极端）：强杀兜底，exitCode 不可得
            }
        } catch (IOException e) {
            log.debug("[pipeline-runner] 输出读取中断: {}", e.getMessage());
        } catch (InterruptedException e) {
            interrupted = true;
            proc.destroyForcibly();
            forceRemoveContainer(containerName);
            Thread.currentThread().interrupt();
        } finally {
            if (proc.isAlive()) {
                proc.destroyForcibly();
            }
        }
        String logTail = String.join("\n", tail);
        if (interrupted) {
            return new Outcome(null, logTail, "执行被中断");
        }
        if (timedOut.get()) {
            return new Outcome(null, logTail,
                    "执行超时（>" + timeout.toMinutes() + " 分钟）已强杀并清理容器 " + containerName);
        }
        return new Outcome(proc.exitValue(), logTail, null);
    }

    /** 超时/中断后的容器清理兜底（fire-and-forget，失败仅 debug） */
    private void forceRemoveContainer(String name) {
        try {
            Process rm = new ProcessBuilder(List.of("docker", "rm", "-f", name)).start();
            if (!rm.waitFor(RM_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                rm.destroyForcibly();
            }
        } catch (Exception e) {
            log.debug("[pipeline-runner] 超时容器清理失败 {}: {}", name, e.getMessage());
        }
    }
}
