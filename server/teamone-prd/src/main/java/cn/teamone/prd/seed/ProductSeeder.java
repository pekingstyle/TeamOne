package cn.teamone.prd.seed;

import cn.teamone.prd.domain.Component;
import cn.teamone.prd.domain.Product;
import cn.teamone.prd.domain.Release;
import cn.teamone.prd.domain.RoadmapItem;
import cn.teamone.prd.domain.Sprint;
import cn.teamone.prd.domain.StrategicGoal;
import cn.teamone.prd.domain.WorkItem;
import cn.teamone.prd.domain.KeySequence;
import cn.teamone.prd.repo.ComponentRepository;
import cn.teamone.prd.repo.KeySequenceRepository;
import cn.teamone.prd.repo.ProductRepository;
import cn.teamone.prd.repo.ReleaseRepository;
import cn.teamone.prd.repo.RoadmapItemRepository;
import cn.teamone.prd.repo.SprintRepository;
import cn.teamone.prd.repo.StrategicGoalRepository;
import cn.teamone.prd.repo.WorkItemRepository;
import cn.teamone.platform.domain.AppUser;
import cn.teamone.platform.domain.Department;
import cn.teamone.platform.repo.AppUserRepository;
import cn.teamone.platform.repo.DepartmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * prd 域开发种子（W2，幂等按 key：存在即跳过；风格对齐 app 模块 DevSeeder）。
 *
 * <p>开箱即演数据（V-2 验收矩阵依赖）：
 * G-1「平台效能提升」→ p1 TeamOne → 组件 流水线引擎/协同服务 → RM-1「v2.4.0 版本线」；
 * release v2.4.0（status=blocked、blocked=true、plan_date=2026-09-30、挂 RM-1/p1）；
 * sprint S-1 当前迭代（挂 p1/v2.4.0）；
 * 缺陷 D-88（致命·修复中·blockedRelease=v2.4.0·assignee=dev1·reporter=admin，release 投影 [D-88]）、
 * D-87（一般·新建·不挂阻塞——保证 D-88 关闭后门禁数学清零）；
 * 任务 T-103/T-104/T-105/T-106（todo 16h·dev1 / in_progress 24h·dev2 / done 8h·admin /
 * in_progress 12h·dev2，挂 p1+RM-1+v2.4.0+S-1；T-104/T-106 同人同窗构成 CF-1/CF-2 演示场景，
 * key 衔接 MR 种子的 T-1xx 序列——目标五层下钻与工时桑基图数据源）；
 * key_sequence DEFECT 起始 88（发号服务为「存量+1 后返回」语义 → 下一个新缺陷=D-89，V-2 步骤 3 依赖）。
 * 旧库升级幂等：RM-1↔v2.4.0 双向关联、D-88/D-87 的 RM/版本关联若为空一律回填；
 * R-5 AC③ 数据收敛：ensureTask/backfillLinks 挂条目时同步回填 goalId（读 RoadmapItem.getGoalId，
 * 条目路径工作项双路径一致）。</p>
 *
 * <p>strategic_goal / roadmap_item / sprint 表无 key 列，业务编号（G-1/RM-1/S-1）
 * 以名称前缀手填，按名称幂等。ApplicationRunner 间无稳定顺序且 DevSeeder 不可改动
 * （W2 红线），故本类对 admin/dev1/dev2 做同口径自举兜底（ensureUser 与 DevSeeder
 * 全字段一致）——两 Seeder 无论谁先执行，结果收敛一致。</p>
 *
 * <p>R-13 种子开关一拆二（评审必改①/D7）：本类为<b>演示数据</b>，挂
 * {@code teamone.seed.demo}（默认 true 保持现有行为）；dev 库 dogfooding 时置 false，
 * 以平台自建真实工作项驱动。账号/ACL/引导类开关在 teamone.seed.enabled，互不影响。</p>
 */
