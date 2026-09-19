package cn.teamone.eng.runner;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PipelineRunner 认领 SQL 契约钉死（M4-INC1 · 照 OutboxRelay 先例的 native SKIP LOCKED）：
 * 反射锁定认领 SQL 形状（表/过滤/排序/限量/锁语义）、认领落库字段（running/locked_by/
 * started_at/attempt+1）、调度周期（fixedDelay 5s）与灰度装配条件（simulated=false 才装配）。
 * 纯反射无 Spring——契约被改即测试红，防止后续批次无声漂移。
 *
 * @author Ivan Yang, 2026-09-19
 */
class PipelineRunnerContractTest {

    private static String staticField(String name) throws Exception {
        Field f = PipelineRunner.class.getDeclaredField(name);
        f.setAccessible(true);
        return (String) f.get(null);
    }

    @Test
    void claimSelect_skipLocked_contract() throws Exception {
        String sql = staticField("CLAIM_SELECT_SQL");
        assertTrue(sql.contains("FROM eng.pipeline_job"), "必须扫作业真相表");
        assertTrue(sql.contains("status = 'pending'"), "只认领待执行作业");
        assertTrue(sql.contains("ORDER BY created_at"), "按创建序（FIFO）");
        assertTrue(sql.contains("LIMIT 1"), "单飞并发 1：每次认领一条");
        assertTrue(sql.contains("FOR UPDATE SKIP LOCKED"), "锁行至事务提交且跳过已被锁行");
    }

    @Test
    void claimUpdate_marksRunningWithIdentity_contract() throws Exception {
        String sql = staticField("CLAIM_UPDATE_SQL");
        assertTrue(sql.contains("UPDATE eng.pipeline_job"));
        assertTrue(sql.contains("status = 'running'"), "认领即置 running");
        assertTrue(sql.contains("locked_by = ?"), "认领者标识 hostname:pid 占位参数");
        assertTrue(sql.contains("started_at = now()"));
        assertTrue(sql.contains("attempt = attempt + 1"), "认领计执行次数");
        assertTrue(sql.contains("WHERE id = ?"));
    }

    @Test
    void poll_scheduledFixedDelay5s() throws Exception {
        Method poll = PipelineRunner.class.getMethod("poll");
        Scheduled scheduled = poll.getAnnotation(Scheduled.class);
        assertNotNull(scheduled, "poll 必须由 @Scheduled 驱动");
        assertEquals("${teamone.pipeline.poll-interval-ms:5000}", scheduled.fixedDelayString(),
                "fixedDelay 默认 5s（可配）");
    }

    @Test
    void runner_assembledOnlyInRealMode() {
        ConditionalOnProperty cond = PipelineRunner.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(cond, "Runner 装配须受灰度开关约束");
        assertEquals("teamone.pipeline.simulated", cond.name()[0]);
        assertEquals("false", cond.havingValue(), "真实模式（false）才装配");
        assertTrue(cond.matchIfMissing(), "缺省（未配置）= 真实执行");
    }
}
