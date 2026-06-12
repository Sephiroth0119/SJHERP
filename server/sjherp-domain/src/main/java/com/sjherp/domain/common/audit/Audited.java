package com.sjherp.domain.common.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计标记注解（M2-T07，CLAUDE.md 原则 3）：标在领域 Service 的公有写方法上，
 * 由 app 层的 AuditAspect 拦截并写 audit_log 表。
 *
 * <p>审计边界选在领域 Service 写方法（而非 Controller / SQL 层）：
 * Agent 工具与 REST API 共用同一个 Service Bean，单点标注即双路径覆盖；
 * 且此处有业务语义（action 如 customer.disable），SQL 层只有行变更没有意图。
 *
 * <p>本注解是纯 Java 定义（零依赖），领域层不因审计引入 Spring。
 * 约定：被标注方法须有名为 {@code operator} 的 String 参数（操作人，
 * 人工=登录名 / Agent=agent:&lt;userId&gt;），返回值实现 {@link AuditTarget}
 * 时切面自动提取目标标识与变更摘要。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** 动作标识（业务语义），格式「目标.动作」，如 product.create / customer.disable */
    String action();

    /** 目标类型，如 product / customer / user（audit_log.target_type，与查询筛选对齐） */
    String targetType();
}
