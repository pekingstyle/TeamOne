package cn.teamone.eng.app;

import cn.teamone.eng.infra.git.GitCommit;
import cn.teamone.eng.infra.git.GitPort;
import cn.teamone.eng.repo.CommitWorkItemRepository;
import cn.teamone.platform.infra.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * push 事件处理（07 §2.2 数据链的 eng 侧编排）：
 * <pre>
 * GitPort.logRange → 逐 commit 解析 subject/body 的 refs #KEY（正则 #[A-Z]{1,4}-\d+）
 *   → upsert eng.commit_work_item（ON CONFLICT 三元组 DO NOTHING，重复 push 幂等）
 *   → 命中≥1 条时同事务 outbox.append(git_push, "git.push", {repo,ref,commits:[{sha,keys}],count})
 * </pre>
 *
 * <p>纪律：refs #KEY 未命中工作项<b>静默跳过</b>（不校验 work_item 存在性，eng 零 prd 依赖；
 * 消费方按需取舍）；映射行与 outbox 在同一事务（REQUIRED）同生共死。
 * outbox aggregate_id 需要 UUID 而仓库键是文本——用 repoKey 的名字派生 UUID（v3 deterministic），
 * 同一仓库恒定，投递方可用其做聚合去重/分片。</p>
 *
 * @author Ivan Yang, 2026-09-12
 */
@Service
public class GitHookService {

    private static final Logger log = LoggerFactory.getLogger(GitHookService.class);

    /** refs 关键字：#D-88 / #REQ-12（1~4 位大写前缀 + 数字；大小写敏感，全大写才算引用） */
    static final Pattern WORK_ITEM_KEY = Pattern.compile("#([A-Z]{1,4}-\\d+)");

    private final GitPort git;
    private final CommitWorkItemRepository commitWorkItems;
    private final OutboxWriter outbox;

    public GitHookService(GitPort git, CommitWorkItemRepository commitWorkItems, OutboxWriter outbox) {
        this.git = git;
        this.commitWorkItems = commitWorkItems;
        this.outbox = outbox;
    }

    /** 处理结果（端点响应投影） */
    public record Result(int scanned, int mapped, int rows) {}

    /**
     * 处理一次 push 区间。必须幂等可重放（hook 端不重试，但对账兜底/人工重放会再次进入）。
     *
     * @return scanned=区间提交数；mapped=携带 refs 的提交数；rows=本次实际新插入映射行数
     */
    @Transactional // REQUIRED：映射行 + outbox 同事务（红线 3）
    public Result process(String repoKey, String oldRev, String newRev, String ref) {
        List<GitCommit> range = git.logRange(repoKey, oldRev, newRev);

        List<Map<String, Object>> hits = new ArrayList<>();
        int rows = 0;
        for (GitCommit c : range) {
            Set<String> keys = extractKeys(c.subject(), c.body());
            if (keys.isEmpty()) {
                continue;
            }
            for (String key : keys) {
                rows += commitWorkItems.insertIgnore(repoKey, c.sha(), c.authorName(), c.authorEmail(),
                        c.committedAt(), c.subject(), c.body(), key);
            }
            // payload 投影：sha + keys（不放 author/body 等大字段，事件消费方按需再查）
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("sha", c.sha());
            hit.put("keys", List.copyOf(keys));
            hits.add(hit);
        }

        if (!hits.isEmpty()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("repo", repoKey);
            payload.put("ref", ref);
            payload.put("commits", hits);
            payload.put("count", hits.size()); // 携带 refs 的提交数
            outbox.append("git_push", repoAggregateId(repoKey), "git.push", payload, null);
        }

        log.info("[git-hook] repo={} ref={} scanned={} mapped={} rows={}",
                repoKey, ref, range.size(), hits.size(), rows);
        return new Result(range.size(), hits.size(), rows);
    }

    /** 提取 subject+body 中的全部 refs #KEY（去重、保序；不校验工作项存在性） */
    static LinkedHashSet<String> extractKeys(String subject, String body) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        collectKeys(keys, subject);
        collectKeys(keys, body);
        return keys;
    }

    private static void collectKeys(Set<String> sink, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher m = WORK_ITEM_KEY.matcher(text);
        while (m.find()) {
            sink.add(m.group(1));
        }
    }

    /** 仓库键 → 确定性聚合 id（outbox.aggregate_id 列为 uuid；名字派生保证同仓库恒定） */
    static UUID repoAggregateId(String repoKey) {
        return UUID.nameUUIDFromBytes(("eng.repository:" + repoKey).getBytes(StandardCharsets.UTF_8));
    }
}
