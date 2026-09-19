package cn.teamone.eng.infra.git;

import java.time.Instant;

/**
 * Git 分支投影（GitPort 接口契约，M2-INC-3 U3）。
 *
 * @param name          分支名（short refname，如 main, dev）
 * @param commitSha     最新提交 40 位 sha
 * @param committedAt   最新提交时间
 * @param commitSubject 最新提交标题
 * @author Ivan Yang, 2026-09-13
 */
public record GitBranch(String name, String commitSha, Instant committedAt, String commitSubject) {
}