@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "teamone.seed.demo", havingValue = "true", matchIfMissing = true)
public class ProductSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductSeeder.class);

    private final StrategicGoalRepository goals;
    private final ProductRepository products;
    private final ComponentRepository components;
    private final RoadmapItemRepository roadmaps;
    private final ReleaseRepository releases;
    private final SprintRepository sprints;
    private final WorkItemRepository workItems;
    private final KeySequenceRepository keySequences;
    private final AppUserRepository users;
    private final DepartmentRepository departments;
    private final PasswordEncoder encoder;

    public ProductSeeder(StrategicGoalRepository goals, ProductRepository products,
                         ComponentRepository components, RoadmapItemRepository roadmaps,
                         ReleaseRepository releases, SprintRepository sprints,
                         WorkItemRepository workItems, KeySequenceRepository keySequences,
                         AppUserRepository users, DepartmentRepository departments,
                         PasswordEncoder encoder) {
        this.goals = goals;
        this.products = products;
        this.components = components;
        this.roadmaps = roadmaps;
        this.releases = releases;
        this.sprints = sprints;
        this.workItems = workItems;
        this.keySequences = keySequences;
        this.users = users;
        this.departments = departments;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppUser admin = user("admin");
        AppUser dev1 = user("dev1");
        AppUser dev2 = user("dev2");

        // ---------- 目标 G-1「平台效能提升」（无 key 列，G-1 以名称前缀手填） ----------
        StrategicGoal g1 = goals.findAll().stream()
                .filter(g -> "G-1 平台效能提升".equals(g.getName()))
                .findFirst().orElseGet(() -> {
                    StrategicGoal goal = new StrategicGoal();
                    goal.setName("G-1 平台效能提升");
                    goal.setDescription("战略目标 G-1：一站式研发协同，提升平台效能");
                    goal.setOwnerId(admin.getId());
                    return goals.save(goal);
                });

        // ---------- 产品 p1 TeamOne ----------
        Product p1 = products.findByKey("p1").orElseGet(() -> {
            Product p = new Product();
            p.setKey("p1");
            p.setName("TeamOne");
            p.setDescription("TeamOne 一站式研发协同平台");
            p.setGoalId(g1.getId());
            p.setOwnerId(admin.getId());
            return products.save(p);
        });

        // ---------- 组件两件套（英文键，兼作 bare repo 名） ----------
        Component pipeline = ensureComponent("pipeline-engine", "流水线引擎", p1, dev1);
        Component collab = ensureComponent("collab-service", "协同服务", p1, dev2);

        // ---------- RoadMap 条目 RM-1「v2.4.0 版本线」挂 G-1/p1 ----------
        RoadmapItem rm1 = roadmaps.findAll().stream()
                .filter(r -> "RM-1 v2.4.0 版本线".equals(r.getName()))
                .findFirst().orElseGet(() -> {
                    RoadmapItem r = new RoadmapItem();
                    r.setName("RM-1 v2.4.0 版本线");
                    r.setDescription("RoadMap 条目 RM-1：v2.4.0 版本线（挂 G-1/p1）");
                    r.setProductId(p1.getId());
                    r.setGoalId(g1.getId());
                    r.setOwnerId(admin.getId());
                    r.setStartDate(LocalDate.of(2026, 9, 1));
                    r.setDueDate(LocalDate.of(2026, 9, 30));
                    return roadmaps.save(r);
                });

        // ---------- release v2.4.0（blocked 门禁态） ----------
        Release v240 = releases.findByKey("v2.4.0").orElseGet(() -> {
            Release r = new Release();
            r.setKey("v2.4.0");
            r.setName("v2.4.0 版本");
            r.setProductId(p1.getId());
            r.setRoadmapItemId(rm1.getId());
            r.setStatus(Release.STATUS_BLOCKED);
            r.setBlocked(true);
            r.setPlanDate(LocalDate.of(2026, 9, 30));
            return releases.save(r);
        });

        // ---------- sprint S-1 当前迭代 ----------
        Sprint s1 = sprints.findAll().stream()
                .filter(s -> s.getName() != null && s.getName().startsWith("S-1 "))
                .findFirst().orElseGet(() -> {
                    Sprint s = new Sprint();
                    s.setName("S-1 当前迭代");
                    s.setProductId(p1.getId());
                    s.setReleaseId(v240.getId());
                    s.setCapacityHours(320);
                    s.setStartDate(LocalDate.of(2026, 9, 7));
                    s.setDueDate(LocalDate.of(2026, 9, 25));
                    return sprints.save(s);
                });

        // ---------- 关联补链（幂等回填：早期种子/旧库中已存在的实体也要补上空关联） ----------
        if (rm1.getReleaseId() == null) {
            rm1.setReleaseId(v240.getId());
            roadmaps.save(rm1);
        }
        if (v240.getRoadmapItemId() == null) {
            v240.setRoadmapItemId(rm1.getId());
            releases.save(v240);
        }

        // ---------- 缺陷 D-88（致命·修复中·阻塞 v2.4.0）/ D-87（一般·新建·不阻塞） ----------
        WorkItem d88 = ensureDefect("D-88", "流水线引擎构建偶发崩溃，阻塞 v2.4.0 发布",
                WorkItem.SEVERITY_FATAL, WorkItem.STATUS_DEFECT_FIXING,
                p1, pipeline, v240, dev1, admin);
        WorkItem d87 = ensureDefect("D-87", "协同服务消息列表分页显示重复行",
                WorkItem.SEVERITY_MAJOR, WorkItem.STATUS_DEFECT_NEW,
                p1, collab, null, dev2, admin);
        // 存量缺陷补链（挂 RM-1 + v2.4.0；sprint 关联已通）：仅回填空关联，不覆盖既有值
        backfillLinks(d88, rm1, v240);
        backfillLinks(d87, rm1, v240);

        // ---------- 任务三件套（key 衔接 MR 种子的 T-1xx 序列；工时/状态三态齐备，
        // 让目标五层下钻（G→RM→工作项）与工时桑基图都有像样的数据） ----------
        ensureTask("T-103", "流水线引擎并行任务调度优化", WorkItem.STATUS_TODO,
                p1, rm1, v240, dev1, admin, new BigDecimal("16.0"),
                LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 25));
        ensureTask("T-104", "协同服务已读回执性能调优", WorkItem.STATUS_IN_PROGRESS,
                p1, rm1, v240, dev2, admin, new BigDecimal("24.0"),
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 15));
        ensureTask("T-105", "v2.4.0 发布检查单核对与演练", WorkItem.STATUS_DONE,
                p1, rm1, v240, admin, admin, new BigDecimal("8.0"),
                LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 11));
        // T-106 与 T-104 同人（dev2）同窗重叠且均进行中：让冲突批算器稳定产出
        // CF-1（12+2.4h/天 > 容量 8h）与 CF-2（区间重叠）演示数据（含时间窗口/事件结构化字段）
        ensureTask("T-106", "消息通道断线重连补偿任务", WorkItem.STATUS_IN_PROGRESS,
                p1, rm1, v240, dev2, admin, new BigDecimal("12.0"),
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 18));

        // 门禁投影初始化（真相在 work_item 查询；此处仅为种子首态）
        if (!v240.getBlockedDefectIds().contains(d88.getId())) {
            v240.setBlockedDefectIds(List.of(d88.getId()));
            releases.save(v240);
        }

        // ---------- key_sequence DEFECT=88（下一个新缺陷 = D-89） ----------
        if (keySequences.findById("DEFECT").isEmpty()) {
            KeySequence seq = new KeySequence();
            seq.setType("DEFECT");
            seq.setNextVal(88);
            keySequences.save(seq);
        }

        log.info("[seed] prd ready (G-1/p1/pipeline-engine/collab-service/RM-1↔v2.4.0(blocked)/S-1/"
                                + "D-88/D-87/T-103~T-106, DEFECT seq=88)");
    }

    private Component ensureComponent(String key, String name, Product product, AppUser owner) {
        return components.findByKey(key).orElseGet(() -> {
            Component c = new Component();
            c.setKey(key);
            c.setName(name);
            c.setDescription(name + "（" + key + ".git）");
            c.setProductId(product.getId());
            c.setOwnerId(owner.getId());
            return components.save(c);
        });
    }

    private WorkItem ensureDefect(String key, String title, String severity, String status,
                                  Product product, Component component, Release blockedRelease,
                                  AppUser assignee, AppUser reporter) {
        WorkItem existing = workItems.findByKey(key).orElse(null);
        if (existing != null) {
            return existing;
        }
        WorkItem d = new WorkItem();
        d.setKey(key);
        d.setType(WorkItem.TYPE_DEFECT);
        d.setTitle(title);
        d.setStatus(status);
        d.setSeverity(severity);
        d.setPriority("P1");
        d.setProductId(product.getId());
        d.setComponentId(component.getId());
        d.setBlockedReleaseId(blockedRelease == null ? null : blockedRelease.getId());
        d.setSprintId(sprintIdOf(product)); // 挂当前迭代
        d.setAssigneeId(assignee.getId());
        d.setReporterId(reporter.getId());
        d.setStoryPoints(new BigDecimal("3.0"));
        d.setPath("/"); // 占位拿 id 后补真实路径（/{productId}/{id}/）
        WorkItem saved = workItems.saveAndFlush(d);
        saved.setPath("/" + product.getId() + "/" + saved.getId() + "/");
        return saved;
    }

    /**
     * 任务工作项 ensure（幂等按 key；挂 product + RM 条目 + 版本 + 当前迭代，estimate_hours
     * 与状态齐备——工时三桶/桑基图数据源）。已存在时仅回填空关联（roadmap/release/sprint），
     * 不覆盖既有业务值。
     */
    private WorkItem ensureTask(String key, String title, String status, Product product,
                                RoadmapItem roadmap, Release release, AppUser assignee,
                                AppUser reporter, BigDecimal estimateHours,
                                LocalDate startDate, LocalDate dueDate) {
        WorkItem existing = workItems.findByKey(key).orElse(null);
        if (existing != null) {
            backfillLinks(existing, roadmap, release);
            if (existing.getSprintId() == null) {
                existing.setSprintId(sprintIdOf(product));
                workItems.save(existing);
            }
            return existing;
        }
        WorkItem t = new WorkItem();
        t.setKey(key);
        t.setType(WorkItem.TYPE_TASK);
        t.setTitle(title);
        t.setStatus(status);
        t.setPriority("P2");
        t.setProductId(product.getId());
        t.setRoadmapItemId(roadmap.getId());
        // R-5 AC③ 数据收敛：挂条目即派生 goal_id（直连口径双路径一致，读条目 goal_id）
        t.setGoalId(roadmap.getGoalId());
        t.setReleaseId(release.getId());
        t.setSprintId(sprintIdOf(product)); // 挂当前迭代
        t.setAssigneeId(assignee.getId());
        t.setReporterId(reporter.getId());
        t.setEstimateHours(estimateHours);
        t.setStartDate(startDate);
        t.setDueDate(dueDate);
        t.setPath("/"); // 占位拿 id 后补真实路径（/{productId}/{id}/）
        WorkItem saved = workItems.saveAndFlush(t);
        saved.setPath("/" + product.getId() + "/" + saved.getId() + "/");
        return saved;
    }

    /**
     * 存量工作项补链（幂等：仅回填空关联 roadmap_item_id/release_id，不覆盖既有值）。
     * R-5 AC③ 数据收敛：回填 roadmap_item_id（或本就挂在本条目下）且 goal_id 缺失时，
     * 读 {@link RoadmapItem#getGoalId()} 幂等补齐——条目路径工作项必须带同源 goal_id，
     * 保证聚合链直连口径双路径一致。
     */
    private void backfillLinks(WorkItem wi, RoadmapItem roadmap, Release release) {
        boolean dirty = false;
        if (wi.getRoadmapItemId() == null) {
            wi.setRoadmapItemId(roadmap.getId());
            dirty = true;
        }
        if (roadmap.getId().equals(wi.getRoadmapItemId())
                && wi.getGoalId() == null && roadmap.getGoalId() != null) {
            wi.setGoalId(roadmap.getGoalId());
            dirty = true;
        }
        if (wi.getReleaseId() == null) {
            wi.setReleaseId(release.getId());
            dirty = true;
        }
        if (dirty) {
            workItems.save(wi);
        }
    }

    private UUID sprintIdOf(Product product) {
        List<Sprint> list = sprints.findByProductIdOrderByStartDateAsc(product.getId());
        return list.isEmpty() ? null : list.get(list.size() - 1).getId();
    }

    private AppUser user(String username) {
        return users.findByUsername(username).orElseGet(() -> {
            // 兜底自举（ApplicationRunner 顺序不定且 DevSeeder 不可改）：全字段与 DevSeeder 一致，
            // 正常路径（DevSeeder 先行）此分支永不触发。
            Department dept = departments.findByName("平台研发部").orElseGet(() -> {
                Department d = new Department();
                d.setName("平台研发部");
                return departments.save(d);
            });
            AppUser u = new AppUser();
            u.setUsername(username);
            u.setPasswordHash(encoder.encode("admin".equals(username) ? "Admin@123" : "Dev@12345"));
            u.setDisplayName(switch (username) {
                case "admin" -> "平台管理员";
                case "dev1" -> "开发一号";
                default -> "开发二号";
            });
            u.setTitle(switch (username) {
                case "admin" -> "平台负责人";
                case "dev1" -> "后端工程师";
                default -> "前端工程师";
            });
            u.setPlatformRole("admin".equals(username)
                    ? AppUser.PlatformRole.OWNER : AppUser.PlatformRole.MEMBER);
            u.setDepartmentId(dept.getId());
            return users.save(u);
        });
    }
}
