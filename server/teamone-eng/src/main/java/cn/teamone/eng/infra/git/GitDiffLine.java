package cn.teamone.eng.infra.git;

/**
 * 单行 Diff 投影（对应前端 FileDiff.lines）。
 *
 * @param oldNo 旧文件行号（add 时为空）
 * @param newNo 新文件行号（del 时为空）
 * @param type  行类型：add / del / ctx
 * @param text  行内容
 */
public record GitDiffLine(
        Integer oldNo,
        Integer newNo,
        String type,
        String text
) {}
