package cn.teamone.eng.api;

import cn.teamone.eng.domain.CommitWorkItem;
import cn.teamone.eng.repo.CommitWorkItemRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提交查询端点（05 §3.3：GET /api/v1/commits?workItemKey=）。
 *
 * <p>登录态（SecurityConfig /api/** 统一 JWT）；数据源为 eng 自有表 commit_work_item
 * （push hook 解析落库），按 created_at DESC 返回——工作项详情页「关联提交」清单用。
 * eng 零 prd 依赖：key 文本化，未命中返回空清单（不校验工作项存在性）。</p>
 *
 * @author Ivan Yang, 2026-09-12
 */
@RestController
@RequestMapping("/api/v1/commits")
public class CommitQueryController {

    private final CommitWorkItemRepository commitWorkItems;

    public CommitQueryController(CommitWorkItemRepository commitWorkItems) {
        this.commitWorkItems = commitWorkItems;
    }

    @GetMapping
    public Map<String, Object> byWorkItem(@RequestParam(required = false) String workItemKey) {
        if (workItemKey == null || workItemKey.isBlank()) {
            throw new BusinessException(ErrorCode.PLT_4000, "workItemKey 必填（如 D-88）");
        }
        List<CommitWorkItem> rows =
                commitWorkItems.findByWorkItemKeyOrderByCreatedAtDesc(workItemKey.trim());
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (CommitWorkItem row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("repo", row.getRepoKey());
            item.put("sha", row.getCommitSha());
            item.put("authorName", row.getAuthorName());
            item.put("authorEmail", row.getAuthorEmail());
            item.put("committedAt", row.getCommittedAt());
            item.put("subject", row.getSubject());
            items.add(item);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("workItemKey", workItemKey.trim());
        res.put("count", items.size());
        res.put("items", items);
        return res;
    }
}
