-- V4：prd 域全表 + collab 最小集 + infra 四表 + audit 审计（05 架构文档 §2.4 为蓝本）
-- 修订红线（方向审查 2026-09-11）：
--   * 主键一律 gen_random_uuid()——实际部署为外部 PostgreSQL 17.5，uuidv7() 为 18 特性不可用（S-2 结论）
--   * release.status 五值（planned/coding/blocked/code_freeze/released，无 testing，06 §0 A5）
--   * work_item.status 类型感知复合 CHECK；requirement 不含 rejected（驳回回 draft，05 §6.1）
--   * collab.message 按月 RANGE 分区（2026m09 + default 兜底）；user_conversation_cursor 留 M2 不建
--   * audit.audit_log 普通表；按月分区 M2 再做

-- ================= prd：目标/产品/组件/RoadMap 条目（层级引用列 + owner，§2.3） =================

CREATE TABLE prd.strategic_goal (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name        text NOT NULL,
  description text,
  owner_id    uuid REFERENCES platform.app_user(id),
  parent_id   uuid REFERENCES prd.strategic_goal(id),
  version     int  NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE prd.product (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  key         text NOT NULL UNIQUE,               -- 仓库名/业务键根（05 §6.3 {productKey}）
  name        text NOT NULL,
  description text,
  goal_id     uuid REFERENCES prd.strategic_goal(id),
  owner_id    uuid REFERENCES platform.app_user(id),
  version     int  NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE prd.component (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  key         text NOT NULL UNIQUE,               -- 仓库名（05 §6.3 {componentKey}.git）
  name        text NOT NULL,
  description text,
  product_id  uuid NOT NULL REFERENCES prd.product(id),
  owner_id    uuid REFERENCES platform.app_user(id),
  version     int  NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE prd.roadmap_item (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name        text NOT NULL,
  description text,
  product_id  uuid REFERENCES prd.product(id),
  goal_id     uuid REFERENCES prd.strategic_goal(id),  -- 关联链（§2.3）
  owner_id    uuid REFERENCES platform.app_user(id),
  start_date  date,
  due_date    date,
  version     int  NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);

-- ================= prd.release（版本发布，门禁字段即状态；五值 CHECK，无 testing——06 §0 A5） =================

CREATE TABLE prd.release (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  key                text NOT NULL UNIQUE,        -- v2.4.0
  name               text NOT NULL,
  product_id         uuid NOT NULL REFERENCES prd.product(id),
  roadmap_item_id    uuid REFERENCES prd.roadmap_item(id),
  status             text NOT NULL DEFAULT 'planned'
                     CHECK (status IN ('planned','coding','blocked','code_freeze','released')),
  plan_date          date,
  code_freeze_date   date,
  blocked            boolean NOT NULL DEFAULT false,      -- 门禁：致命/严重缺陷未关闭
  blocked_defect_ids uuid[] NOT NULL DEFAULT '{}',        -- 冗余清单（UI 直读，真相见 work_item）
  env_progress       jsonb NOT NULL DEFAULT '{}',         -- {"dev":"passed","staging":"pending"}
  baseline_id        uuid,                                -- eng.baseline 逻辑引用
  released_at        timestamptz,
  version            int  NOT NULL DEFAULT 0,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_release_blocked_status CHECK (blocked = false OR status IN ('coding','blocked'))
);
CREATE INDEX idx_release_product_status ON prd.release (product_id, status);

ALTER TABLE prd.roadmap_item
  ADD COLUMN release_id uuid,
  ADD CONSTRAINT fk_roadmap_item_release FOREIGN KEY (release_id) REFERENCES prd.release(id);

-- ================= prd.sprint（迭代） =================

CREATE TABLE prd.sprint (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name           text NOT NULL,
  product_id     uuid NOT NULL REFERENCES prd.product(id),
  release_id     uuid REFERENCES prd.release(id),
  capacity_hours int,
  start_date     date,
  due_date       date,
  version        int  NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);

-- ================= prd.work_item（单表工作项：task/test_task/defect/requirement） =================

CREATE TABLE prd.work_item (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  key                text NOT NULL UNIQUE,            -- REQ-1 / T-12 / TT-9 / D-88
  type               text NOT NULL CHECK (type IN ('requirement','task','test_task','defect')),
  title              text NOT NULL,
  description        text,
  status             text NOT NULL,
  priority           text CHECK (priority IN ('P0','P1','P2','P3')),
  assignee_id        uuid REFERENCES platform.app_user(id),
  reporter_id        uuid NOT NULL REFERENCES platform.app_user(id),
  product_id         uuid REFERENCES prd.product(id),
  component_id       uuid REFERENCES prd.component(id),
  parent_id          uuid REFERENCES prd.work_item(id),
  path               text NOT NULL,                   -- 物化路径 '/g1/rq3/w12/'，LIKE 前缀查子树
  sprint_id          uuid REFERENCES prd.sprint(id),
  release_id         uuid REFERENCES prd.release(id), -- 交付版本
  roadmap_item_id    uuid REFERENCES prd.roadmap_item(id),
  goal_id            uuid REFERENCES prd.strategic_goal(id),  -- 需求直挂目标（R2）
  story_points       numeric(5,1),
  start_date         date,
  due_date           date,
  labels             jsonb NOT NULL DEFAULT '[]',
  -- 类型专用列（按 type 启用，CHECK 保证）
  severity           text CHECK (severity IS NULL OR severity IN ('致命','严重','一般','轻微')),
  blocked_release_id uuid REFERENCES prd.release(id), -- 缺陷阻塞的版本（门禁真相）
  found_in_id        uuid REFERENCES prd.work_item(id), -- 缺陷发现于测试任务
  related_id         uuid REFERENCES prd.work_item(id), -- 关联工作项
  fixed_mr_id        uuid,                            -- 修复 MR（eng.mr_review 逻辑引用）
  requirement_id     uuid REFERENCES prd.work_item(id), -- 任务/测试任务挂需求
  version            int  NOT NULL DEFAULT 0,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  -- 类型感知复合状态 CHECK（05 §6.1 四状态机；defect 无 rejected，requirement 驳回回 draft）
  CONSTRAINT ck_work_item_type_status CHECK (
       (type = 'task'        AND status IN ('todo','in_progress','done'))
    OR (type = 'test_task'   AND status IN ('pending','in_progress','passed','failed'))
    OR (type = 'defect'      AND status IN ('新建','修复中','已修复','回归通过','已关闭','重新打开'))
    OR (type = 'requirement' AND status IN ('draft','pending_review','accepted','in_dev','delivered','closed'))
  ),
  CONSTRAINT ck_work_item_type_severity CHECK (
    (type = 'defect' AND severity IS NOT NULL AND requirement_id IS NULL)
    OR (type <> 'defect' AND severity IS NULL)
  )
);
CREATE INDEX idx_work_item_type_status ON prd.work_item (type, status);
CREATE INDEX idx_work_item_assignee_status ON prd.work_item (assignee_id, status);
CREATE INDEX idx_work_item_sprint ON prd.work_item (sprint_id) WHERE sprint_id IS NOT NULL;
CREATE INDEX idx_work_item_release ON prd.work_item (release_id) WHERE release_id IS NOT NULL;
CREATE INDEX idx_work_item_path ON prd.work_item (path text_pattern_ops);
-- 发布门禁热点：某版本未关闭的致命/严重缺陷（部分索引，查询恒走索引）
CREATE INDEX idx_work_item_gate ON prd.work_item (blocked_release_id)
  WHERE blocked_release_id IS NOT NULL
    AND severity IN ('致命','严重')
    AND status NOT IN ('已关闭','回归通过');
CREATE INDEX idx_work_item_labels ON prd.work_item USING gin (labels);

CREATE TABLE prd.work_item_link (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  from_item_id uuid NOT NULL REFERENCES prd.work_item(id),
  to_item_id   uuid NOT NULL REFERENCES prd.work_item(id),
  relation     text NOT NULL CHECK (relation IN ('discovered_in','relates_to','fixed_by','blocks')),
  created_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (from_item_id, to_item_id, relation)
);

-- ================= prd.requirement_review（需求评审，会签） =================

CREATE TABLE prd.requirement_review (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  requirement_id uuid NOT NULL REFERENCES prd.work_item(id),
  round          int NOT NULL DEFAULT 1,              -- 驳回后重提轮次+1
  reviewer_id    uuid NOT NULL REFERENCES platform.app_user(id),
  result         text NOT NULL DEFAULT 'pending'
                 CHECK (result IN ('pending','approved','rejected')),
  comment        text,
  decided_at     timestamptz,
  version        int  NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (requirement_id, round, reviewer_id)
);
-- 会签判定：round 内无 pending 且无 rejected → 全部通过；存在 rejected → 驳回回 draft（05 §6.1）

-- ================= prd.key_sequence / calendar / feature_flag =================

CREATE TABLE prd.key_sequence (
  type     text PRIMARY KEY,          -- DEFECT/TASK/TEST_TASK/REQUIREMENT
  next_val bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
-- 发号：INSERT ... ON CONFLICT(type) DO UPDATE SET next_val=key_sequence.next_val+1 RETURNING next_val（行锁天然串行）

CREATE TABLE prd.calendar (
  date        date PRIMARY KEY,
  is_workday  boolean NOT NULL DEFAULT true,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE prd.feature_flag (
  flag_key   text PRIMARY KEY,
  enabled    boolean NOT NULL DEFAULT false,
  note       text,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- ================= infra 四表（outbox 原样照抄 05 §2.4） =================

CREATE TABLE infra.outbox_event (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  aggregate_type text NOT NULL,
  aggregate_id   uuid NOT NULL,
  type           text NOT NULL,                       -- defect.closed / release.published …
  payload        jsonb NOT NULL,
  actor_id       uuid,
  trace_id       text,
  occurred_at    timestamptz NOT NULL DEFAULT now(),
  published_at   timestamptz,                         -- NULL=待投递
  retry_count    int NOT NULL DEFAULT 0
);
CREATE INDEX idx_outbox_unpublished ON infra.outbox_event (published_at) WHERE published_at IS NULL;  -- 投递器扫描

CREATE TABLE infra.processed_event (
  event_id    uuid PRIMARY KEY,       -- 消费幂等：重复投递不再处理
  consumed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE infra.dead_letter (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  type        text NOT NULL,
  payload     jsonb,
  error       text,
  retry_count int NOT NULL DEFAULT 0,
  dead_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE infra.idempotency_record (
  key            text PRIMARY KEY,    -- Idempotency-Key（24h 快照，§3.1）
  response_status int NOT NULL,
  response_body  jsonb,
  created_at     timestamptz NOT NULL DEFAULT now()
);

-- ================= audit.audit_log（append-only；按月分区 M2 再做） =================

CREATE TABLE audit.audit_log (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  actor_id      uuid,
  action        text NOT NULL,        -- login.success / login.failure / acl.changed / release.publish …
  resource_type text,
  resource_id   text,
  detail        jsonb,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_log_created_at ON audit.audit_log (created_at);
COMMENT ON TABLE audit.audit_log IS '审计日志（append-only，M1 普通表；按月分区 M2 落地）';

-- ================= collab 三表（M1 最小集；user_conversation_cursor 留 M2） =================

CREATE TABLE collab.conversation (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  type              text NOT NULL CHECK (type IN ('dm','group','topic','channel')),
  name              text,                             -- dm 为 NULL（成员即名字）
  dedup_key         text UNIQUE,                      -- dm 专用：'dm:{小id}:{大id}'，防裂会话
  -- 对象化话题专用（type='topic'）
  target_type       text CHECK (target_type IS NULL OR target_type IN
                    ('goal','product','component','roadmap','release','sprint','requirement',
                     'task','test_task','defect','mr','pipeline','baseline')),
  target_id         uuid,
  auto_created      boolean NOT NULL DEFAULT false,   -- 随对象自动建题
  pinned_message_id bigint,
  -- 生命周期
  archived_at       timestamptz,
  archived_reason   text,     -- target_closed / sprint_ended / release_released / baseline_frozen / manual
  last_message_id   bigint,
  last_message_at   timestamptz,                      -- 会话列表排序（同事务冗余更新）
  version           int  NOT NULL DEFAULT 0,
  created_at        timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_conversation_topic CHECK ((type='topic') = (target_type IS NOT NULL))
);
CREATE UNIQUE INDEX uq_conversation_topic ON collab.conversation (target_type, target_id)
  WHERE type = 'topic' AND target_type IS NOT NULL;   -- 一个对象一个话题
CREATE INDEX idx_conversation_active ON collab.conversation (archived_at) WHERE archived_at IS NULL;

CREATE TABLE collab.conversation_member (
  conversation_id uuid REFERENCES collab.conversation(id),
  user_id         uuid REFERENCES platform.app_user(id),
  role            text NOT NULL DEFAULT 'member' CHECK (role IN ('owner','member')),
  joined_at       timestamptz NOT NULL DEFAULT now(),
  created_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (conversation_id, user_id)
);
CREATE INDEX idx_conv_member_user ON collab.conversation_member (user_id);

-- 消息：bigint identity 兼做会话内排序（全局单调⇒会话内单调）；按月 RANGE 分区（PG17 支持分区表 identity）
CREATE TABLE collab.message (
  id              bigint GENERATED ALWAYS AS IDENTITY,
  conversation_id uuid NOT NULL REFERENCES collab.conversation(id),
  sender_id       uuid NOT NULL REFERENCES platform.app_user(id),
  kind            text NOT NULL DEFAULT 'text'
                  CHECK (kind IN ('text','file','image','card','system')),
  body            text,                               -- kind=text 的内容；card 时为 jsonb 字符串
  attachments     jsonb NOT NULL DEFAULT '[]',        -- [{fileId,name,size,mime,thumbUrl}] → platform.file
  ref_type        text,
  ref_id          uuid,                               -- 对象引用卡（可跳转），对应原型 attMeta
  created_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE collab.message_2026m09 PARTITION OF collab.message
  FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
-- DEFAULT 分区兜底（滚动分区脚本 S-3 留 M2）
CREATE TABLE collab.message_default PARTITION OF collab.message DEFAULT;

CREATE INDEX idx_message_conv ON collab.message (conversation_id, id);

-- 干系人自动拉人规则（05 §6.2，Flyway 种子，可运维修改）
CREATE TABLE collab.stakeholder_rule (
  target_type text NOT NULL,
  relation    text NOT NULL,
  include     text NOT NULL,          -- 拉入者清单（角色码逗号分隔）
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (target_type, relation)
);

INSERT INTO collab.stakeholder_rule (target_type, relation, include) VALUES
  ('requirement', 'auto_topic', 'owner,reviewers,product_owner,goal_owner'),
  ('release',     'auto_topic', 'release_manager,blocked_defect_assignees,sprint_assignees'),
  ('defect',      'auto_topic', 'reporter,fixer,test_task_assignee,release_manager'),
  ('sprint',      'auto_topic', 'owner,domain_lead'),
  ('roadmap',     'auto_topic', 'owner,domain_lead'),
  ('goal',        'auto_topic', 'owner,domain_lead')
ON CONFLICT (target_type, relation) DO NOTHING;
