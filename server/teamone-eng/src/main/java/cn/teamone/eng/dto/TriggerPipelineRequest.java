package cn.teamone.eng.dto;

import java.util.UUID;

/**
 * 触发流水线运行请求 DTO。
 * <p>
 * 用于在前端点击运行、Git Webhook Push 事件到达、或 MR 创建与更新时触发 CI 流水线。
 * </p>
 *
 * @param branch    目标运行分支名（如 "main" 或 "feature/demo"），缺省时自动使用仓库默认分支
 * @param commitSha 指定触发的 Git 提交完整或短 SHA，缺省时自动解析该分支最新 HEAD Commit
 * @param trigger   流水线触发类型：
 *                  <ul>
 *                    <li>{@code manual}：开发者在控制台手动点击「运行流水线」触发</li>
 *                    <li>{@code push}：Git 代码推送到远端裸仓后由 Hook 自动触发</li>
 *                    <li>{@code mr}：合并请求创建或提交更新时触发门禁流水线</li>
 *                    <li>{@code schedule}：定时任务触发（如夜间全量回归）</li>
 *                  </ul>
 * @param mrId      关联的合并请求（MR）ID；若提供，流水线执行完成（成功）后会自动将单测通过性与覆盖率数据回填至该 MR 门禁并解除合并拦截
 *
 * @author Ivan Yang, 2026-09-13
 */
public record TriggerPipelineRequest(
        String branch,
        String commitSha,
        String trigger,
        UUID mrId
) {}

