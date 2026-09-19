-- V22：CI 真实执行最小闭环（M4-INC1 · docs/v2/11 §3.6 作业表子集 + §4 执行架构）
-- 遵循红线：
--   * 只追加，不改既有结构（pipeline_run 仅加列）；主键统一 gen_random_uuid()
--   * eng 域内逻辑引用不跨域建外键；run_id 同库 eng 域内沿用 V13 先例建 FK + CASCADE
--   * 零硬编码仓库/用户/MR UUID；作业状态机 CHECK 收口
--
-- 定位（docs/v2/11 §3.1）：eng.pipeline_job 是调度/执行/门禁的原子真相粒度；
-- pipeline_run.stages jsonb 降级为聚合展示快照（由作业结果驱动回写，形状保持 V16 前端契约）。

-- ================= eng.pipeline_job（作业执行真相表） =================
-- stage 取值：build（构建）/ test（单测门禁）——M4-INC1 两条链式作业；
-- status 作业级状态机与 run 级（pending/running/passed/failed/...）独立，成功态为 success；
-- cmd 为服务端模板生成（构建体系 × 阶段），绝不拼装用户输入，Runner 以空白分列成数组执行（无 shell）。
CREATE TABLE IF NOT EXISTS eng.pipeline_job (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  run_id      uuid NOT NULL REFERENCES eng.pipeline_run(id) ON DELETE CASCADE,
  seq         int  NOT NULL,                     -- run 内作业序号（链式推进顺序，从 1 起）
  stage       text NOT NULL CHECK (stage IN ('build', 'test')),
  name        text NOT NULL,                     -- 展示名（"构建"/"单测"）
  cmd         text NOT NULL,                     -- 服务端模板命令（mvn -q -f <workdir>/pom.xml ... 等）
  workdir     text,                              -- 构建层相对路径（""=仓库根；如 server/web），surefire 扫描起点
  status      text NOT NULL DEFAULT 'pending'
              CHECK (status IN ('pending', 'running', 'success', 'failed', 'skipped')),
  exit_code   int,                               -- 进程退出码（超时/未启动为 NULL）
  log_tail    text,                              -- 合流输出尾部 ~120 行（日志主体完整留存归 M4-INC2 对象存储）
  error_msg   text,                              -- 失败原因（工具链缺失/超时/导出失败等）
  attempt     int  NOT NULL DEFAULT 0,           -- 认领执行次数（SKIP LOCKED 认领时 +1）
  locked_by   text,                              -- 认领者标识 hostname:pid（内嵌 Runner）
  locked_at   timestamptz,
  started_at  timestamptz,
  finished_at timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_pipeline_job_run_seq UNIQUE (run_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_pipeline_job_run ON eng.pipeline_job (run_id);
-- Runner 认领扫描（WHERE status='pending' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED）
-- 的部分索引（docs/v2/11 §3.6 同型：只索引待认领行，积压可观测）
CREATE INDEX IF NOT EXISTS idx_pipeline_job_pending
  ON eng.pipeline_job (created_at) WHERE status = 'pending';

-- ================= eng.pipeline_run 只追加列（识别矩阵最小版落库） =================
-- build_system：trigger 时 BuildSystemDetector 识别结果（maven/npm/unknown；历史模拟 run 为 NULL，API 层透出 unknown）
-- workdir：识别出的构建层（构建体系 manifest 所在目录相对仓库根，""=根）
ALTER TABLE eng.pipeline_run ADD COLUMN IF NOT EXISTS build_system text;
ALTER TABLE eng.pipeline_run ADD COLUMN IF NOT EXISTS workdir text;
