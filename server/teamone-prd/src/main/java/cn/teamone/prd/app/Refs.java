package cn.teamone.prd.app;

import cn.teamone.prd.domain.Component;
import cn.teamone.prd.domain.Product;
import cn.teamone.prd.domain.Release;
import cn.teamone.prd.domain.RoadmapItem;
import cn.teamone.prd.domain.Sprint;
import cn.teamone.prd.domain.StrategicGoal;
import cn.teamone.prd.domain.WorkItem;
import cn.teamone.prd.repo.ComponentRepository;
import cn.teamone.prd.repo.ProductRepository;
import cn.teamone.prd.repo.ReleaseRepository;
import cn.teamone.prd.repo.RoadmapItemRepository;
import cn.teamone.prd.repo.SprintRepository;
import cn.teamone.prd.repo.StrategicGoalRepository;
import cn.teamone.prd.repo.WorkItemRepository;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.shared.api.BusinessException;
import cn.teamone.shared.api.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 引用解析（id 或业务键 → 实体）。URL/请求体允许直接写业务键（D-88 / v2.4.0），
 * 未命中抛 {@link ErrorCode#PLT_4040}（404）。
 */
@Service
public class Refs {

    private final WorkItemRepository workItems;
    private final ReleaseRepository releases;
    private final ProductRepository products;
    private final ComponentRepository components;
    private final SprintRepository sprints;
    private final StrategicGoalRepository goals;
    private final AppUserRepository users;
    private final RoadmapItemRepository roadmaps;

    public Refs(WorkItemRepository workItems, ReleaseRepository releases, ProductRepository products,
                ComponentRepository components, SprintRepository sprints, StrategicGoalRepository goals,
                AppUserRepository users, RoadmapItemRepository roadmaps) {
        this.workItems = workItems;
        this.releases = releases;
        this.products = products;
        this.components = components;
        this.sprints = sprints;
        this.goals = goals;
        this.users = users;
        this.roadmaps = roadmaps;
    }

    public WorkItem workItem(String idOrKey) {
        WorkItem wi = looksUuid(idOrKey)
                ? workItems.findById(UUID.fromString(idOrKey)).orElse(null)
                : workItems.findByKey(idOrKey).orElse(null);
        if (wi == null) {
            throw new BusinessException(ErrorCode.PLT_4040, "工作项不存在: " + idOrKey);
        }
        return wi;
    }

    public Release release(String idOrKey) {
        Release r = looksUuid(idOrKey)
                ? releases.findById(UUID.fromString(idOrKey)).orElse(null)
                : releases.findByKey(idOrKey).orElse(null);
        if (r == null) {
            throw new BusinessException(ErrorCode.PLT_4040, "版本不存在: " + idOrKey);
        }
        return r;
    }

    public Product product(String idOrKey) {
        Product p = looksUuid(idOrKey)
                ? products.findById(UUID.fromString(idOrKey)).orElse(null)
                : products.findByKey(idOrKey).orElse(null);
        if (p == null) {
            throw new BusinessException(ErrorCode.PLT_4040, "产品不存在: " + idOrKey);
        }
        return p;
    }

    public Component component(String idOrKey) {
        Component c = looksUuid(idOrKey)
                ? components.findById(UUID.fromString(idOrKey)).orElse(null)
                : components.findByKey(idOrKey).orElse(null);
        if (c == null) {
            throw new BusinessException(ErrorCode.PLT_4040, "组件不存在: " + idOrKey);
        }
        return c;
    }

    public Sprint sprint(String idOrKey) {
        if (!looksUuid(idOrKey)) {
            throw new BusinessException(ErrorCode.PLT_4040, "迭代不存在: " + idOrKey);
        }
        return sprints.findById(UUID.fromString(idOrKey))
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "迭代不存在: " + idOrKey));
    }

    /**
     * RoadMap 条目（仅接受 uuid——prd.roadmap_item 无业务 key 列，06 §3 W2 建表定稿）。
     */
    public RoadmapItem roadmapItem(String id) {
        if (!looksUuid(id)) {
            throw new BusinessException(ErrorCode.PLT_4040, "RoadMap 条目不存在: " + id);
        }
        return roadmaps.findById(UUID.fromString(id))
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "RoadMap 条目不存在: " + id));
    }

    public StrategicGoal goal(String id) {
        if (!looksUuid(id)) {
            throw new BusinessException(ErrorCode.PLT_4040, "目标不存在: " + id);
        }
        return goals.findById(UUID.fromString(id))
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "目标不存在: " + id));
    }

    /** 用户 id（接受 uuid 或 username） */
    public UUID userId(String idOrName) {
        if (looksUuid(idOrName)) {
            return UUID.fromString(idOrName);
        }
        return users.findByUsername(idOrName)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLT_4040, "用户不存在: " + idOrName))
                .getId();
    }

    /** 可空版 userId（null 透传） */
    public UUID userIdOrNull(String idOrName) {
        return idOrName == null ? null : userId(idOrName);
    }

    /** 可空版工作项解析（null 透传；用于可选 requirementId） */
    public UUID workItemIdOrNull(String idOrKey) {
        return idOrKey == null ? null : workItem(idOrKey).getId();
    }

    public static boolean looksUuid(String s) {
        if (s == null || s.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
