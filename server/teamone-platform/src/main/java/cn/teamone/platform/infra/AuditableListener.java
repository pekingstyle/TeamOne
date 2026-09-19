package cn.teamone.platform.infra;

import jakarta.persistence.PreUpdate;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.Temporal;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * updatedAt 统一刷新监听（质量整改 B3）：{@code @PreUpdate} 置 updatedAt=now。
 *
 * <p>放 platform 供各域实体挂 {@code @EntityListeners(AuditableListener.class)}
 * （prd→platform 依赖允许）。反射定位 {@code setUpdatedAt(Temporal)} 并缓存，
 * 未挂该字段的实体静默跳过——监听器本身无侵入。</p>
 */
public class AuditableListener {

    private static final ConcurrentHashMap<Class<?>, Optional<Method>> SETTERS = new ConcurrentHashMap<>();

    @PreUpdate
    public void touch(Object entity) {
        Method setter = SETTERS
                .computeIfAbsent(entity.getClass(), AuditableListener::findUpdatedAtSetter)
                .orElse(null);
        if (setter != null) {
            try {
                setter.invoke(entity, Instant.now());
            } catch (ReflectiveOperationException ignored) {
                // 反射失败不阻断业务更新（updatedAt 由字段默认值兜底）
            }
        }
    }

    private static Optional<Method> findUpdatedAtSetter(Class<?> type) {
        for (Method m : type.getMethods()) {
            if (m.getName().equals("setUpdatedAt") && m.getParameterCount() == 1
                    && Temporal.class.isAssignableFrom(m.getParameterTypes()[0])) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }
}
