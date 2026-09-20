package cn.teamone.eng.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 构建命令执行器（M4-INC1 · 每作业容器隔离形态；M4-INC2 · JaCoCo 覆盖率链路 + 执行安全加固）。
 *
 * <p><b>形态演进</b>：初版为进程内 ProcessBuilder 直跑 mvn——实测两次拖垮整个 WSL VM
 * （构建进程与宿主 Spring JVM 同容器互相挤压，Hyper-V 侧 VM 反复重建），遂按
 * docs/v2/11 §4「每作业容器隔离（工具链镜像白名单）」改造：作业经
 * {@code docker run --rm} 在独立工具镜像容器内执行，与长期稳定运行的容器化 IT 同模式。
 * M4-INC2 追加资源上限（{@code --memory/--cpus}，防单作业挤垮宿主）与覆盖率链路。</p>
 *
 * <p><b>覆盖率链路（M4-INC2 · R8 门禁真实化 docs/v2/11 §3.4）</b>：
 * <ol>
 *   <li>test 阶段 maven 命令追加 {@code -DargLine=-javaagent:{agentJar}=destfile=/ws/jacoco.exec,...}
 *       —— agent jar 由运维预热入 teamone-m2 卷（容器内 /root/.m2/repository/org/jacoco/…），
 *       {@link #runMavenTestWithCoverage} 先经一次性探测容器确认存在才注入；不存在则不注入
 *       （诚实降级——缺 agent 时 -javaagent 会让 surefire fork 直接起不来，单测本身不能被阻塞）。</li>
 *   <li>若被建工程 pom 硬编码 {@code <argLine>} 覆盖了属性注入，exec 不会产出——由 Runner 侧
 *       「exec 缺失→降级」分支兜底（本方法无法感知，交由上层）。</li>
 *   <li>test 作业成功后 {@link #generateCoverageReport}：服务器侧枚举工作区任意层级的
 *       {@code target/classes} 目录（只取含 .class 的，上限 {@value #MAX_CLASSFILE_DIRS} 个防 argv 爆）
 *       → 工具容器执行 {@code java -jar {cliJar} report …} 产出 /ws/jacoco.xml。</li>
 * </ol></p>
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
    /** agent 探测容器超时（镜像本地无缓存时拉取余量） */
    private static final long PROBE_TIMEOUT_SECONDS = 60;
    /** 覆盖率报告生成容器超时（cli report 通常秒级，留足余量） */
    private static final Duration REPORT_TIMEOUT = Duration.ofMinutes(5);
    /** 覆盖率报告容器尾部日志行数 */
    private static final int REPORT_TAIL_LINES = 60;

    /** 容器内工作区挂载点（与 -v {ws}:/ws 及 -w /ws 对齐） */
    static final String WS_CONTAINER_DIR = "/ws";
    /** 容器内 exec 文件路径（agent destfile 与 cli report 输入共用同一约定） */
    static final String JACOCO_EXEC = "/ws/jacoco.exec";
    /** 容器内 xml 报告路径（cli --xml 输出；Runner 在工作区根读回 jacoco.xml） */
    static final String JACOCO_XML = "/ws/jacoco.xml";
    /** classfiles 目录枚举上限（防超多模块仓库撑爆 docker argv） */
    static final int MAX_CLASSFILE_DIRS = 32;

    /** Maven 工具镜像（含 JDK+mvn；默认与 IT 容器化同镜像） */
    @Value("${teamone.pipeline.tool-image.maven:maven:3.9-eclipse-temurin-17}")
    private String mavenImage = "maven:3.9-eclipse-temurin-17";

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

    /** 作业容器内存上限（M4-INC2 执行安全加固：--memory 原样透传 docker） */
    @Value("${teamone.pipeline.job-memory:2g}")
    private String jobMemory = "2g";

    /** 作业容器 CPU 上限（M4-INC2 执行安全加固：--cpus 原样透传 docker） */
    @Value("${teamone.pipeline.job-cpus:2}")
    private String jobCpus = "2";

    /** JaCoCo 版本（agent/cli jar 均按此拼 m2 卷内路径；版本可配） */
    @Value("${teamone.pipeline.jacoco.version:0.8.12}")
    private String jacocoVersion = "0.8.12";

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
     * test 阶段 maven 执行结果（覆盖率注入形态）。
     *
     * @param outcome       进程执行结果
     * @param agentInjected agent 是否探测存在并完成注入（false=诚实降级：命令原样执行）
     */
    public record TestRunOutcome(Outcome outcome, boolean agentInjected) {}

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

    // ==================== JaCoCo 覆盖率链路（M4-INC2） ====================

    /** 容器内 agent-runtime jar 路径（m2 卷预热位；{@code org.jacoco.agent:{version}:runtime}） */
    String jacocoAgentJar() {
        return "/root/.m2/repository/org/jacoco/org.jacoco.agent/" + jacocoVersion
                + "/org.jacoco.agent-" + jacocoVersion + "-runtime.jar";
    }

    /** 容器内 cli-nodeps jar 路径（m2 卷预热位） */
    String jacocoCliJar() {
        return "/root/.m2/repository/org/jacoco/org.jacoco.cli/" + jacocoVersion
                + "/org.jacoco.cli-" + jacocoVersion + "-nodeps.jar";
    }

    /**
     * surefire argLine 属性注入串（单 argv 分列，无 shell）。
     *
     * <p>append=<b>true</b>：maven 多模块构建每个 surefire fork 各自 dump 一次，append=false
     * 会让后一个模块 fork 截断前一个的数据（只剩末模块）——多模块必须合并追加。「防跨轮陈旧
     * 数据」的意图（规格原文 append=false）由「每作业全新工作区」保证：Runner 认领后
     * cleanDirectory + 新 jobId 目录导出，exec 不可能残留。与既有 MAVEN_OPTS 注入并存不冲突：
     * env 只作用于 mvn 宿主 JVM，-DargLine 经 surefire 属性下发到测试 fork JVM。</p>
     */
    String jacocoArgLineProperty() {
        return "-DargLine=-javaagent:" + jacocoAgentJar()
                + "=destfile=" + JACOCO_EXEC + ",append=true";
    }

    /**
     * agent jar 是否已预热入 m2 卷：一次性探测容器 {@code test -f}（exit 0=存在）。
     * 不用服务器侧 Files.exists——teamone-m2 是 docker 具名卷，server 进程看不到其内容。
     */
    public boolean jacocoAgentAvailable() {
        if (mavenImage == null || mavenImage.isBlank()) {
            return false;
        }
        Process proc = null;
        try {
            proc = new ProcessBuilder(List.of(
                            "docker", "run", "--rm",
                            "-v", "teamone-m2:/root/.m2",
                            "--entrypoint", "test",
                            mavenImage,
                            "-f", jacocoAgentJar()))
                    .redirectErrorStream(true)
                    .start();
            // QA 复审 MUST-FIX：先 waitFor 后读流——docker CLI 挂死（流不 EOF）时 drainQuietly 会
            // 永久阻塞、探测超时形同虚设（单飞执行线程被卡死）。读流放 waitFor 之后，超时由
            // waitFor 返回 false 兜底强杀（finally destroyForcibly）
            boolean finished = proc.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                return false;
            }
            drainQuietly(proc);
            return proc.exitValue() == 0;
        } catch (Exception e) {
            log.warn("[pipeline-runner] JaCoCo agent 探测容器失败（按缺失降级）: {}", e.getMessage());
            return false;
        } finally {
            if (proc != null && proc.isAlive()) {
                proc.destroyForcibly();
            }
        }
    }

    /**
     * 执行 test 阶段 maven 命令（按需注入 JaCoCo agent）。
     *
     * <p>agent 探测存在 → 命令尾部追加 {@link #jacocoArgLineProperty()}；不存在 → 原样执行
     * （单测不被环境缺口阻塞），{@code agentInjected=false} 交上层标记覆盖率降级。</p>
     *
     * @param cmd        mvn 命令参数数组（首元素=mvn）
     * @param workdir    作业工作区
     * @param timeout    超时
     * @param tailLines  尾部缓冲行数
     * @return 执行结果 + agent 注入标记
     */
    public TestRunOutcome runMavenTestWithCoverage(List<String> cmd, Path workdir, Duration timeout, int tailLines) {
        if (cmd == null || cmd.isEmpty() || !"mvn".equals(cmd.get(0))) {
            return new TestRunOutcome(run(cmd, workdir, timeout, tailLines), false);
        }
        if (!jacocoAgentAvailable()) {
            log.warn("[pipeline-runner] JaCoCo agent 未预热入 teamone-m2 卷（{}）——test 命令不注入，覆盖率降级",
                    jacocoAgentJar());
            return new TestRunOutcome(run(cmd, workdir, timeout, tailLines), false);
        }
        List<String> injected = new ArrayList<>(cmd);
        injected.add(jacocoArgLineProperty());
        return new TestRunOutcome(run(injected, workdir, timeout, tailLines), true);
    }

    /**
     * 生成 JaCoCo XML 覆盖率报告（Runner 侧，test 作业成功后调用）。
     *
     * <p>argv 形态：{@code docker run --rm --name teamone-cov-{jobId} -v {ws}:/ws
     * -v teamone-m2:/root/.m2 --memory --cpus -w /ws {maven镜像} java -jar {cliJar}
     * report /ws/jacoco.exec --classfiles /ws/<模块>/target/classes（可重复）
     * --xml /ws/jacoco.xml --quiet}。exec 不存在 / 无 classfiles → 返回失败 Outcome
     * （诚实降级，不抛异常不阻塞主链）。成功以「退出码 0 且工作区实际出现 jacoco.xml」为准。</p>
     *
     * @param workspace 作业工作区（含 jacoco.exec 与编译产物）
     * @return 执行结果（errorMsg 说明降级原因）
     */
    public Outcome generateCoverageReport(Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace)) {
            return new Outcome(null, "", "覆盖率报告跳过：工作区不存在");
        }
        if (!Files.isRegularFile(workspace.resolve("jacoco.exec"))) {
            return new Outcome(null, "", "jacoco.exec 缺失（agent 未注入或 argLine 被工程 pom 覆盖）——覆盖率降级");
        }
        List<String> argv;
        try {
            argv = buildCoverageArgv(workspace, mountDirFor(workspace));
        } catch (IOException e) {
            return new Outcome(null, "", "classfiles 枚举失败（覆盖率降级）: " + e.getMessage());
        }
        if (argv == null) {
            return new Outcome(null, "", "工作区无含 .class 的 target/classes（无 classfiles 可报告）——覆盖率降级");
        }
        String containerName = "teamone-cov-" + workspace.getFileName().toString().replace("teamone-job-", "");
        Outcome out = runDocker(argv, containerName, REPORT_TIMEOUT, REPORT_TAIL_LINES);
        if (out.success() && !Files.isRegularFile(workspace.resolve("jacoco.xml"))) {
            return new Outcome(out.exitCode(), out.logTail(), "覆盖率报告容器退出码 0 但未产出 jacoco.xml——覆盖率降级");
        }
        return out;
    }

    /**
     * 拼装覆盖率报告的完整 docker argv（包内可见供单测锁形状）。
     *
     * @param workspace 工作区（须已含 jacoco.exec，否则返回 null）
     * @param mountDir  daemon 侧挂载路径（已完成 host 翻译）
     * @return 完整 argv；exec 缺失或无可报告 classfiles 时 null
     */
    List<String> buildCoverageArgv(Path workspace, String mountDir) throws IOException {
        if (!Files.isRegularFile(workspace.resolve("jacoco.exec"))) {
            return null;
        }
        List<String> classDirs = classfileDirs(workspace);
        if (classDirs.isEmpty()) {
            return null;
        }
        List<String> argv = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "--name", "teamone-cov-" + workspace.getFileName().toString().replace("teamone-job-", ""),
                "-v", mountDir + ":" + WS_CONTAINER_DIR,
                "-v", "teamone-m2:/root/.m2",
                "--memory=" + jobMemory,
                "--cpus=" + jobCpus,
                "-w", WS_CONTAINER_DIR,
                mavenImage,
                "java", "-jar", jacocoCliJar(), "report", JACOCO_EXEC));
        for (String dir : classDirs) {
            argv.add("--classfiles");
            argv.add(dir);
        }
        argv.add("--xml");
        argv.add(JACOCO_XML);
        argv.add("--quiet");
        return argv;
    }

    /**
     * 枚举工作区内任意层级的 {@code target/classes} 目录（只取至少含一个 .class 的；排序去随机；
     * 上限 {@value #MAX_CLASSFILE_DIRS} 防超多模块仓库撑爆 argv）。
     *
     * @return 容器视角路径列表（/ws/…，分隔符归一为 /）
     */
    static List<String> classfileDirs(Path workspace) throws IOException {
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(workspace)) {
            walk.filter(Files::isDirectory)
                    .filter(BuildCommandExecutor::isTargetClassesDir)
                    .filter(BuildCommandExecutor::hasClassFiles)
                    .sorted()
                    .limit(MAX_CLASSFILE_DIRS)
                    .forEach(dirs::add);
        }
        List<String> containerPaths = new ArrayList<>(dirs.size());
        for (Path dir : dirs) {
            containerPaths.add(WS_CONTAINER_DIR + "/"
                    + workspace.relativize(dir).toString().replace('\\', '/'));
        }
        return containerPaths;
    }

    /** 目录形如 <code>&lt;任意&gt;/target/classes</code> 判定 */
    private static boolean isTargetClassesDir(Path dir) {
        Path parent = dir.getParent();
        return parent != null
                && "classes".equals(dir.getFileName().toString())
                && "target".equals(parent.getFileName().toString());
    }

    /** 目录内（任意深度）是否至少存在一个 .class 文件（空目录/纯资源目录不进 classfiles） */
    private static boolean hasClassFiles(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.anyMatch(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().endsWith(".class"));
        } catch (IOException e) {
            return false;
        }
    }

    // ==================== 作业容器执行 ====================

    /**
     * 在独立工具容器内执行命令（stdout/stderr 合流，尾部缓冲）。
     *
     * <p>argv 形态：{@code docker run --rm --name {name} --memory={m} --cpus={c}
     * -v {ws}:/ws -v teamone-m2:/root/.m2 -e MAVEN_OPTS=… -w /ws {image} {cmd...}}——命令作为
     * 镜像 ENTRYPOINT 直传（无 shell）。超时由看门狗线程强杀 CLI，并异步
     * {@code docker rm -f} 兜底清容器（--rm 在 CLI 被杀后不会触发）。</p>
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
        List<String> argv = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "--name", containerName,
                "--memory=" + jobMemory,
                "--cpus=" + jobCpus,
                "-v", mountDirFor(workdir) + ":" + WS_CONTAINER_DIR,
                "-v", "teamone-m2:/root/.m2",
                "-e", "MAVEN_OPTS=-Xmx1024m -XX:MaxMetaspaceSize=384m",
                "-w", WS_CONTAINER_DIR,
                image));
        argv.addAll(cmd);
        return runDocker(argv, containerName, timeout, tailLines);
    }

    /** 挂载路径翻译：容器内 /teamone-ws/… → daemon 侧宿主路径（配置了 host 根时） */
    private String mountDirFor(Path workdir) {
        String mountDir = workdir.toString();
        if (workspaceHostDir != null && !workspaceHostDir.isBlank()
                && mountDir.startsWith(workspaceContainerPrefix)) {
            mountDir = workspaceHostDir + mountDir.substring(workspaceContainerPrefix.length());
        }
        return mountDir;
    }

    /**
     * 拉起完整 docker argv 并收口输出（进程/看门狗/尾部缓冲机械；作业容器与覆盖率报告容器共用）。
     */
    private Outcome runDocker(List<String> argv, String containerName, Duration timeout, int tailLines) {
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

    /** 读尽探测容器输出（test -f 无输出；防御性排空防管道缓冲阻塞） */
    private static void drainQuietly(Process proc) throws IOException {
        try (var in = proc.getInputStream()) {
            byte[] buf = new byte[4096];
            while (in.read(buf) >= 0) {
                // 丢弃
            }
        }
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
