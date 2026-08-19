package com.equipmock.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在 {@link MockHandler} 实现类上，声明拦截目标（02 §2 / 05 §3）。
 *
 * <ul>
 *   <li>{@link #targetClasses()}：精确 FQCN，首版不支持通配（防误伤）。</li>
 *   <li>{@link #methods()}：方法名；{@code {"*"}} 表示类中全部声明方法（declaredOnly 语义，
 *       不含继承自父类的方法）。</li>
 *   <li>{@link #matchOverloads()}：true = 同签名规则作用于全部重载。</li>
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface MockInterceptor {

    /** 目标类精确 FQCN，可多个 */
    String[] targetClasses();

    /** 目标方法名，可多个；{"*"} 表示全部声明方法 */
    String[] methods();

    /** true = 同名全部重载共享拦截 */
    boolean matchOverloads() default true;
}
