package cn.teamone.eng.infra.git;

import java.time.Instant;

/**
 * Git 单行 Blame 归属元数据。
 * <p>
 * 对应 git blame --porcelain 解析所得的单行代码历史溯源快照，
 * 记录该行最近一次被修改的 Commit SHA、作者信息、提交时间与源码内容。
 * </p>
 *
 * @param lineNo      行号（从 1 开始）
 * @param commitSha   产生该行的完整 40 位 Commit SHA
 * @param commitShort 7 位短 SHA
 * @param authorName  提交作者姓名
 * @param authorEmail 提交作者邮箱
 * @param committedAt 提交时间
 * @param summary     提交信息标题简述
 * @param lineContent 该行源码内容
 * @author Ivan Yang, 2026-09-13
 */
public record GitBlameLine(
        int lineNo,
        String commitSha,
        String commitShort,
        String authorName,
        String authorEmail,
        Instant committedAt,
        String summary,
        String lineContent
) {}
