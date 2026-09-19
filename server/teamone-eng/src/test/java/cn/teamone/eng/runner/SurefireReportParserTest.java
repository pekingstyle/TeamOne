package cn.teamone.eng.runner;

import cn.teamone.eng.app.PipelineService.TestSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SurefireReportParser 单测（M4-INC1 R8 门禁真实化）：临时目录造两个 TEST-*.xml 聚合 /
 * 无报告不动门禁 / 非 surefire 文件忽略 / 坏 XML 单文件跳过。纯文件无 Spring。
 *
 * @author Ivan Yang, 2026-09-19
 */
class SurefireReportParserTest {

    @TempDir
    Path workdir;

    private void writeReport(String moduleDir, String fileName, int tests, int errors, int failures, int skipped)
            throws IOException {
        Path dir = workdir.resolve(moduleDir).resolve("target").resolve("surefire-reports");
        Files.createDirectories(dir);
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                           name="cn.teamone.demo.DemoTest" time="0.042" tests="%d" errors="%d" failures="%d" skipped="%d">
                  <testcase name="ok" classname="cn.teamone.demo.DemoTest" time="0.001"/>
                </testsuite>
                """.formatted(tests, errors, failures, skipped);
        Files.writeString(dir.resolve(fileName), xml);
    }

    @Test
    void aggregates_twoReportFiles_acrossModules() throws IOException {
        // 两个模块各一份报告（递归扫描 **/target/surefire-reports/TEST-*.xml）
        writeReport("server", "TEST-cn.teamone.demo.A.xml", 10, 1, 2, 1);
        writeReport("server/child", "TEST-cn.teamone.demo.B.xml", 5, 0, 0, 0);
        TestSummary s = SurefireReportParser.parse(workdir);
        assertTrue(s.hasReports());
        assertEquals(2, s.reportFiles());
        assertEquals(15, s.tests());
        assertEquals(1, s.errors());
        assertEquals(2, s.failures());
        assertEquals(1, s.skipped());
        assertFalse(s.gatePassed()); // errors+failures>0
    }

    @Test
    void allGreen_summaryPassesGate() throws IOException {
        writeReport("server", "TEST-cn.teamone.demo.A.xml", 8, 0, 0, 0);
        TestSummary s = SurefireReportParser.parse(workdir);
        assertTrue(s.gatePassed()); // tests>0 且零错误零失败
        assertEquals(8, s.tests());
    }

    @Test
    void noReports_emptySummary_gateUntouched() {
        TestSummary s = SurefireReportParser.parse(workdir); // 空目录
        assertFalse(s.hasReports());
        assertEquals(0, s.tests());
        assertEquals(0, s.reportFiles());
    }

    @Test
    void missingRootDir_emptySummary() {
        TestSummary s = SurefireReportParser.parse(workdir.resolve("no-such-dir"));
        assertFalse(s.hasReports());
    }

    @Test
    void nonSurefireFiles_ignored_badXmlSkipped() throws IOException {
        // 非 TEST-*.xml 的同名目录内容（如 txt 输出）不得计入
        writeReport("server", "TEST-cn.teamone.demo.A.xml", 4, 0, 0, 0);
        Files.writeString(workdir.resolve("server").resolve("target")
                .resolve("surefire-reports").resolve("cn.teamone.demo.A.txt"), "Tests run: 4");
        // 坏 XML：解析失败单文件跳过，不拖垮聚合
        Path badDir = workdir.resolve("server2").resolve("target").resolve("surefire-reports");
        Files.createDirectories(badDir);
        Files.writeString(badDir.resolve("TEST-broken.xml"), "not-xml <<<");

        TestSummary s = SurefireReportParser.parse(workdir);
        assertEquals(1, s.reportFiles());
        assertEquals(4, s.tests());
    }

    @Test
    void reportOutsideTargetDir_ignored() throws IOException {
        // surefire-reports 但不在 target/ 下——不匹配递归形态，忽略
        Path dir = workdir.resolve("m").resolve("surefire-reports");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("TEST-x.xml"), "<testsuite name=\"x\" tests=\"3\"/>");
        assertEquals(0, SurefireReportParser.parse(workdir).reportFiles());
    }
}
