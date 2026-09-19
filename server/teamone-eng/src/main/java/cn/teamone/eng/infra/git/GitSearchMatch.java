package cn.teamone.eng.infra.git;

/**
 * Git 代码全文检索单行命中项（V-21 / U4）。
 * <p>
 * 对应 {@code git grep -n} 在指定提交或分支下匹配到的代码文件路径、行号与源码行内容。
 * </p>
 *
 * @param filePath    相对文件路径（如 "server/pom.xml"）
 * @param lineNo      行号（从 1 开始）
 * @param lineContent 匹配到的代码行内容
 * @author Ivan Yang, 2026-09-13
 */
public record GitSearchMatch(
        String filePath,
        int lineNo,
        String lineContent
) {}
