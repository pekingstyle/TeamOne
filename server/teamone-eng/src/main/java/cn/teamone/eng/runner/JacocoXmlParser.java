package cn.teamone.eng.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * JaCoCo XML 报告解析器（M4-INC2 · R8 门禁真实化，docs/v2/11 §3.4「统一算法」）。
 *
 * <p>解析工具容器产出的 {@code jacoco.xml}（cli report --xml）：
 * <ul>
 *   <li>全局行覆盖率：report 根级 {@code <counter type="LINE" missed covered>} 汇总 →
 *     {@code covered/(missed+covered)*100}（counter 缺失/总数 0 兜底为 0.0——空报告不冒充有覆盖）。</li>
 *   <li>逐源文件行集：{@code <package>/<sourcefile>/<line nr mi ci mb cb>}——jacoco 的行号天然挂在
 *     sourcefile 上（<b>内部类归一</b>：{@code Foo$Bar} 的行并入 {@code Foo.java} 的 sourcefile
 *     列表），键即归一化后的 {@code src/main/java} 相对路径（如
 *     {@code cn/teamone/eng/runner/PipelineRunner.java}，包名斜杠形 + 源文件名）——与
 *     {@link DiffLineExtractor} 的变更行键同构，供 patch 覆盖率交集。行「已覆盖」口径：
 *     {@code ci+cb>0}（指令或分支任一命中）；「可执行行」= 报告列出的全部行
 *     （jacoco 只列含字节码的行，mi+ci+mb+cb 必 &gt;0）。</li>
 * </ul></p>
 *
 * <p>解析失败/文件缺失返回 {@code null}——调用方（Runner）按覆盖率链路降级处理（不误判、不阻塞）。
 * XXE 加固：JaCoCo 报告自带 {@code <!DOCTYPE report … "report.dtd">}，不能禁 DOCTYPE 本身，
 * 改为禁外部 DTD 加载与实体扩展（报告文件来自仓库工作区，按不可信输入处理）。</p>
 *
 * @author Ivan Yang, 2026-09-20
 */
public final class JacocoXmlParser {

    private static final Logger log = LoggerFactory.getLogger(JacocoXmlParser.class);

    private JacocoXmlParser() {
    }

    /**
     * 单源文件行集。
     *
     * @param executableLines 可执行行号集（jacoco 报告列出的全部行）
     * @param coveredLines    已覆盖行号集（ci+cb&gt;0）
     */
    public record SourceLines(Set<Integer> executableLines, Set<Integer> coveredLines) {}

    /**
     * 解析结果。
     *
     * @param totalPercent 全局行覆盖率百分数（counter 缺失兜底 0.0）
     * @param sources      归一化源路径 → 行集（键形如 p/Foo.java）
     */
    public record Result(double totalPercent, Map<String, SourceLines> sources) {}

    /**
     * 解析 jacoco.xml。
     *
     * @param xml 报告文件（不存在/坏文件返回 null）
     * @return 解析结果；无法解析时 null（调用方降级）
     */
    public static Result parse(Path xml) {
        if (xml == null || !Files.isRegularFile(xml)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(xml)) {
            Document doc = newFactory().newDocumentBuilder().parse(in);
            Element report = doc.getDocumentElement();
            if (!"report".equals(report.getNodeName())) {
                return null;
            }
            int missed = 0;
            int covered = 0;
            for (Element counter : childElements(report, "counter")) {
                if ("LINE".equals(counter.getAttribute("type"))) {
                    missed = intAttr(counter, "missed");
                    covered = intAttr(counter, "covered");
                }
            }
            double total = (missed + covered) == 0 ? 0.0 : covered * 100.0 / (missed + covered);
            Map<String, SourceLines> sources = new LinkedHashMap<>();
            for (Element pkg : childElements(report, "package")) {
                String pkgName = pkg.getAttribute("name").replace('\\', '/');
                for (Element sourcefile : childElements(pkg, "sourcefile")) {
                    SourceLines lines = parseSourceLines(sourcefile);
                    if (lines == null || lines.executableLines().isEmpty()) {
                        continue;
                    }
                    String key = pkgName.isEmpty()
                            ? sourcefile.getAttribute("name")
                            : pkgName + "/" + sourcefile.getAttribute("name");
                    sources.put(key, lines);
                }
            }
            return new Result(total, sources);
        } catch (Exception e) {
            log.warn("[pipeline-runner] jacoco.xml 解析失败（{}）: {}", xml, e.getMessage());
            return null;
        }
    }

    /** 单 sourcefile 的行集（nr&gt;0 才计；空/坏属性行跳过） */
    private static SourceLines parseSourceLines(Element sourcefile) {
        Set<Integer> executable = new TreeSet<>();
        Set<Integer> covered = new TreeSet<>();
        for (Element line : childElements(sourcefile, "line")) {
            int nr = intAttr(line, "nr");
            if (nr <= 0) {
                continue;
            }
            executable.add(nr);
            if (intAttr(line, "ci") + intAttr(line, "cb") > 0) {
                covered.add(nr);
            }
        }
        return new SourceLines(executable, covered);
    }

    /** 直接子元素列表（只取一层——report 级与 package 级 counter 分层语义不同） */
    private static List<Element> childElements(Element parent, String tagName) {
        List<Element> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element e && tagName.equals(e.getTagName())) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * XML 解析器加固：JaCoCo 报告自带 DOCTYPE（report.dtd）——不禁 DOCTYPE，
     * 禁外部 DTD 加载与实体扩展。
     */
    private static DocumentBuilderFactory newFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        return factory;
    }

    /** 数值属性容错读取（缺失/非数字按 0——counter 缺失兜底） */
    private static int intAttr(Element el, String name) {
        String v = el.getAttribute(name);
        if (v == null || v.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
