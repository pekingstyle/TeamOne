package cn.teamone.app.health;

import cn.teamone.eng.infra.git.GitPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 自定义健康指标测试（V-17）。
 */
class HealthIndicatorsTest {

    @Test
    void gitStorageHealth_unconfigured_returnsUp() {
        GitStorageHealthIndicator indicator = new GitStorageHealthIndicator("", null);
        Health health = indicator.health();
        assertEquals(Status.UP, health.getStatus());
        assertEquals("UNCONFIGURED", health.getDetails().get("status"));
    }

    @Test
    void gitStorageHealth_nonExistentDir_returnsDown() {
        GitStorageHealthIndicator indicator = new GitStorageHealthIndicator("Z:/non/existent/path/xyz", null);
        Health health = indicator.health();
        assertEquals(Status.DOWN, health.getStatus());
        assertNotNull(health.getDetails().get("error"));
    }

    @Test
    void gitStorageHealth_validDir_returnsUpWithMetrics(@TempDir Path tempDir) {
        // 创建一个模拟的 bare repo 目录
        File repoDir = new File(tempDir.toFile(), "test.git");
        assertTrue(repoDir.mkdir());

        GitPort gitPort = mock(GitPort.class);
        GitStorageHealthIndicator indicator = new GitStorageHealthIndicator(tempDir.toString(), gitPort);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(1, health.getDetails().get("repoCount"));
        assertTrue((Long) health.getDetails().get("usableSpaceBytes") > 0);
        assertTrue((Boolean) health.getDetails().get("gitPortAvailable"));
    }

    @Test
    void valkeyHealth_nullTemplate_returnsUpWithDisabled() {
        ValkeyHealthIndicator indicator = new ValkeyHealthIndicator(null);
        Health health = indicator.health();
        assertEquals(Status.UP, health.getStatus());
        assertEquals("DISABLED", health.getDetails().get("valkey"));
    }

    @Test
    void valkeyHealth_connected_returnsUpWithLatency() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection conn = mock(RedisConnection.class);

        when(template.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(conn);
        when(conn.ping()).thenReturn("PONG");

        ValkeyHealthIndicator indicator = new ValkeyHealthIndicator(template);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("CONNECTED", health.getDetails().get("valkey"));
        assertEquals("PONG", health.getDetails().get("response"));
        assertNotNull(health.getDetails().get("latencyMs"));
    }

    @Test
    void valkeyHealth_connectionFailed_returnsUpWithDegraded() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);

        when(template.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenThrow(new RuntimeException("Connection refused"));

        ValkeyHealthIndicator indicator = new ValkeyHealthIndicator(template);
        Health health = indicator.health();

        // 遵守 W3 红线 4：Valkey 异常时系统降级，健康状态依旧为 UP
        assertEquals(Status.UP, health.getStatus());
        assertEquals("DEGRADED", health.getDetails().get("valkey"));
        assertNotNull(health.getDetails().get("warning"));
    }
}
