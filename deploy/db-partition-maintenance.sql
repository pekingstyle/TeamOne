-- =============================================================================
-- TeamOne 数据库核心大表按月分区自动化运维脚本（Spike S-3）
-- 遵循 PostgreSQL 18 原生声明式 RANGE 分区机制
-- 提供：分区自动检测预建、边界安全校验、已满历史分区解耦（DETACH）与无损归档
-- =============================================================================

-- 1. 确保 collab 域分区维护存储函数存在
CREATE OR REPLACE FUNCTION collab.maintain_message_partitions(months_ahead integer DEFAULT 3)
RETURNS TABLE(partition_name text, range_start text, range_end text, status text) 
LANGUAGE plpgsql
AS $$
DECLARE
    cur_date date := date_trunc('month', CURRENT_DATE)::date;
    target_date date;
    next_date date;
    part_name text;
    part_exists boolean;
    i integer;
BEGIN
    -- 循环预建从当前月开始、向后扩展 months_ahead 个月份的分区
    FOR i IN 0..months_ahead LOOP
        target_date := (cur_date + (i || ' month')::interval)::date;
        next_date := (target_date + '1 month'::interval)::date;
        part_name := 'message_' || to_char(target_date, 'YYYY"m"MM');

        -- 检查分区表是否已在当前数据库中存在
        SELECT EXISTS (
            SELECT 1 
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'collab' AND c.relname = part_name
        ) INTO part_exists;

        IF part_exists THEN
            partition_name := 'collab.' || part_name;
            range_start := to_char(target_date, 'YYYY-MM-01');
            range_end := to_char(next_date, 'YYYY-MM-01');
            status := 'ALREADY_EXISTS';
            RETURN NEXT;
        ELSE
            -- 动态执行创建子分区（PostgreSQL 会自动将父表上的索引、约束级联至子分区）
            BEGIN
                EXECUTE format(
                    'CREATE TABLE collab.%I PARTITION OF collab.message FOR VALUES FROM (%L) TO (%L);',
                    part_name, target_date, next_date
                );
                partition_name := 'collab.' || part_name;
                range_start := to_char(target_date, 'YYYY-MM-01');
                range_end := to_char(next_date, 'YYYY-MM-01');
                status := 'CREATED';
                RETURN NEXT;
            EXCEPTION WHEN OTHERS THEN
                partition_name := 'collab.' || part_name;
                range_start := to_char(target_date, 'YYYY-MM-01');
                range_end := to_char(next_date, 'YYYY-MM-01');
                status := 'ERROR: ' || SQLERRM;
                RETURN NEXT;
            END;
        END IF;
    END LOOP;
END;
$$;

COMMENT ON FUNCTION collab.maintain_message_partitions(integer) IS 
'自动化维护 collab.message 消息按月分区（预建当前月与未来 N 个月分区）';


-- 2. 安全解除历史分区挂载（DETACH PARTITION），使其转化为独立普通表供导出备份
CREATE OR REPLACE FUNCTION collab.detach_message_partition(p_part_name text)
RETURNS boolean
LANGUAGE plpgsql
AS $$
DECLARE
    clean_name text;
BEGIN
    -- 规范化表名（去除可能传入的 collab. 前缀）
    clean_name := replace(p_part_name, 'collab.', '');

    -- 校验指定表是否确实属于 collab.message 的子分区
    IF NOT EXISTS (
        SELECT 1 
        FROM pg_catalog.pg_inherits inh
        JOIN pg_catalog.pg_class parent ON parent.oid = inh.inhparent
        JOIN pg_catalog.pg_class child ON child.oid = inh.inhrelid
        JOIN pg_catalog.pg_namespace n ON n.oid = child.relnamespace
        WHERE n.nspname = 'collab' 
          AND parent.relname = 'message' 
          AND child.relname = clean_name
    ) THEN
        RAISE EXCEPTION 'Table collab.% is not a partition of collab.message', clean_name;
    END IF;

    -- 执行解除挂载（将子分区脱离为独立常规表）
    EXECUTE format('ALTER TABLE collab.message DETACH PARTITION collab.%I;', clean_name);
    RETURN true;
END;
$$;

COMMENT ON FUNCTION collab.detach_message_partition(text) IS 
'解除指定历史消息分区的挂载（DETACH），使其转为普通表以便安全归档或冷备';


-- 3. 分区状态与分布便捷视图
CREATE OR REPLACE VIEW collab.v_message_partition_stats AS
SELECT 
    child.relname AS partition_name,
    pg_catalog.pg_get_expr(child.relpartbound, child.oid) AS partition_bound,
    pg_catalog.pg_size_pretty(pg_catalog.pg_total_relation_size(child.oid)) AS total_size,
    pg_catalog.pg_stat_get_live_tuples(child.oid) AS live_tuples
FROM pg_catalog.pg_inherits inh
JOIN pg_catalog.pg_class parent ON parent.oid = inh.inhparent
JOIN pg_catalog.pg_class child ON child.oid = inh.inhrelid
JOIN pg_catalog.pg_namespace n ON n.oid = child.relnamespace
WHERE n.nspname = 'collab' AND parent.relname = 'message'
ORDER BY child.relname;

COMMENT ON VIEW collab.v_message_partition_stats IS 
'查看 collab.message 当前所有子分区、约束边界、磁盘占用与估计记录行数';
