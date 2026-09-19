package cn.teamone.eng.infra.git;

import java.time.Instant;

/**
 * Git 标签投影（GitPort 接口契约，M2-INC-3 U3）。
 *
 * @param name          标签名（short refname，如 v1.0.0）
 * @param commitSha     指向的提交 sha
 * @param committedAt   标签/提交时间
 * @param message       标签说明
 * @author Ivan Yang, 2026-09-13
 */
public record GitTag(String name, String commitSha, Instant committedAt, String message) {
}
