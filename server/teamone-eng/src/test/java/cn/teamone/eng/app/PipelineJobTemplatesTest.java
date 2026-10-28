package cn.teamone.eng.app;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PipelineJobTemplates 单测（M4-INC1 §3.3 分体系模板）：任务口径的命令模板逐字钉死 +
 * 空白分列安全性（无 shell）。纯静态无 Spring。
 *
 * @author Ivan Yang, 2026-09-19
 */
class PipelineJobTemplatesTest {

    @Test
    void mavenBuild_withSubdirWorkdir() {
        assertEquals("mvn -q -f server/pom.xml package -DskipTests",
                PipelineJobTemplates.cmd("maven", "build", "server"));
    }

    @Test
    void mavenBuild_atRoot() {
        assertEquals("mvn -q -f pom.xml package -DskipTests",
                PipelineJobTemplates.cmd("maven", "build", ""));
        assertEquals("mvn -q -f pom.xml package -DskipTests",
                PipelineJobTemplates.cmd("maven", "build", null));
    }

    @Test
    void mavenTest() {
        assertEquals("mvn -q -f server/pom.xml test",
                PipelineJobTemplates.cmd("maven", "test", "server"));
        assertEquals("mvn -q -f pom.xml test",
                PipelineJobTemplates.cmd("maven", "test", ""));
    }

    @Test
    void npmBuildAndTest() {
        assertEquals("npm ci --prefix web", PipelineJobTemplates.cmd("npm", "build", "web"));
        assertEquals("npm test --prefix web", PipelineJobTemplates.cmd("npm", "test", "web"));
        assertEquals("npm ci", PipelineJobTemplates.cmd("npm", "build", ""));
        assertEquals("npm test", PipelineJobTemplates.cmd("npm", "test", null));
    }

    @Test
    void jobNames() {
        assertEquals("构建", PipelineJobTemplates.jobName("build"));
        // ⑧ 部署作业名映射在 jobNames 用例尾部断言
        assertEquals("单测", PipelineJobTemplates.jobName("test"));
    }

    @Test
    void split_whitespaceOnly_noQuotingNeeded() {
        List<String> argv = PipelineJobTemplates.split("mvn -q -f server/pom.xml package -DskipTests");
        assertEquals(List.of("mvn", "-q", "-f", "server/pom.xml", "package", "-DskipTests"), argv);
    }

    @Test
    void unknownStage_rejected() {
        // ⑧ deploy 为合法阶段：自足式 maven package（独立工作区，Runner 收集制品+登记）
        assertEquals("mvn -q -f pom.xml package -DskipTests", PipelineJobTemplates.cmd("maven", "deploy", ""));
        assertEquals("部署", PipelineJobTemplates.jobName("deploy"));
        assertThrows(IllegalArgumentException.class, () -> PipelineJobTemplates.cmd("maven", "nonsense", ""));
    }
}
