package cn.teamone.eng.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JacocoXmlParser 单测（M4-INC2）：样例 xml 全局 LINE 汇总与逐源文件行集、内部类归一
 * （Foo/Foo$Bar 并入 Foo.java 的 sourcefile 行集）、counter 缺失兜底 0.0、DOCTYPE 容忍
 * （jacoco 报告自带 report.dtd 引用且不触发外部加载）、文件缺失/坏文件返回 null。
 *
 * @author Ivan Yang, 2026-09-20
 */
class JacocoXmlParserTest {

    @TempDir
    Path tmp;

    /** 样例报告（贴近 jacoco cli 真实产出形状：DOCTYPE + package/class/sourcefile/line/counter） */
    private static final String SAMPLE = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="teamone">
              <sessioninfo id="sid" start="1" dump="2"/>
              <package name="cn/teamone/eng/runner">
                <class name="cn/teamone/eng/runner/Foo" sourcefilename="Foo.java">
                  <method name="&lt;init&gt;" desc="()V" line="8"/>
                  <counter type="LINE" missed="1" covered="2"/>
                </class>
                <class name="cn/teamone/eng/runner/Foo$Inner" sourcefilename="Foo.java">
                  <counter type="LINE" missed="1" covered="2"/>
                </class>
                <sourcefile name="Foo.java">
                  <line nr="10" mi="0" ci="2" mb="0" cb="0"/>
                  <line nr="11" mi="3" ci="0" mb="0" cb="0"/>
                  <line nr="20" mi="0" ci="0" mb="1" cb="1"/>
                  <line nr="21" mi="0" ci="1" mb="0" cb="0"/>
                  <counter type="LINE" missed="1" covered="3"/>
                </sourcefile>
              </package>
              <package name="cn/teamone/eng/app">
                <sourcefile name="Bar.java">
                  <line nr="5" mi="0" ci="4" mb="0" cb="0"/>
                </sourcefile>
              </package>
              <counter type="INSTRUCTION" missed="100" covered="200"/>
              <counter type="BRANCH" missed="1" covered="3"/>
              <counter type="LINE" missed="25" covered="75"/>
            </report>
            """;

    private Path write(String content) throws Exception {
        Path xml = tmp.resolve("jacoco.xml");
        Files.writeString(xml, content);
        return xml;
    }

    @Test
    void parsesGlobalLineCounterFromReportLevel() throws Exception {
        JacocoXmlParser.Result result = JacocoXmlParser.parse(write(SAMPLE));

        assertNotNull(result);
        // 全局只认 report 根级 counter（package/class 级的 1/3 不计入）；75/(25+75)=75.0
        assertEquals(75.0, result.totalPercent(), 0.0001);
    }

    @Test
    void innerClassesNormalizeIntoMainSourceFileKey() throws Exception {
        JacocoXmlParser.Result result = JacocoXmlParser.parse(write(SAMPLE));

        // Foo 与 Foo$Inner 都指向 Foo.java：sourcefile 行集天然归一，键=包路径+主类源文件名
        Set<String> keys = result.sources().keySet();
        assertTrue(keys.contains("cn/teamone/eng/runner/Foo.java"), "键应为归一化的主类源路径: " + keys);
        assertTrue(keys.contains("cn/teamone/eng/app/Bar.java"));
        assertEquals(2, result.sources().size());
    }

    @Test
    void coveredLineMeansInstructionOrBranchHit() throws Exception {
        JacocoXmlParser.Result result = JacocoXmlParser.parse(write(SAMPLE));

        JacocoXmlParser.SourceLines foo = result.sources().get("cn/teamone/eng/runner/Foo.java");
        assertNotNull(foo);
        // 全部列出行都是可执行行；ci+cb>0 才算已覆盖（20 行 ci=0 但 cb=1 → 覆盖；11 行全 miss → 未覆盖）
        assertEquals(Set.of(10, 11, 20, 21), foo.executableLines());
        assertEquals(Set.of(10, 20, 21), foo.coveredLines());
    }

    @Test
    void missingLineCounterFallsBackToZeroButStillParsesSources() throws Exception {
        String noCounter = """
                <?xml version="1.0"?>
                <report name="x">
                  <package name="p">
                    <sourcefile name="A.java">
                      <line nr="1" mi="0" ci="1" mb="0" cb="0"/>
                    </sourcefile>
                  </package>
                </report>
                """;
        JacocoXmlParser.Result result = JacocoXmlParser.parse(write(noCounter));

        assertNotNull(result);
        assertEquals(0.0, result.totalPercent(), 0.0001, "counter 缺失兜底 0.0");
        assertEquals(Set.of(1), result.sources().get("p/A.java").coveredLines());
    }

    @Test
    void missingOrMalformedFileReturnsNull() throws Exception {
        assertNull(JacocoXmlParser.parse(tmp.resolve("nope.xml")));
        Path bad = write("<report name='x'><!-- 未闭合");
        assertNull(JacocoXmlParser.parse(bad));
        Path notReport = write("<other/>");
        assertNull(JacocoXmlParser.parse(notReport));
    }

    @Test
    void defaultPackageKeyHasNoLeadingSlash() throws Exception {
        String defaultPkg = """
                <?xml version="1.0"?>
                <report name="x">
                  <package name="">
                    <sourcefile name="Top.java">
                      <line nr="3" mi="0" ci="1" mb="0" cb="0"/>
                    </sourcefile>
                  </package>
                  <counter type="LINE" missed="0" covered="1"/>
                </report>
                """;
        JacocoXmlParser.Result result = JacocoXmlParser.parse(write(defaultPkg));

        assertNotNull(result);
        assertEquals(100.0, result.totalPercent(), 0.0001);
        assertTrue(result.sources().containsKey("Top.java"), "默认包键=裸文件名");
    }

    /** Map 视角给交集用（等价断言辅助）：键集与 JacocoXmlParser.Result.sources 一致 */
    @Test
    void sourcesMapIsUsableForIntersection() throws Exception {
        JacocoXmlParser.Result result = JacocoXmlParser.parse(write(SAMPLE));
        Map<String, JacocoXmlParser.SourceLines> sources = result.sources();
        JacocoXmlParser.SourceLines bar = sources.get("cn/teamone/eng/app/Bar.java");
        assertNotNull(bar);
        assertEquals(Set.of(5), bar.executableLines());
        assertEquals(Set.of(5), bar.coveredLines());
    }
}
