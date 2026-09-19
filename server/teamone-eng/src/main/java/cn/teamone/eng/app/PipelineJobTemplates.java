package cn.teamone.eng.app;

import java.util.List;

/**
 * 流水线作业命令模板（docs/v2/11 §3.3 分体系默认模板 · M4-INC1 最小版）。
 *
 * <p>模板是 cmd 列的唯一来源（服务端生成，绝不拼装用户输入）：
 * <ul>
 *   <li>build：maven={@code mvn -q -f <workdir>/pom.xml package -DskipTests}；
 *       npm={@code npm ci --prefix <workdir>}</li>
 *   <li>test：maven={@code mvn -q -f <workdir>/pom.xml test}；npm={@code npm test --prefix <workdir>}</li>
 * </ul>
 * workdir 为 ""（manifest 在仓库根）时退化为 {@code -f pom.xml} / 无 --prefix。
 * workdir 来自 GitPort tree 白名单目录名（无空白字符），模板串可由 Runner
 * 以空白分列成参数数组经 ProcessBuilder 执行（无 shell，注入面封闭）。</p>
 *
 * <p>执行工作目录=导出的工作区根（cmd 内以 workdir 前缀定位构建层）。</p>
 *
 * @author Ivan Yang, 2026-09-19
 */
public final class PipelineJobTemplates {

    private PipelineJobTemplates() {
    }

    /** 作业展示名 */
    public static final String BUILD_JOB_NAME = "构建";
    public static final String TEST_JOB_NAME = "单测";

    /**
     * 按构建体系与阶段生成作业命令。
     *
     * @param buildSystem maven / npm（unknown 体系不建作业，不应走到本方法）
     * @param stage       build / test
     * @param workdir     构建层相对路径（null 视为 ""）
     * @return 命令行文本（空白分列安全）
     */
    public static String cmd(String buildSystem, String stage, String workdir) {
        String dir = (workdir == null) ? "" : workdir.trim();
        boolean maven = "maven".equals(buildSystem);
        if ("build".equals(stage)) {
            return maven
                    ? "mvn -q -f " + (dir.isEmpty() ? "pom.xml" : dir + "/pom.xml") + " package -DskipTests"
                    : "npm ci" + (dir.isEmpty() ? "" : " --prefix " + dir);
        }
        if ("test".equals(stage)) {
            return maven
                    ? "mvn -q -f " + (dir.isEmpty() ? "pom.xml" : dir + "/pom.xml") + " test"
                    : "npm test" + (dir.isEmpty() ? "" : " --prefix " + dir);
        }
        throw new IllegalArgumentException("未知流水线作业阶段: " + stage);
    }

    /**
     * 作业展示名（阶段 → 名称）。
     *
     * @param stage build / test
     * @return 展示名
     */
    public static String jobName(String stage) {
        return "test".equals(stage) ? TEST_JOB_NAME : BUILD_JOB_NAME;
    }

    /**
     * Runner 空白分列（模板串无引号/转义，空白即参数边界）。
     *
     * @param cmd 模板命令行
     * @return ProcessBuilder 参数数组
     */
    public static List<String> split(String cmd) {
        return List.of(cmd.trim().split("\\s+"));
    }
}
