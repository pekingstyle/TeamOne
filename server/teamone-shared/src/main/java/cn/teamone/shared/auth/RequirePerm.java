package cn.teamone.shared.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 资源级权限声明（四步短路链：平台角色 → 部门负责人 → 组件负责人 → resource_acl → 默认拒绝）。
 * resourceType/action 与 platform.resource_acl 的列一一对应；resourceId 留空表示平台级资源。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePerm {
    String resourceType();

    String action();

    /** 留空 = 平台级资源（uuid_nil） */
    String resourceId() default "";
}
