package cn.teamone.eng.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BuildCommandExecutor 单测（M4-INC2，不起 docker）：JaCoCo agent/cli 容器路径与 argLine
 * 注入串形状（版本可配）、覆盖率报告 argv 拼装（classfiles 可重复 / /ws 前缀 / 资源上限 /
 * exec 与 classfiles 缺失降级 / 32 目录上限防 argv 爆）。进程执行机械由容器化 IT 覆盖。
 *
 * @author Ivan Yang, 2026-09-20
 */
class BuildCommandExecutorTest {

    @TempDir
    Path tmp;

    private static BuildCommandExecutor executor() {
        return new BuildCommandExecutor(); // @Value 字段带内联默认值，纯单测可直接用
    }

    private static void set(BuildCommandExecutor executor, String field, Object value) throws Exception {
        Field f = BuildCommandExecutor.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(executor, value);
    }

    private static int count(List<String> argv, String token) {
        int n = 0;
        for (String s : argv) {
            if (token.equals(s)) {
                n++;
            }
        }
        return n;
    }

    private void touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
    }

    // ==================== JaCoCo 路径与注入串 ====================

    @Test
    void jacocoJarsFollowM2PathConventionWithConfigurableVersion() throws Exception {
        BuildCommandExecutor executor = executor();

        assertEquals(
                "/root/.m2/repository/org/jacoco/org.jacoco.agent/0.8.12/org.jacoco.agent-0.8.12-runtime.jar",
                executor.jacocoAgentJar());
        assertEquals(
                "/root/.m2/repository/org/jacoco/org.jacoco.cli/0.8.12/org.jacoco.cli-0.8.12-nodeps.jar",
                executor.jacocoCliJar());

        set(executor, "jacocoVersion", "0.8.13");
        assertTrue(executor.jacocoAgentJar().contains("0.8.13/org.jacoco.agent-0.8.13-runtime.jar"));
        assertTrue(executor.jacocoCliJar().contains("0.8.13/org.jacoco.cli-0.8.13-nodeps.jar"));
    }

    @Test
    void argLinePropertyIsSingleTokenPointingAtWsExec() {
        String argLine = executor().jacocoArgLineProperty();

        assertTrue(argLine.startsWith("-DargLine=-javaagent:"), "须为单 argv 分列的 -D 属性: " + argLine);
        assertTrue(argLine.contains("/org.jacoco.agent-0.8.12-runtime.jar"));
        assertTrue(argLine.contains("destfile=/ws/jacoco.exec"));
        // append=true：多模块 surefire fork 各自 dump 须合并（append=false 会被后一模块截断）
        assertTrue(argLine.endsWith(",append=true"));
        assertFalse(argLine.contains(" "), "无空白——作为单一 argv 直传无 shell");
    }

    // ==================== 覆盖率报告 argv 拼装 ====================

    @Test
    void coverageArgvShape() throws Exception {
        Path ws = tmp.resolve("teamone-job-x");
        touch(ws.resolve("jacoco.exec"));
        touch(ws.resolve("server/teamone-eng/target/classes/cn/teamone/eng/App.class"));
        touch(ws.resolve("server/teamone-app/target/classes/cn/teamone/app/App.class"));
        // 干扰项：无 .class 的 classes 目录、非 target/classes 的 .class、只有 txt 的 classes
        touch(ws.resolve("server/other/target/classes/empty.txt"));
        touch(ws.resolve("plain/A.class"));

        List<String> argv = executor().buildCoverageArgv(ws, "/var/lib/teamone-ws/teamone-job-x");

        assertNotNull(argv);
        // docker 形态：挂载（宿主路径翻译后的 mountDir）+ m2 卷 + 资源上限 + 工作目录
        assertEquals("docker", argv.get(0));
        assertTrue(argv.contains("/var/lib/teamone-ws/teamone-job-x:/ws"));
        assertTrue(argv.contains("teamone-m2:/root/.m2"));
        assertTrue(argv.contains("--memory=2g"), "M4-INC2 执行安全加固：内存上限");
        assertTrue(argv.contains("--cpus=2"), "M4-INC2 执行安全加固：CPU 上限");
        assertTrue(argv.contains("maven:3.9-eclipse-temurin-17"));
        // cli 报告命令：java -jar {cli} report {exec} --classfiles（可重复）--xml {xml} --quiet
        int javaIdx = argv.indexOf("java");
        assertTrue(javaIdx > 0);
        assertEquals("-jar", argv.get(javaIdx + 1));
        assertEquals("/root/.m2/repository/org/jacoco/org.jacoco.cli/0.8.12/org.jacoco.cli-0.8.12-nodeps.jar",
                argv.get(javaIdx + 2));
        assertEquals("report", argv.get(javaIdx + 3));
        assertEquals("/ws/jacoco.exec", argv.get(javaIdx + 4));
        // 只有两个含 .class 的 target/classes 进 classfiles（干扰项排除）；容器路径 /ws 前缀 + / 分隔
        assertEquals(2, count(argv, "--classfiles"));
        assertTrue(argv.contains("/ws/server/teamone-eng/target/classes"));
        assertTrue(argv.contains("/ws/server/teamone-app/target/classes"));
        // 结尾：--xml /ws/jacoco.xml --quiet
        assertEquals("--xml", argv.get(argv.size() - 3));
        assertEquals("/ws/jacoco.xml", argv.get(argv.size() - 2));
        assertEquals("--quiet", argv.get(argv.size() - 1));
    }

    @Test
    void coverageArgvNullWhenExecMissing() throws Exception {
        Path ws = tmp.resolve("teamone-job-x");
        touch(ws.resolve("server/teamone-eng/target/classes/A.class")); // 有 classfiles 但无 exec

        assertNull(executor().buildCoverageArgv(ws, ws.toString()), "exec 缺失（agent 未注入/被 pom 覆盖）→降级");

        BuildCommandExecutor.Outcome out = executor().generateCoverageReport(ws);
        assertFalse(out.success());
        assertTrue(out.errorMsg().contains("jacoco.exec 缺失"));
        assertTrue(out.errorMsg().contains("覆盖率降级"));
    }

    @Test
    void coverageArgvNullWhenNoClassfiles() throws Exception {
        Path ws = tmp.resolve("teamone-job-x");
        touch(ws.resolve("jacoco.exec"));
        touch(ws.resolve("server/x/target/classes/only.txt")); // classes 目录无 .class

        assertNull(executor().buildCoverageArgv(ws, ws.toString()), "无有效 classfiles→降级");

        BuildCommandExecutor.Outcome out = executor().generateCoverageReport(ws);
        assertFalse(out.success());
        assertTrue(out.errorMsg().contains("无含 .class 的 target/classes"));
    }

    @Test
    void classfileDirsCappedAt32() throws Exception {
        Path ws = tmp.resolve("teamone-job-x");
        touch(ws.resolve("jacoco.exec"));
        for (int i = 0; i < 40; i++) {
            touch(ws.resolve(String.format("m%02d/target/classes/A.class", i)));
        }

        List<String> dirs = BuildCommandExecutor.classfileDirs(ws);

        assertEquals(BuildCommandExecutor.MAX_CLASSFILE_DIRS, dirs.size(), "超多模块仓库截 32 防 argv 爆");
        assertTrue(dirs.stream().allMatch(d -> d.startsWith("/ws/m") && d.endsWith("/target/classes")));
    }

    @Test
    void classfileDirPathsUseForwardSlashesRegardlessOfHostFs() throws IOException {
        Path ws = tmp.resolve("teamone-job-x");
        touch(ws.resolve("a/b/target/classes/X.class"));

        List<String> dirs = BuildCommandExecutor.classfileDirs(ws);

        assertEquals(List.of("/ws/a/b/target/classes"), dirs, "Windows 宿主开发机上分隔符也归一为 /");
    }
}
