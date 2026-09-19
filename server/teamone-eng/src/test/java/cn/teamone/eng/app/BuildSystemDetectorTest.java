package cn.teamone.eng.app;

import cn.teamone.eng.infra.git.GitPort;
import cn.teamone.eng.infra.git.GitTreeItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BuildSystemDetector 单测（M4-INC1 §2 识别矩阵最小版）：特征命中 / 层级 / Maven 优先 /
 * Dockerfile 不计构建 / 空仓与 GitPort 异常降级 unknown。纯 Mockito 无 Spring。
 *
 * @author Ivan Yang, 2026-09-19
 */
class BuildSystemDetectorTest {

    private GitPort gitPort;
    private BuildSystemDetector detector;

    @BeforeEach
    void setUp() {
        gitPort = mock(GitPort.class);
        detector = new BuildSystemDetector(gitPort);
    }

    private static GitTreeItem dir(String name) {
        return new GitTreeItem("040000", "tree", "0beef", null, name, name);
    }

    private static GitTreeItem file(String name) {
        return new GitTreeItem("100644", "blob", "dead", 1L, name, name);
    }

    @Test
    void rootPom_detectedAsMavenAtRoot() {
        BuildSystemDetector.Result r = BuildSystemDetector.scan(
                List.of(dir("web"), file("pom.xml"), file("Dockerfile")), sub -> List.of(file("package.json")));
        assertEquals("maven", r.buildSystem());
        assertEquals("", r.workdir()); // 根命中：workdir=根层
    }

    @Test
    void serverPom_detectedAsMavenInSubdir() {
        BuildSystemDetector.Result r = BuildSystemDetector.scan(
                List.of(dir("server"), dir("web"), file("Dockerfile")),
                sub -> "server".equals(sub) ? List.of(file("pom.xml"), file("src")) : List.of(file("package.json")));
        assertEquals("maven", r.buildSystem());
        assertEquals("server", r.workdir()); // workdir=manifest 所在层
    }

    @Test
    void webPackageJson_detectedAsNpm() {
        BuildSystemDetector.Result r = BuildSystemDetector.scan(
                List.of(dir("web"), dir("docs")),
                sub -> "web".equals(sub) ? List.of(file("package.json"), file("pnpm-lock.yaml")) : List.of(file("a.md")));
        assertEquals("npm", r.buildSystem());
        assertEquals("web", r.workdir());
    }

    @Test
    void rootPackageJson_detectedAsNpmAtRoot() {
        BuildSystemDetector.Result r = BuildSystemDetector.scan(
                List.of(file("package.json"), file("src.ts")), sub -> List.of());
        assertEquals("npm", r.buildSystem());
        assertEquals("", r.workdir());
    }

    @Test
    void mavenWinsOverNpm_whenBothPresent() {
        // §2.2 B1：pom 与 package 并存（同级子目录）→ Maven 优先
        BuildSystemDetector.Result r = BuildSystemDetector.scan(
                List.of(dir("server"), dir("web")),
                sub -> "server".equals(sub) ? List.of(file("pom.xml")) : List.of(file("package.json")));
        assertEquals("maven", r.buildSystem());
        assertEquals("server", r.workdir());
    }

    @Test
    void dockerfileOnly_isUnknown_notCountedAsBuild() {
        // B3 纯镜像封装：Dockerfile 仅标记不计构建，最小版 → unknown
        BuildSystemDetector.Result r = BuildSystemDetector.scan(
                List.of(file("Dockerfile"), file("README.md")), sub -> List.of());
        assertEquals("unknown", r.buildSystem());
        assertEquals("", r.workdir());
    }

    @Test
    void emptyTree_isUnknown() {
        assertEquals("unknown", BuildSystemDetector.scan(List.of(), sub -> List.of()).buildSystem());
    }

    @Test
    void detect_degradesToUnknown_whenGitPortFails() {
        when(gitPort.tree(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("branch missing"));
        BuildSystemDetector.Result r = detector.detect("teamone/teamone.git", "nope");
        assertEquals("unknown", r.buildSystem());
        assertEquals("", r.workdir());
    }

    @Test
    void detect_subdirFailure_tolerated() {
        // 根无特征、唯一子目录列举失败 → 不拖垮整体，返回 unknown（不抛出）
        when(gitPort.tree(eq("teamone/teamone.git"), eq("main"), eq("")))
                .thenReturn(List.of(dir("server")));
        when(gitPort.tree(eq("teamone/teamone.git"), eq("main"), eq("server")))
                .thenThrow(new RuntimeException("boom"));
        assertEquals("unknown", detector.detect("teamone/teamone.git", "main").buildSystem());
    }
}
