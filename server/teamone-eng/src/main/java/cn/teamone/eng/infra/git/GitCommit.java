package cn.teamone.eng.infra.git;

import java.time.Instant;

/**
 * Git 提交投影（GitPort 输出契约，07 §2.0「modules/git → Java 重写」的解析结果）。
 *
 * @param sha         完整 40 位 commit sha
 * @param authorName  作者名（git %an）
 * @param authorEmail 作者邮箱（git %ae，当前 log format 未取可留 null）
 * @param committedAt 作者时间（git %aI 严格 ISO 8601）
 * @param subject     单行标题（git %s）
 * @param body        正文（git %b，与 subject 同为 refs #KEY 解析源）
 * @author Ivan Yang, 2026-09-12
 */
public record GitCommit(String sha, String authorName, String authorEmail,
                        Instant committedAt, String subject, String body) {
}
