package cn.teamone.eng.runner;

import cn.teamone.eng.app.PipelineService.TestSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * surefire 报告聚合解析器（M4-INC1 · R8 门禁真实化最小版，docs/v2/11 §3.4）。
 *
 * <p>递归扫描工作区任意层级目录下的 {@code target/surefire-reports/TEST-*.xml}（maven 体系；
 * npm 跳过），
 * 汇总根元素 {@code <testsuite>} 的 tests/errors/failures/skipped 属性。识别失败/无报告
 * 返回 {@code reportFiles=0} 的空摘要——调用方据此不动 MR 门禁，仅在 stages summary 注记
 * （门禁不误判通过）。坏文件单文件跳过，不拖垮整轮聚合。</p>
 *
 * @author Ivan Yang, 2026-09-19
 */
public final class SurefireReportParser {

    private static final Logger log = LoggerFactory.getLogger(SurefireReportParser.class);

    /** 相对路径形态：<任意目录>/target/surefire-reports/TEST-xxx.xml（workdir 根下无目录前缀） */
    private static final Pattern REPORT_PATH =
            Pattern.compile("(?:[^/]+/)*target/surefire-reports/TEST-[^/]+\\.xml");

    private SurefireReportParser() {
    }

    /**
     * 解析指定根目录（通常为工作区内的构建层 workdir）下的全部 surefire 报告。
     *
     * @param root 扫描根目录（不存在/不可读返回空摘要）
     * @return 聚合摘要（无报告时 reportFiles=0）
     */
    public static TestSummary parse(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return new TestSummary(0, 0, 0, 0, 0);
        }
        List<Path> reports;
        try (Stream<Path> walk = Files.walk(root)) {
            reports = walk.filter(Files::isRegularFile)
                    .filter(p -> REPORT_PATH.matcher(root.relativize(p).toString()
                            .replace('\\', '/')).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("[pipeline-runner] surefire 报告扫描失败（{}）: {}", root, e.getMessage());
            return new TestSummary(0, 0, 0, 0, 0);
        }
        int tests = 0;
        int errors = 0;
        int failures = 0;
        int skipped = 0;
        int parsed = 0;
        for (Path report : reports) {
            int[] counts = parseOne(report);
            if (counts == null) {
                continue; // 坏文件跳过：聚合按可解析文件计，不误判也不崩
            }
            tests += counts[0];
            errors += counts[1];
            failures += counts[2];
            skipped += counts[3];
            parsed++;
        }
        return new TestSummary(tests, errors, failures, skipped, parsed);
    }

    /** 单文件解析：testsuite 根属性 tests/errors/failures/skipped；坏文件返回 null */
    private static int[] parseOne(Path report) {
        try (InputStream in = Files.newInputStream(report)) {
            Document doc = newFactory().newDocumentBuilder().parse(in);
            Element suite = doc.getDocumentElement();
            if (!"testsuite".equals(suite.getNodeName())) {
                return null;
            }
            return new int[]{
                    intAttr(suite, "tests"),
                    intAttr(suite, "errors"),
                    intAttr(suite, "failures"),
                    intAttr(suite, "skipped")
            };
        } catch (Exception e) {
            log.warn("[pipeline-runner] surefire 报告解析失败（跳过 {}）: {}", report, e.getMessage());
            return null;
        }
    }

    /** XML 解析器加固：禁 DCD/外部实体（报告文件来自仓库工作区，按不可信输入处理） */
    private static DocumentBuilderFactory newFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        return factory;
    }

    /** testsuite 数值属性（缺失/非数字按 0——surefire 老版本可能省略 skipped） */
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
